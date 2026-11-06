"""Sustained synthetic load against the isolated event rehearsal (API 8082, devfest_test data only).

Mode "mixed" (default): simulates 16 or 48 team clients, 4 room managers and 1 admin polling the revision
endpoint, refetching their board when it changes, submitting room answers with idempotent retries and judging
concurrently. Results are written to docs/event-load-<scale>x.json.

Mode "sse": opens --streams live revision streams (GET /event/stream with single-use tickets, spread over the 21
seeded accounts), performs --actions admin timer actions and measures, per action and stream, the time from the
action's HTTP response to the matching "revision" event. Results are written to docs/event-load-sse-<N>.json.

Mode "quiz": the AI room activity in one compressed round. The AI manager imports 45 questions and 3 repairs, the
admin sends all 16 teams to the AI room (room exceptions for groups B-D) and starts round 1; every team answers all
45 questions, finishes its MCQs and submits its 3 repairs; the last repair evaluates it against the pass score (a pass
collects the room's clue), while the manager polls the results every 5 s. Every team must be evaluated exactly once
with the outcome its own total predicts. Results are written to docs/event-load-quiz.json.

Mode "linux": the Linux room in one compressed round. The manager uploads a package of just under 20 MB unpacked and
sets the answer keys; the admin sends all 16 teams to the room. Every team starts its terminal (files info, the 20 MB
package download, then a restart whose If-None-Match must answer 304), saves its six-field form five times and
submits it once; the last two teams only save, so the round's end submits them. The manager keeps the organizer
dashboard open (a GET every 5 s). Every team must end with exactly one submission, the dashboard total its own
answers predict and its clue iff its final clue was right; the backend heap is sampled during the downloads. The
bundle download is measured on its own and excluded from the 300 ms p95 limit. Results go to docs/event-load-linux.json.
"""
import argparse
import datetime as dt
import http.client
import json
import math
from pathlib import Path
import random
import re
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_BASE = "http://127.0.0.1:8082/api/v1"
ALLOWED_HOSTS = {"127.0.0.1", "localhost"}
POLL_SECONDS = 10
SUBMIT_SECONDS = 60
JUDGE_SECONDS = 20
RETRY_SHARE = 0.2
SUBMISSION_LIST_LIMIT = 50
# Access tokens expire after 15 minutes and the harness signs in only once per account.
MAX_SECONDS = 780


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scale", type=int, choices=(1, 3), default=1, help="team clients per team account")
    parser.add_argument("--seconds", type=int, default=300, help=f"measured duration (30..{MAX_SECONDS})")
    parser.add_argument("--base-url", default=DEFAULT_BASE)
    parser.add_argument("--ui-url", default="http://localhost:4202", help="linux: where the site's static files are served")
    parser.add_argument("--mode", choices=("mixed", "sse", "quiz", "linux"), default="mixed")
    parser.add_argument("--streams", type=int, default=21, help="sse: concurrent streams (1..300)")
    parser.add_argument("--actions", type=int, default=20, help="sse: admin actions to announce (1..200)")
    parser.add_argument("--gap-ms", type=int, default=500, help="sse: pause between admin actions")
    args = parser.parse_args()
    if not 1 <= args.streams <= 300 or not 1 <= args.actions <= 200:
        parser.error("--streams must be 1..300 and --actions 1..200")
    if not 30 <= args.seconds <= MAX_SECONDS:
        parser.error(f"--seconds must be between 30 and {MAX_SECONDS}")
    if urllib.parse.urlparse(args.base_url).hostname not in ALLOWED_HOSTS:
        parser.error("Refusing a non-local base URL: only 127.0.0.1 or localhost rehearsals are allowed.")
    return args


class Api:
    def __init__(self, base):
        self.base = base.rstrip("/")

    def call(self, method, path, token=None, body=None, key=None):
        """Returns (status, parsed JSON or None, elapsed ms); status 0 means a network failure."""
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        if key:
            headers["Idempotency-Key"] = key
        payload = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(self.base + path, data=payload, headers=headers, method=method)
        started = time.perf_counter()
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                status, raw = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, raw = error.code, error.read()
        except (urllib.error.URLError, TimeoutError, ConnectionError) as error:
            return 0, {"detail": str(error)}, (time.perf_counter() - started) * 1000
        elapsed = (time.perf_counter() - started) * 1000
        return status, (json.loads(raw) if raw else None), elapsed

    def setup(self, method, path, token=None, body=None):
        status, data, _ = self.call(method, path, token, body)
        if not 200 <= status < 300:
            raise SystemExit(f"Setup failed: {method} {path} -> {status} {data}")
        return data

    def login(self, email, password):
        return self.setup("POST", "/auth/login", body={"email": email, "password": password})["accessToken"]


def route_template(path):
    return re.sub(r"/\d+(?=/|$)", "/{id}", path)


class Metrics:
    def __init__(self):
        self.lock = threading.Lock()
        self.latencies = {}
        self.errors = []
        self.expected_conflicts = 0

    def record(self, method, path, status, elapsed, conflict_expected=False):
        endpoint = f"{method} {route_template(path)}"
        with self.lock:
            self.latencies.setdefault(endpoint, {"ms": [], "errors": 0})["ms"].append(elapsed)
            if conflict_expected and status == 409:
                self.expected_conflicts += 1
            elif not 200 <= status < 300:
                self.latencies[endpoint]["errors"] += 1
                self.errors.append({"endpoint": endpoint, "status": status})

    def error(self, description):
        with self.lock:
            self.errors.append({"endpoint": "check", "status": None, "detail": description})


class ReceiptLedger:
    """Proves that retries replay the same receipt and that every new key creates a new one."""

    def __init__(self):
        self.lock = threading.Lock()
        self.receipt_by_key = {}
        self.key_by_receipt = {}
        self.accepted_by_team = {}
        self.retries = 0
        self.retry_mismatches = 0
        self.duplicate_receipts = 0

    def accept(self, team, key, receipt):
        with self.lock:
            if self.key_by_receipt.setdefault(receipt["id"], key) != key:
                self.duplicate_receipts += 1
            self.receipt_by_key[key] = receipt
            self.accepted_by_team.setdefault(team, set()).add(receipt["id"])

    def replay(self, key, receipt):
        with self.lock:
            self.retries += 1
            if self.receipt_by_key.get(key) != receipt:
                self.retry_mismatches += 1


class Client(threading.Thread):
    """Polls the revision every 10 s and refetches its one board when the revision changes."""

    def __init__(self, run, token, board_path):
        super().__init__(daemon=True)
        self.run_state, self.token, self.board_path = run, token, board_path
        self.revision = None
        self.board = None
        self.next_poll = math.inf

    def request(self, method, path, body=None, key=None, conflict_expected=False):
        status, data, elapsed = self.run_state.api.call(method, path, self.token, body, key)
        self.run_state.metrics.record(method, path, status, elapsed, conflict_expected)
        return status, data

    def refetch_board(self):
        status, data = self.request("GET", self.board_path)
        if status == 200:
            self.board = data

    def poll(self):
        status, data = self.request("GET", "/event/revision")
        if status == 200 and data["revision"] != self.revision:
            self.revision = data["revision"]
            self.refetch_board()
        self.next_poll += POLL_SECONDS

    def act(self):
        """Role-specific scheduled work; returns the next time it wants to run."""
        return math.inf

    def run(self):
        # Jittered starts spread the polls the way independently opened browser tabs would.
        self.next_poll = self.run_state.started + random.uniform(0, POLL_SECONDS)
        next_action = self.first_action()
        while time.monotonic() < self.run_state.deadline:
            now = time.monotonic()
            if now >= self.next_poll:
                self.poll()
            elif now >= next_action:
                next_action = self.act()
            else:
                time.sleep(min(self.next_poll, next_action, self.run_state.deadline) - now)

    def first_action(self):
        return math.inf


class TeamClient(Client):
    def __init__(self, run, token, team, client_number):
        super().__init__(run, token, "/event/team/board")
        self.team, self.client_number, self.sequence = team, client_number, 0

    def first_action(self):
        return self.run_state.started + random.uniform(5, SUBMIT_SECONDS)

    def act(self):
        room = (self.board or {}).get("scheduledRoom")
        if room and room["open"]:
            self.submit(room["roomId"])
        return time.monotonic() + random.uniform(SUBMIT_SECONDS * 0.8, SUBMIT_SECONDS * 1.2)

    def submit(self, room_id):
        self.sequence += 1
        key = str(uuid.uuid4())
        path = f"/event/team/rooms/{room_id}/submissions"
        body = {"technicalOutput": f"Load client {self.team}.{self.client_number} attempt {self.sequence}",
                "clueAnswer": f"LOAD CHECK {self.sequence}"}
        status, receipt = self.request("POST", path, body, key)
        if status != 200:
            return
        self.run_state.ledger.accept(self.team, key, receipt)
        if random.random() < RETRY_SHARE:
            status, replayed = self.request("POST", path, body, key)
            if status == 200:
                self.run_state.ledger.replay(key, replayed)


class ManagerClient(Client):
    STATUS_CYCLE = {"PENDING": "PASS", "PASS": "FAIL", "FAIL": "PENDING"}

    def __init__(self, run, token, room_id):
        super().__init__(run, token, f"/event/rooms/{room_id}/board")
        self.room_id = room_id

    def first_action(self):
        return self.run_state.started + random.uniform(2, JUDGE_SECONDS)

    def act(self):
        teams = (self.board or {}).get("teams") or []
        if teams:
            self.judge(random.choice(teams)["judgement"])
        return time.monotonic() + random.uniform(JUDGE_SECONDS * 0.8, JUDGE_SECONDS * 1.2)

    def judge(self, judgement):
        body = {"status": self.STATUS_CYCLE[judgement["status"]], "hints": random.randint(0, 3),
                "note": "Synthetic load review.", "version": judgement["version"]}
        path = f"/event/rooms/{self.room_id}/judgements/{judgement['teamId']}"
        status, _ = self.request("PUT", path, body, conflict_expected=True)
        # Own writes and conflicts both make the cached versions stale.
        if status in (200, 409):
            self.refetch_board()


class LoadRun:
    def __init__(self, api):
        self.api = api
        self.metrics = Metrics()
        self.ledger = ReceiptLedger()
        self.started = self.deadline = None

    def begin(self, seconds):
        self.started = time.monotonic()
        self.deadline = self.started + seconds


def sign_in_everyone(api, config):
    """Each account signs in exactly once, well inside the 10 logins per account per minute limit."""
    admin = api.login(config["adminEmail"], config["adminPassword"])
    managers = [api.login(email, config["seedPassword"]) for email in config["managers"].values()]
    teams = [api.login(email, config["seedPassword"]) for email in config["members"]]
    return admin, managers, teams


# Practice clue keys exist only in test and script code; each room's manager sets its key like on the board.
PRACTICE_KEYS = {"AI": "WHITE VAN", "PROBLEM_SOLVING": "RED CLAY", "LINUX": "WEST TOWER", "HARDWARE": "+970-59-881"}


def set_clue_keys(api, managers):
    """A room cannot open without a clue key, so every manager sets one before the admin opens the rooms."""
    for token in managers:
        room_id = api.setup("GET", "/event/home", token)["roomId"]
        room = api.setup("GET", f"/event/rooms/{room_id}/board", token)["room"]
        api.setup("PUT", f"/event/rooms/{room_id}/content", token, {
            "clueKey": PRACTICE_KEYS[room["code"]], "recoveryPrompt": room["recoveryPrompt"],
            "recoveryAnswer": room["recoveryAnswer"], "version": room["version"]})


def prepare_round_one(api, admin):
    """Round 1 with every room open and its timer running (25 min by default, longer than any run) gives every
    group an open scheduled room for the whole run. A new round is only ASSIGNED, so the timer is started here."""
    api.setup("POST", "/event/admin/round", admin, {"round": 1})
    for room in api.setup("GET", "/event/admin/board", admin)["rooms"]:
        api.setup("POST", f"/event/admin/rooms/{room['id']}/open", admin)
    api.setup("POST", "/event/admin/round/timer/start", admin)


def build_clients(run, admin, managers, teams, scale):
    clients = [Client(run, admin, "/event/admin/board")]
    for token in managers:
        room_id = run.api.setup("GET", "/event/home", token)["roomId"]
        if room_id is None:
            raise SystemExit("A seeded manager has no room; restart the rehearsal to reseed.")
        clients.append(ManagerClient(run, token, room_id))
    for team, token in enumerate(teams, start=1):
        clients.extend(TeamClient(run, token, team, number) for number in range(1, scale + 1))
    return clients


def cross_check_submissions(run, teams):
    """Every accepted receipt must be listed by GET /team/submissions (which returns the newest 50)."""
    results = []
    for team, token in enumerate(teams, start=1):
        accepted = run.ledger.accepted_by_team.get(team, set())
        listed = {receipt["id"] for receipt in run.api.setup("GET", "/event/team/submissions", token)}
        complete = accepted <= listed if len(accepted) <= SUBMISSION_LIST_LIMIT else len(listed) == SUBMISSION_LIST_LIMIT
        if not complete:
            run.metrics.error(f"team {team}: {len(accepted)} accepted but {len(listed)} listed")
        results.append({"team": team, "accepted": len(accepted), "listed": len(listed), "complete": complete})
    return results


def percentile(sorted_values, share):
    return sorted_values[max(0, math.ceil(share * len(sorted_values)) - 1)]


def endpoint_summary(latencies):
    summary = {}
    for endpoint, data in sorted(latencies.items()):
        values = sorted(data["ms"])
        summary[endpoint] = {"requests": len(values), "errors": data["errors"],
                             "p50Ms": round(percentile(values, .50), 1), "p95Ms": round(percentile(values, .95), 1),
                             "p99Ms": round(percentile(values, .99), 1), "maxMs": round(values[-1], 1)}
    return summary


def build_report(args, run, clients, cross_check, elapsed):
    ledger, metrics = run.ledger, run.metrics
    endpoints = endpoint_summary(metrics.latencies)
    return {
        "testedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "environment": "Local Windows, PostgreSQL devfest_test, API 8082. Synthetic event traffic; no WAN latency.",
        "baseUrl": args.base_url, "scale": args.scale, "seconds": args.seconds, "elapsedSeconds": round(elapsed, 1),
        "clients": {"teams": sum(isinstance(c, TeamClient) for c in clients),
                    "managers": sum(isinstance(c, ManagerClient) for c in clients), "admins": 1},
        "endpoints": endpoints,
        "totals": {"requests": sum(e["requests"] for e in endpoints.values()), "errors": len(metrics.errors),
                   "expectedJudgementConflicts": metrics.expected_conflicts,
                   "acceptedSubmissions": len(ledger.receipt_by_key), "retries": ledger.retries,
                   "retryMismatches": ledger.retry_mismatches, "duplicateReceiptIds": ledger.duplicate_receipts},
        "submissionCrossCheck": cross_check,
        "errorSamples": metrics.errors[:50],
    }


def print_summary(report):
    print(f"{'endpoint':<52}{'requests':>9}{'errors':>8}{'p50':>9}{'p95':>9}{'p99':>9}{'max':>9}")
    for endpoint, row in report["endpoints"].items():
        print(f"{endpoint:<52}{row['requests']:>9}{row['errors']:>8}{row['p50Ms']:>9}"
              f"{row['p95Ms']:>9}{row['p99Ms']:>9}{row['maxMs']:>9}")
    print(json.dumps(report["totals"]))


# ---- SSE mode -----------------------------------------------------------------------------------------------

STREAM_OPEN_SECONDS = 15
DELIVERY_SECONDS = 10


class LiveStream(threading.Thread):
    """One GET /event/stream connection; records when each revision number first arrived."""

    def __init__(self, base, ticket, number):
        super().__init__(daemon=True)
        parsed = urllib.parse.urlparse(base)
        self.host, self.port, self.prefix = parsed.hostname, parsed.port, parsed.path.rstrip("/")
        self.ticket, self.number = ticket, number
        self.connected = threading.Event()
        self.lock = threading.Lock()
        self.received = {}
        self.error = None
        self.closing = False
        self.raw_socket = None

    def run(self):
        connection = http.client.HTTPConnection(self.host, self.port, timeout=120)
        try:
            connection.request("GET", f"{self.prefix}/event/stream?ticket={urllib.parse.quote(self.ticket)}",
                               headers={"Accept": "text/event-stream"})
            # getresponse() hands the socket to the response, so keep it for close().
            self.raw_socket = connection.sock
            response = connection.getresponse()
            if response.status != 200:
                self.error = f"stream {self.number}: HTTP {response.status}"
                return
            name = "message"
            while True:
                line = response.readline()
                if not line:
                    if not self.closing:
                        self.error = f"stream {self.number}: closed by the server"
                    return
                line = line.decode("utf-8").rstrip("\n")
                if line.startswith(":"):
                    self.connected.set()
                elif line.startswith("event:"):
                    name = line[6:].strip()
                elif line.startswith("data:") and name == "revision":
                    revision = json.loads(line[5:])["revision"]
                    with self.lock:
                        self.received.setdefault(revision, time.perf_counter())
                elif line == "":
                    name = "message"
        # close() from the main thread can surface as AttributeError inside http.client's chunked reader.
        except (OSError, http.client.HTTPException, ValueError, AttributeError) as error:
            if not self.closing:
                self.error = f"stream {self.number}: {type(error).__name__} {error}"
        finally:
            self.connected.set()
            connection.close()

    def first_at_least(self, revision):
        with self.lock:
            times = [at for number, at in self.received.items() if number >= revision]
        return min(times) if times else None

    def close(self):
        """shutdown() wakes the reader blocked in readline(), which then closes the connection itself."""
        self.closing = True
        if self.raw_socket is not None:
            try:
                self.raw_socket.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass


def java_memory():
    """Heap and live threads of the rehearsal backend JVM (jcmd from the JDK) plus its Windows working set."""
    record = json.loads((ROOT / ".local/event-rehearsal.json").read_text(encoding="utf-8-sig"))
    pid = record.get("backend")
    result = {"pid": pid}
    try:
        heap = subprocess.run(["jcmd", str(pid), "GC.heap_info"], capture_output=True, text=True, timeout=20).stdout
        match = re.search(r"total reserved (\d+)K, committed (\d+)K, used (\d+)K", heap)
        if match:
            result.update(heapCommittedMb=round(int(match[2]) / 1024, 1), heapUsedMb=round(int(match[3]) / 1024, 1))
        counters = subprocess.run(["jcmd", str(pid), "PerfCounter.print"], capture_output=True, text=True,
                                  timeout=20).stdout
        threads = re.search(r"java\.threads\.live=(\d+)", counters)
        if threads:
            result["liveThreads"] = int(threads[1])
        listing = subprocess.run(["tasklist", "/FI", f"PID eq {pid}", "/FO", "CSV", "/NH"], capture_output=True,
                                 text=True, timeout=20).stdout
        working_set = re.search(r'"([\d,.]+) K"', listing)
        if working_set:
            result["workingSetMb"] = round(int(re.sub(r"[,.]", "", working_set[1])) / 1024, 1)
    except (OSError, subprocess.SubprocessError) as error:
        result["error"] = str(error)
    return result


def latency_summary(values):
    """A negative value means the event arrived before the client finished reading the action's response."""
    if not values:
        return {}
    values = sorted(values)
    return {"count": len(values), "p50": round(percentile(values, .50), 1), "p95": round(percentile(values, .95), 1),
            "max": round(values[-1], 1), "min": round(values[0], 1)}


def open_streams(api, tokens, count):
    """Tickets are spread round-robin over the accounts (30 tickets per account per minute)."""
    streams = []
    for number in range(count):
        ticket = api.setup("POST", "/event/stream-ticket", tokens[number % len(tokens)], {})["ticket"]
        streams.append(LiveStream(api.base, ticket, number))
    for stream in streams:
        stream.start()
    for stream in streams:
        stream.connected.wait(STREAM_OPEN_SECONDS)
    return streams


def announce(api, admin, count, gap_ms):
    """Alternating pause/resume of the running round timer: every action bumps the revision once."""
    actions = []
    paths = ["/event/admin/round/timer/pause", "/event/admin/round/timer/resume"]
    for index in range(count):
        path = paths[index % 2]
        sent = time.perf_counter()
        status, state, _ = api.call("POST", path, admin)
        answered = time.perf_counter()
        actions.append({"path": path, "status": status, "sent": sent, "answered": answered,
                        "revision": (state or {}).get("revision")})
        time.sleep(gap_ms / 1000)
    return actions


def sse_run(args, api, config):
    admin, managers, teams = sign_in_everyone(api, config)
    set_clue_keys(api, managers)
    prepare_round_one(api, admin)
    tokens = [admin, *managers, *teams]
    memory_before = java_memory()
    streams = open_streams(api, tokens, args.streams)
    open_errors = [stream.error for stream in streams if stream.error]
    memory_open = java_memory()
    print(f"{len(streams) - len(open_errors)} of {len(streams)} streams open; {args.actions} admin actions...",
          flush=True)
    actions = announce(api, admin, args.actions, args.gap_ms)
    last = max(action["revision"] or 0 for action in actions)
    deadline = time.monotonic() + DELIVERY_SECONDS
    while time.monotonic() < deadline and any(s.first_at_least(last) is None for s in streams if not s.error):
        time.sleep(0.05)
    memory_after = java_memory()

    after_response, after_request, missing = [], [], 0
    for action in actions:
        if action["status"] != 200 or action["revision"] is None:
            continue
        for stream in streams:
            arrived = stream.first_at_least(action["revision"])
            if arrived is None:
                missing += 1
                continue
            after_response.append((arrived - action["answered"]) * 1000)
            after_request.append((arrived - action["sent"]) * 1000)
    stream_errors = [stream.error for stream in streams if stream.error]
    for stream in streams:
        stream.close()
    for stream in streams:
        stream.join(5)
    action_errors = [f"{action['path']} -> {action['status']}" for action in actions if action["status"] != 200]
    report = {
        "testedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "environment": "Local Windows, PostgreSQL devfest_test, API 8082. Synthetic SSE clients; no WAN latency.",
        "baseUrl": args.base_url, "mode": "sse", "streams": args.streams, "accounts": len(tokens),
        "actions": args.actions, "gapMs": args.gap_ms, "lastRevision": last,
        "deliveries": len(after_response), "expectedDeliveries": len(streams) * len(actions),
        "missingDeliveries": missing,
        "latencyAfterResponseMs": latency_summary(after_response),
        "latencyAfterRequestStartMs": latency_summary(after_request),
        "adminActionMs": latency_summary([(action["answered"] - action["sent"]) * 1000 for action in actions]),
        "errors": stream_errors + action_errors,
        "javaMemory": {"before": memory_before, "streamsOpen": memory_open, "afterActions": memory_after},
    }
    output = ROOT / f"docs/event-load-sse-{args.streams}.json"
    output.write_text(json.dumps(report, indent=2), encoding="utf-8")
    summary_keys = ("streams", "deliveries", "expectedDeliveries", "missingDeliveries", "latencyAfterResponseMs",
                    "latencyAfterRequestStartMs", "adminActionMs", "errors", "javaMemory")
    print(json.dumps({key: report[key] for key in summary_keys}, indent=2))
    if report["errors"] or missing:
        raise SystemExit(f"SSE load check failed; see {output}")
    print(f"SSE load check passed; report in {output}")


# ---- Quiz mode (AI room activity) ---------------------------------------------------------------------------

QUIZ_SECTIONS = (("EASY", 20, 1), ("MEDIUM", 15, 2), ("ADVANCED", 6, 3), ("LANDMARK", 3, 3), ("RECENT", 1, 3))
QUIZ_REPAIRS = (
    ("Fix the training loop", 7, ["for batch in loader:", "    optimizer.zero_grad()", "    loss = model(batch)",
                                  "    loss.backward()"]),
    ("Fix the data split", 7, ["rows = load_rows()", "random.shuffle(rows)", "cut = int(len(rows) * 0.8)",
                               "train = rows[:cut]", "test = rows[cut:]"]),
    ("Fix the precision metric", 6, ["tp = 0", "for y, p in pairs:", "    if y == p == 1:", "        tp += 1",
                                     "precision = tp / max(1, predicted)", "print(precision)"]),
)
RESULTS_POLL_SECONDS = 5
# Random answers land around this total, so some teams pass and some do not.
QUIZ_PASS_SCORE = 30
DUPLICATE_PROBE_SHARE = 0.05


def quiz_document():
    """45 questions (20/15/6/3/1, 80 points) and 3 repairs (20 points), the planned 100."""
    questions = []
    for section, count, points in QUIZ_SECTIONS:
        for number in range(1, count + 1):
            tag = f"{section.lower()}-{number}"
            questions.append({"section": section, "prompt": f"Load question {tag}?",
                              "options": [f"A {tag}", f"B {tag}", f"C {tag}", f"D {tag}"],
                              "correctOption": number % 4, "points": points, "explanation": ""})
    repairs = [{"title": title, "instructions": "Order the lines.", "points": points, "snippets": snippets}
               for title, points, snippets in QUIZ_REPAIRS]
    return {"format": 1, "mcqMinutes": 14, "passScore": QUIZ_PASS_SCORE, "questions": questions, "repairs": repairs}


class QuizLedger:
    """Expected scores per team (from the organizer's answer key) and proof that retries replay receipts."""

    def __init__(self):
        self.lock = threading.Lock()
        self.expected = {}
        self.retries = self.retry_mismatches = 0
        self.duplicate_probes = self.duplicates_refused = 0
        self.outcomes = {}

    def add(self, team, points):
        with self.lock:
            self.expected[team] = round(self.expected.get(team, 0) + points, 2)

    def replay(self, first, second):
        with self.lock:
            self.retries += 1
            if first != second:
                self.retry_mismatches += 1

    def probe(self, refused):
        with self.lock:
            self.duplicate_probes += 1
            if refused:
                self.duplicates_refused += 1


class QuizTeamClient(Client):
    """One team's browser: answers every question, finishes the MCQs and submits each repair; the last one records
    its result, which it then reads. After each save it fetches the activity again, like the page does; it also
    follows the revision like every screen."""

    def __init__(self, run, token, team, room_id, key, gap):
        super().__init__(run, token, "/event/team/board")
        self.team, self.room_id, self.answer_key, self.gap = team, room_id, key, gap
        self.activity_path = f"/event/team/rooms/{room_id}/activity"
        self.step = "answer"
        self.queue = None

    def first_action(self):
        return self.run_state.started + random.uniform(1, 5)

    def next_time(self):
        return time.monotonic() + random.uniform(self.gap * 0.6, self.gap * 1.4)

    def load_activity(self):
        status, data = self.request("GET", self.activity_path)
        return data if status == 200 else None

    def post(self, path, body, key):
        status, data = self.request("POST", path, body, key)
        if status == 200 and random.random() < RETRY_SHARE:
            again_status, again = self.request("POST", path, body, key)
            if again_status == 200:
                self.run_state.quiz.replay(data, again)
        return status, data

    def act(self):
        activity = self.load_activity()
        if activity is None:
            return self.next_time()
        if self.step == "answer":
            return self.answer(activity)
        if self.step == "repair":
            return self.repair(activity)
        if self.step == "result":
            self.result(activity)
        return math.inf

    def answer(self, activity):
        open_questions = [q for q in activity["questions"] if q["selectedOptionId"] is None]
        if activity["stage"] != "MCQ" or not open_questions:
            self.request("POST", f"/event/team/rooms/{self.room_id}/activity/finish-mcq")
            self.step = "repair"
            return self.next_time()
        question = open_questions[0]
        option = random.choice(question["options"])
        correct_text, points = self.answer_key["questions"][question["prompt"]]
        status, _ = self.post(f"{self.activity_path}/answers", {"questionId": question["id"], "optionId": option["id"]},
                              str(uuid.uuid4()))
        if status == 200 and option["text"] == correct_text:
            self.run_state.quiz.add(self.team, points)
        if status == 200 and random.random() < DUPLICATE_PROBE_SHARE:
            other = {"questionId": question["id"], "optionId": question["options"][0]["id"]}
            probe_status, _ = self.request("POST", f"{self.activity_path}/answers", other, str(uuid.uuid4()),
                                           conflict_expected=True)
            self.run_state.quiz.probe(probe_status == 409)
        return self.next_time()

    def repair(self, activity):
        pending = [r for r in activity["repairs"] if not r["submitted"]]
        if not pending:
            self.step = "result"
            return self.next_time()
        repair = pending[0]
        order = [s["id"] for s in repair["snippets"]]
        random.shuffle(order)
        code = {s["id"]: s["code"] for s in repair["snippets"]}
        correct, points = self.answer_key["repairs"][repair["title"]]
        status, _ = self.post(f"{self.activity_path}/repairs/{repair['id']}", {"snippetIds": order}, str(uuid.uuid4()))
        if status == 200:
            in_place = sum(1 for i, snippet in enumerate(order) if code[snippet] == correct[i])
            self.run_state.quiz.add(self.team, round(points * in_place / len(order) + 1e-9, 2))
        return self.next_time()

    def result(self, activity):
        """The team sees only whether its clue was unlocked (and the clue), never a score."""
        result = activity.get("result")
        if not result:
            self.run_state.metrics.error(f"team {self.team}: no result after its last repair")
            return
        leaked = [word for word in ("total", "points", "passScore", "score") if word in json.dumps(activity)]
        if leaked:
            self.run_state.metrics.error(f"team {self.team}: the activity names {leaked}")
        with self.run_state.quiz.lock:
            self.run_state.quiz.outcomes[self.team] = (result["clueUnlocked"], result.get("clue"))


class ResultsClient(Client):
    """The AI manager with the results page open: a fresh GET every 5 s (answers do not change the revision)."""

    def __init__(self, run, token, room_id):
        super().__init__(run, token, f"/event/rooms/{room_id}/board")
        self.results_path = f"/event/rooms/{room_id}/quiz/results"

    def first_action(self):
        return self.run_state.started + RESULTS_POLL_SECONDS

    def act(self):
        self.request("GET", self.results_path)
        return time.monotonic() + RESULTS_POLL_SECONDS


def quiz_run(args, api, config):
    admin, managers, teams = sign_in_everyone(api, config)
    set_clue_keys(api, managers)
    ai_manager = managers[list(config["managers"]).index("AI")]
    ai_room = api.setup("GET", "/event/home", ai_manager)["roomId"]
    document = quiz_document()
    api.setup("PUT", f"/event/admin/rooms/{ai_room}/activity", admin, {"activity": "AI_QUIZ"})
    api.setup("POST", f"/event/rooms/{ai_room}/quiz/import", ai_manager, document)
    answer_key = {
        "questions": {q["prompt"]: (q["options"][q["correctOption"]], q["points"]) for q in document["questions"]},
        "repairs": {r["title"]: (r["snippets"], r["points"]) for r in document["repairs"]},
    }
    api.setup("POST", "/event/admin/round", admin, {"round": 1})
    organization = api.setup("GET", "/event/admin/organization", admin)
    team_ids = {team["accountEmail"]: team["id"] for team in organization["teams"]}
    for email in config["members"]:
        team = next(t for t in organization["teams"] if t["accountEmail"] == email)
        if team["groupCode"] != "A":
            api.setup("PUT", "/event/admin/overrides/room-exception", admin,
                      {"teamId": team_ids[email], "roomId": ai_room, "reason": "Load check: every team in the AI room"})
    api.setup("POST", f"/event/admin/rooms/{ai_room}/open", admin)
    api.setup("POST", "/event/admin/round/timer/start", admin)

    run = LoadRun(api)
    run.quiz = QuizLedger()
    # 50 writes per team spread over about 80% of the run (each followed by a GET of the activity).
    gap = args.seconds * 0.8 / 52
    clients = [Client(run, admin, "/event/admin/board"), ResultsClient(run, ai_manager, ai_room)]
    clients += [QuizTeamClient(run, token, team, ai_room, answer_key, gap) for team, token in enumerate(teams, 1)]
    print(f"Running {len(teams)} teams in the AI room for up to {args.seconds} s (a write every ~{gap:.1f} s)...",
          flush=True)
    run.begin(args.seconds)
    for client in clients:
        client.start()
    for client in clients:
        client.join()
    elapsed = time.monotonic() - run.started

    results = api.setup("GET", f"/event/rooms/{ai_room}/quiz/results", ai_manager)
    by_name = {row["teamName"]: row for row in results["teams"]}
    names = {t["id"]: t["name"] for t in organization["teams"]}
    cross_check = []
    for team, email in enumerate(config["members"], 1):
        row = by_name.get(names[team_ids[email]], {})
        expected = run.quiz.expected.get(team, 0)
        ok = row.get("answered") == 45 and row.get("repairsSubmitted") == 3 and abs(row.get("total", -1) - expected) < 0.005
        if not ok:
            run.metrics.error(f"team {team}: results {row.get('answered')}+{row.get('repairsSubmitted')} "
                              f"total {row.get('total')} expected 45+3 total {expected}")
        cross_check.append({"team": team, "answered": row.get("answered"), "repairs": row.get("repairsSubmitted"),
                            "total": row.get("total"), "expectedTotal": expected, "ok": ok})
    quiz = run.quiz
    evaluated = passed = 0
    for check in cross_check:
        team = check["team"]
        row = by_name.get(names[team_ids[config["members"][team - 1]]], {})
        should_pass = check["expectedTotal"] >= QUIZ_PASS_SCORE - 1e-9
        unlocked, clue = quiz.outcomes.get(team, (None, None))
        status = row.get("status")
        evaluated += status in ("PASSED", "NOT_PASSED")
        passed += status == "PASSED"
        good = (status == ("PASSED" if should_pass else "NOT_PASSED") and unlocked == should_pass
                and row.get("clueGranted") == should_pass and (clue == PRACTICE_KEYS["AI"]) == should_pass)
        check.update({"status": status, "teamSawClue": unlocked, "clueGranted": row.get("clueGranted"), "outcomeOk": good})
        if not good:
            run.metrics.error(f"team {team}: status {status}, saw clue {unlocked}, granted {row.get('clueGranted')}, "
                              f"expected pass {should_pass}")
    if evaluated != len(teams):
        run.metrics.error(f"{evaluated} of {len(teams)} teams were evaluated")
    endpoints = endpoint_summary(run.metrics.latencies)
    worst_p95 = max(row["p95Ms"] for row in endpoints.values())
    report = {
        "testedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "environment": "Local Windows, PostgreSQL devfest_test, API 8082. Synthetic AI room traffic; no WAN latency.",
        "baseUrl": args.base_url, "mode": "quiz", "seconds": args.seconds, "elapsedSeconds": round(elapsed, 1),
        "teams": len(teams), "questions": 45, "repairs": 3,
        "endpoints": endpoints,
        "totals": {"requests": sum(e["requests"] for e in endpoints.values()), "errors": len(run.metrics.errors),
                   "worstP95Ms": worst_p95, "retries": quiz.retries, "retryMismatches": quiz.retry_mismatches,
                   "duplicateProbes": quiz.duplicate_probes, "duplicatesRefused": quiz.duplicates_refused,
                   "passScore": QUIZ_PASS_SCORE, "evaluations": evaluated, "passed": passed,
                   "notPassed": evaluated - passed},
        "resultsCrossCheck": cross_check,
        "errorSamples": run.metrics.errors[:50],
    }
    output = ROOT / "docs/event-load-quiz.json"
    output.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print_summary(report)
    totals = report["totals"]
    if (totals["errors"] or totals["retryMismatches"] or totals["duplicatesRefused"] != totals["duplicateProbes"]
            or worst_p95 >= 300):
        raise SystemExit(f"Quiz load check failed; see {output}")
    print(f"Quiz load check passed; report in {output}")


# ---- Linux room mode ----------------------------------------------------------------------------------------

LINUX_UNPACKED_BYTES = 20 * 1024 * 1024 - 1024
LINUX_KEYS = {"path": "/srv/rami/uploads", "username": "tech9", "fileSize": "1337c", "pattern": "*.bak",
              "encryptedMessage": "KHOOR ZRUOG"}
LINUX_SAVES = 5
DASHBOARD_POLL_SECONDS = 5
# The last teams only save: the round's end submits them (rules 18.7).
LINUX_SAVE_ONLY = 2


def heavy_package():
    """A ZIP holding one incompressible file of just under 20 MB unpacked, plus a manifest (the heaviest package)."""
    import io
    import os
    import zipfile
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_STORED) as archive:
        archive.writestr("devfest-manifest.csv", "path,owner,group,mode\n/srv/heavy/payload.bin,rami,rami,0644\n")
        archive.writestr("srv/heavy/payload.bin", os.urandom(LINUX_UNPACKED_BYTES))
    return buffer.getvalue()


def upload_package(api, path, token, data):
    boundary = "----devfest" + uuid.uuid4().hex
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"heavy.zip\"\r\n"
            "Content-Type: application/zip\r\n\r\n").encode() + data + f"\r\n--{boundary}--\r\n".encode()
    request = urllib.request.Request(api.base + path, data=body, method="PUT", headers={
        "Authorization": "Bearer " + token, "Content-Type": f"multipart/form-data; boundary={boundary}"})
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            return json.loads(response.read())
    except urllib.error.HTTPError as error:
        raise SystemExit(f"Setup failed: upload -> {error.code} {error.read()[:300]}")


def raw_get(api, path, token, headers=None):
    """GET that reads the whole body without parsing it: (status, bytes read, elapsed ms, ETag). An absolute URL
    (the site's static files) is fetched without the API's token."""
    if path.startswith("http"):
        request = urllib.request.Request(path, headers=headers or {})
    else:
        request = urllib.request.Request(api.base + path, headers={"Authorization": "Bearer " + token, **(headers or {})})
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            size = 0
            while chunk := response.read(1 << 20):
                size += len(chunk)
            return response.status, size, (time.perf_counter() - started) * 1000, response.headers.get("ETag")
    except urllib.error.HTTPError as error:
        etag = error.headers.get("ETag")
        error.read()
        return error.code, 0, (time.perf_counter() - started) * 1000, etag
    except (urllib.error.URLError, TimeoutError, ConnectionError):
        return 0, 0, (time.perf_counter() - started) * 1000, None


class LinuxLedger:
    def __init__(self):
        self.lock = threading.Lock()
        self.expected = {}
        self.etags = set()
        self.conditional_hits = 0
        self.retries = self.retry_mismatches = 0
        self.downloads = []
        self.tools = []

    def replay(self, first, second):
        with self.lock:
            self.retries += 1
            if first != second:
                self.retry_mismatches += 1


class LinuxTeamClient(Client):
    """One team's page and terminal: starts the terminal (files info, the 20 MB package, then a conditional restart
    request that must answer 304), saves its form five times, submits it once (the last teams only save, so the
    round's end submits them) and reads its form after every write like the page does."""

    def __init__(self, run, token, team, room_id, gap, submits, tools_url):
        super().__init__(run, token, "/event/team/board")
        self.tools_url = tools_url
        self.team, self.room_id, self.gap, self.submits = team, room_id, gap, submits
        self.form_path = f"/event/team/rooms/{room_id}/terminal-form"
        self.bundle_path = f"/event/team/rooms/{room_id}/terminal-bundle"
        self.rng = random.Random(team)
        self.answers = self.draw()
        self.steps = [("files", None), ("download", None), ("restart", None)]
        self.steps += [("save", self.partial(i)) for i in range(LINUX_SAVES - 1)] + [("save", self.answers)]
        if submits:
            self.steps.append(("submit", self.answers))
        run.linux.expected[team] = (self.expected, self.clue_ok)

    def draw(self):
        right = {"path": "/srv/rami/uploads/", "username": "tech9", "fileSize": "1337C", "pattern": "\"*.bak\"",
                 "encryptedMessage": "KHOOR  ZRUOG", "finalClue": "west tower"}
        wrong = {"path": "/tmp", "username": "nobody", "fileSize": "1c", "pattern": "*.x",
                 "encryptedMessage": "NOPE", "finalClue": "WRONG"}
        chances = {"path": .7, "username": .7, "fileSize": .7, "pattern": .7, "encryptedMessage": .8, "finalClue": .7}
        picked = {k: (right if self.rng.random() < chances[k] else wrong)[k] for k in chances}
        points = sum(3 for k in ("path", "username", "fileSize", "pattern") if picked[k] == right[k])
        message_ok, clue_ok = picked["encryptedMessage"] == right["encryptedMessage"], picked["finalClue"] == right["finalClue"]
        self.expected = points + (3 if message_ok and clue_ok else 0)
        self.clue_ok = clue_ok
        return picked

    def first_action(self):
        return self.run_state.started + random.uniform(0, 3)

    def next_time(self):
        return time.monotonic() + random.uniform(self.gap * 0.6, self.gap * 1.4)

    def partial(self, step_index):
        """A growing form: the first saves hold only some of the fields (blanks are allowed)."""
        keys = list(self.answers)
        keep = keys[: 1 + step_index * 2]
        return {k: (self.answers[k] if k in keep else "") for k in keys}

    def act(self):
        if not self.steps:
            return math.inf
        step, values = self.steps.pop(0)
        if step == "files":
            self.request("GET", f"/event/team/rooms/{self.room_id}/terminal-files")
        elif step == "download":
            self.download({})
            self.download_tools()
        elif step == "restart":
            self.download({"If-None-Match": next(iter(self.run_state.linux.etags), '"none"')}, conditional=True)
        else:
            self.write(step, values)
        return self.next_time() if self.steps else math.inf

    def download_tools(self):
        """The terminal's extra tools (a static file of the site), downloaded with the package; excluded from p95."""
        status, size, elapsed, _ = raw_get(self.run_state.api, self.tools_url, self.token)
        self.run_state.metrics.record("GET download tools", "/terminal/devfest-tools.tgz", status, elapsed)
        ledger = self.run_state.linux
        with ledger.lock:
            if status == 200 and size > 1_000_000:
                ledger.tools.append({"team": self.team, "bytes": size, "ms": round(elapsed, 1)})
            else:
                self.run_state.metrics.error(f"team {self.team}: tools download {status}, {size} bytes")

    def download(self, headers, conditional=False):
        status, size, elapsed, etag = raw_get(self.run_state.api, self.bundle_path, self.token, headers)
        label = "GET conditional" if conditional else "GET download"
        self.run_state.metrics.record(label, self.bundle_path, status if status != 304 else 200, elapsed)
        ledger = self.run_state.linux
        with ledger.lock:
            if conditional and status == 304:
                ledger.conditional_hits += 1
            elif conditional:
                self.run_state.metrics.error(f"team {self.team}: restart answered {status}, not 304")
            elif status == 200 and size > LINUX_UNPACKED_BYTES * 0.9:
                ledger.etags.add(etag)
                ledger.downloads.append({"team": self.team, "bytes": size, "ms": round(elapsed, 1)})
            elif not conditional:
                self.run_state.metrics.error(f"team {self.team}: bundle download {status}, {size} bytes")

    def write(self, action, values):
        key = str(uuid.uuid4())
        status, data = self.request("POST", f"{self.form_path}/{action}", values, key)
        if status == 200 and random.random() < RETRY_SHARE:
            again_status, again = self.request("POST", f"{self.form_path}/{action}", values, key)
            if again_status == 200:
                self.run_state.linux.replay(data, again)
        self.request("GET", self.form_path)


class DashboardClient(Client):
    """The Linux manager with the organizer dashboard open: a fresh GET every 5 s."""

    def __init__(self, run, token, room_id):
        super().__init__(run, token, f"/event/rooms/{room_id}/board")
        self.dashboard_path = f"/event/rooms/{room_id}/terminal-dashboard"

    def first_action(self):
        return self.run_state.started + 1

    def act(self):
        self.request("GET", self.dashboard_path)
        return time.monotonic() + DASHBOARD_POLL_SECONDS


class HeapSampler(threading.Thread):
    """Samples the backend JVM's heap while the teams download the package."""

    def __init__(self, seconds):
        super().__init__(daemon=True)
        self.seconds, self.samples = seconds, []

    def run(self):
        end = time.monotonic() + self.seconds
        while time.monotonic() < end:
            self.samples.append(java_memory())
            time.sleep(1)


def linux_run(args, api, config):
    admin, managers, teams = sign_in_everyone(api, config)
    set_clue_keys(api, managers)
    linux_index = list(config["managers"]).index("LINUX")
    manager = managers[linux_index]
    room = api.setup("GET", "/event/home", manager)["roomId"]
    api.setup("PUT", "/event/admin/rooms/{}/activity".format(
        next(r["id"] for r in api.setup("GET", "/event/admin/board", admin)["rooms"] if r["code"] == "AI")),
        admin, {"activity": None})
    api.setup("PUT", f"/event/admin/rooms/{room}/activity", admin, {"activity": "LINUX_TERMINAL"})
    print("Uploading a 20 MB-unpacked package...", flush=True)
    upload_package(api, f"/event/rooms/{room}/terminal-bundle", manager, heavy_package())
    api.setup("PUT", f"/event/rooms/{room}/terminal-dashboard/keys", manager, LINUX_KEYS)
    api.setup("POST", "/event/admin/round", admin, {"round": 1})
    organization = api.setup("GET", "/event/admin/organization", admin)
    team_ids = {team["accountEmail"]: team["id"] for team in organization["teams"]}
    for email in config["members"]:
        team = next(t for t in organization["teams"] if t["accountEmail"] == email)
        if team["groupCode"] != "C":
            api.setup("PUT", "/event/admin/overrides/room-exception", admin,
                      {"teamId": team_ids[email], "roomId": room, "reason": "Load check: every team in the Linux room"})
    api.setup("POST", f"/event/admin/rooms/{room}/open", admin)
    api.setup("POST", "/event/admin/round/timer/start", admin)

    run = LoadRun(api)
    run.linux = LinuxLedger()
    gap = args.seconds * 0.55 / (LINUX_SAVES + 1)
    clients = [Client(run, admin, "/event/admin/board"), DashboardClient(run, manager, room)]
    version = re.search(r'TOOLS_VERSION = "([0-9a-f]+)"',
                        (ROOT / "frontend/src/app/features/event/terminal/tools-version.ts").read_text(encoding="utf-8")).group(1)
    tools_url = f"{args.ui_url.rstrip('/')}/terminal/devfest-tools.tgz?v={version}"
    status, _, _, _ = raw_get(api, tools_url, "")
    if status != 200:
        raise SystemExit(f"The site's tools archive is not served at {tools_url} (HTTP {status}); start the UI first.")
    clients += [LinuxTeamClient(run, token, number, room, gap, number <= len(teams) - LINUX_SAVE_ONLY, tools_url)
                for number, token in enumerate(teams, 1)]
    before = java_memory()
    sampler = HeapSampler(min(30, args.seconds))
    print(f"Running {len(teams)} teams in the Linux room for up to {args.seconds} s...", flush=True)
    run.begin(args.seconds)
    sampler.start()
    for client in clients:
        client.start()
    for client in clients:
        client.join()
    sampler.join()
    elapsed = time.monotonic() - run.started
    after = java_memory()

    # The round's end submits the teams that only saved; then the dashboard and the manager board must agree.
    api.setup("POST", "/event/admin/round/timer/end", admin)
    dashboard_status, dashboard, dashboard_ms = api.call("GET", f"/event/rooms/{room}/terminal-dashboard", manager)
    run.metrics.record("GET", f"/event/rooms/{room}/terminal-dashboard", dashboard_status, dashboard_ms)
    csv_status, _, csv_ms, _ = raw_get(api, f"/event/rooms/{room}/terminal-dashboard.csv", manager)
    run.metrics.record("GET", f"/event/rooms/{room}/terminal-dashboard.csv", csv_status, csv_ms)
    board = api.setup("GET", f"/event/rooms/{room}/board", manager)
    counts = {row["teamName"]: row["submissionCount"] for row in board["teams"]}
    names = {t["id"]: t["name"] for t in organization["teams"]}
    by_name = {row["teamName"]: row for row in dashboard["teams"]}
    cross_check, submitted = [], 0
    for number, email in enumerate(config["members"], 1):
        name = names[team_ids[email]]
        expected, clue_ok = run.linux.expected.get(number, (None, None))
        row = by_name.get(name, {})
        clue_status, board_data, _ = api.call("GET", "/event/team/board", teams[number - 1])
        collected = board_data.get("cluesCollected") if clue_status == 200 else None
        ok = (counts.get(name) == 1 and row.get("status") == "SUBMITTED" and row.get("total") == expected
              and collected == (1 if clue_ok else 0))
        submitted += row.get("status") == "SUBMITTED"
        if not ok:
            run.metrics.error(f"team {number}: submissions {counts.get(name)}, status {row.get('status')}, total "
                              f"{row.get('total')} (expected {expected}), clues {collected} (expected {int(bool(clue_ok))})")
        cross_check.append({"team": number, "submissions": counts.get(name), "status": row.get("status"),
                            "total": row.get("total"), "expectedTotal": expected, "cluesCollected": collected,
                            "cause": row.get("cause"), "ok": ok})
    if run.linux.conditional_hits != len(teams):
        run.metrics.error(f"{run.linux.conditional_hits} of {len(teams)} restarts answered 304")
    if len(run.linux.etags) != 1:
        run.metrics.error(f"teams saw {len(run.linux.etags)} different package digests")

    endpoints = endpoint_summary(run.metrics.latencies)
    rest = sorted(ms for endpoint, data in run.metrics.latencies.items() if not endpoint.startswith("GET download")
                  for ms in data["ms"])
    worst_rest_p95 = max(row["p95Ms"] for name, row in endpoints.items() if not name.startswith("GET download"))
    heap = [m.get("heapUsedMb") for m in sampler.samples if m.get("heapUsedMb") is not None]
    working = [m.get("workingSetMb") for m in sampler.samples if m.get("workingSetMb") is not None]
    report = {
        "testedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "environment": "Local Windows, PostgreSQL devfest_test, API 8082. Synthetic Linux room traffic; no WAN latency.",
        "baseUrl": args.base_url, "mode": "linux", "seconds": args.seconds, "elapsedSeconds": round(elapsed, 1),
        "teams": len(teams), "packageUnpackedBytes": LINUX_UNPACKED_BYTES,
        "endpoints": endpoints,
        "totals": {"requests": sum(e["requests"] for e in endpoints.values()), "errors": len(run.metrics.errors),
                   "p95ExcludingBundleDownloadMs": round(percentile(rest, .95), 1),
                   "worstEndpointP95ExcludingBundleDownloadMs": worst_rest_p95,
                   "submittedForms": submitted, "retries": run.linux.retries,
                   "retryMismatches": run.linux.retry_mismatches, "conditionalRestarts304": run.linux.conditional_hits},
        "bundleDownloads": {"count": len(run.linux.downloads), "downloads": run.linux.downloads,
                            "maxMs": max((d["ms"] for d in run.linux.downloads), default=None)},
        "toolsDownloads": {"count": len(run.linux.tools), "bytes": run.linux.tools[0]["bytes"] if run.linux.tools else None,
                           "maxMs": max((d["ms"] for d in run.linux.tools), default=None)},
        "serverHeap": {"before": before, "after": after, "samplesDuringDownloads": len(sampler.samples),
                       "maxHeapUsedMb": max(heap, default=None), "maxWorkingSetMb": max(working, default=None),
                       "samples": sampler.samples},
        "formsCrossCheck": cross_check,
        "errorSamples": run.metrics.errors[:50],
    }
    output = ROOT / "docs/event-load-linux.json"
    output.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print_summary(report)
    print(json.dumps({"toolsDownloads": report["toolsDownloads"],
                      "bundleDownloads": {k: v for k, v in report["bundleDownloads"].items() if k != "downloads"},
                      "serverHeap": {k: v for k, v in report["serverHeap"].items() if k != "samples"}}))
    totals = report["totals"]
    if (totals["errors"] or totals["retryMismatches"] or totals["submittedForms"] != len(teams)
            or totals["p95ExcludingBundleDownloadMs"] >= 300 or worst_rest_p95 >= 300):
        raise SystemExit(f"Linux load check failed; see {output}")
    print(f"Linux load check passed; report in {output}")



def main():
    args = parse_args()
    api = Api(args.base_url)
    config = json.loads((ROOT / ".local/event-rehearsal.json").read_text(encoding="utf-8-sig"))
    if args.mode == "sse":
        sse_run(args, api, config)
        return
    if args.mode == "quiz":
        quiz_run(args, api, config)
        return
    if args.mode == "linux":
        linux_run(args, api, config)
        return
    # Setup goes through Api.setup, which is never recorded in the measurements.
    admin, managers, teams = sign_in_everyone(api, config)
    set_clue_keys(api, managers)
    prepare_round_one(api, admin)
    run = LoadRun(api)
    clients = build_clients(run, admin, managers, teams, args.scale)
    print(f"Running {len(clients)} clients for {args.seconds} s (scale {args.scale}x)...", flush=True)
    run.begin(args.seconds)
    for client in clients:
        client.start()
    for client in clients:
        client.join()
    elapsed = time.monotonic() - run.started
    cross_check = cross_check_submissions(run, teams)
    report = build_report(args, run, clients, cross_check, elapsed)
    output = ROOT / f"docs/event-load-{args.scale}x.json"
    output.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print_summary(report)
    totals = report["totals"]
    if totals["errors"] or totals["retryMismatches"] or totals["duplicateReceiptIds"]:
        raise SystemExit(f"Event load check failed; see {output}")
    print(f"Event load check passed; report in {output}")


if __name__ == "__main__":
    main()
