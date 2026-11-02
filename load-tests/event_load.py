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
    parser.add_argument("--mode", choices=("mixed", "sse", "quiz"), default="mixed")
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
