"""Sustained synthetic load against the isolated event rehearsal (API 8082, devfest_test data only).

Mode "mixed" (default): simulates 16 or 48 team clients, 4 room managers and 1 admin polling the revision
endpoint, refetching their board when it changes, submitting room answers with idempotent retries and judging
concurrently. Results are written to docs/event-load-<scale>x.json.

Mode "sse": opens --streams live revision streams (GET /event/stream with single-use tickets, spread over the 21
seeded accounts), performs --actions admin timer actions and measures, per action and stream, the time from the
action's HTTP response to the matching "revision" event. Results are written to docs/event-load-sse-<N>.json.

Mode "sentinel": the AI mission room (SENTINEL) in one compressed round. The AI manager imports the shipped mission
file, the admin sends all 16 teams to the AI room (room exceptions for groups B-D) and starts round 1; every team runs
its experiments (stage 3 spends its whole budget and then probes a fifth run, which must be refused), submits its ten
stages in a random order, some with a full-marks answer and some with a worthless one, retries a share of its writes
with the same Idempotency-Key and probes locked stages. Teams 15 and 16 never submit their last stage: the round's end
evaluates them. The manager keeps the results page open (a GET every 5 s). Every team must be evaluated exactly once
with the outcome its own total predicts, no member response may carry points, scores or answers, and no endpoint may
reach a p95 of 300 ms. Results are written to docs/event-load-sentinel.json.

Mode "linux": the Linux room in one compressed round. The manager uploads a package of just under 20 MB unpacked and
sets the answer keys; the admin sends all 16 teams to the room. Every team starts its terminal (files info, the 20 MB
package download, then a restart whose If-None-Match must answer 304), saves its five-field form five times and
submits it once; the last two teams only save, so the round's end submits them. The manager keeps the organizer
dashboard open (a GET every 5 s). Every team must end with exactly one submission, the dashboard total its own
answers predict and its clue iff its final clue was right; the backend heap is sampled during the downloads. The
bundle download is measured on its own and excluded from the 300 ms p95 limit. Results go to docs/event-load-linux.json.

Mode "final": the 100-point scoring and the crypto final. The four room rounds first run through the API: 16 teams
collect their clues while the 4 managers type room scores and record hints (-2), cheating (-7.5), one cancelled hint
and one hint beyond the two allowed (refused with 409). The admin imports the content and opens the final, then all 16
teams start together: unlock (with wrong tries), the four layers, the one-time verdicts and the suspect, two teams
through the Recovery Challenge, a share of the writes retried with the same Idempotency-Key, repeats of the one-time
answers refused. The admin keeps the live final table and the scoreboard open (a GET every 5 s). Two teams never submit
a suspect: the admin's close finalises them. Every team must be finalised exactly once (the finish time of a team that
submitted does not move at the close, closing twice changes nothing), the final parts, the room scores with their
deductions and the totals must be the ones the plans predict, no member response may carry a verdict, deduction or
score, and no endpoint may reach a p95 of 300 ms. Results go to docs/event-load-final.json.
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
    parser.add_argument("--mode", choices=("mixed", "sse", "sentinel", "linux", "final"), default="mixed")
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
        body = {"status": self.STATUS_CYCLE[judgement["status"]], "note": "Synthetic load review.", "version": judgement["version"]}
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
            "clueKey": PRACTICE_KEYS[room["code"]], "version": room["version"]})


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


# ---- Sentinel mode (AI mission room) ---------------------------------------------------------------------

MISSION_FILE = ROOT / "docs/ai-room/sentinel-content.json"
RESULTS_POLL_SECONDS = 5
DUPLICATE_PROBE_SHARE = 0.1
SENTINEL_PASS_SCORE = 60
# A member response never carries one of these keys, at any depth.
SENTINEL_FORBIDDEN_KEYS = {"points", "maxPoints", "pointsEarned", "award", "awards", "score", "scores", "total",
                           "passScore", "passed", "correct", "correctOption", "answer", "expected", "role"}
# Per stage: a full-marks answer and an answer worth 0 (10 points per stage; the file's own scoring tables).
STAGE_ANSWERS = {
    1: ({"config": "iqr-1.5"}, {"config": "z-3"}),
    2: ({"choices": {"entry-gate": "USE", "entry-time": "USE", "vehicle-type": "USE", "previous-visits": "USE",
                     "plate-number": "DROP", "exit-camera-time": "DROP"}},
        {"choices": {"entry-gate": "DROP", "entry-time": "DROP", "vehicle-type": "DROP", "previous-visits": "DROP",
                     "plate-number": "USE", "exit-camera-time": "USE"}}),
    3: ({"config": "tree-5"}, {"config": "knn-1"}),
    4: ({"k1": "Car", "k5": "Van", "reliable": 5}, {"k1": "Van", "k5": "Car", "reliable": 1}),
    5: ({"tp": 6, "fp": 2, "fn": 4, "tn": 8, "precision": 0.75, "recall": 0.6, "metric": "Recall"},
        {"tp": 0, "fp": 0, "fn": 0, "tn": 0, "precision": 0, "recall": 0, "metric": "Accuracy"}),
    6: ({"size": 6, "x": 7.5, "y": 6.5}, {"size": 1, "x": 0, "y": 0}),
    7: ({"order": ["lib", "lab", "dor", "adm", "gar", "g3"], "path": ["lib", "lab", "adm", "gar", "g3"],
         "cost": 15}, {"order": ["lib"], "path": ["lib"], "cost": 0}),
    8: ({"threat": 0.00012, "normal": 0.0010125, "label": "Normal"}, {"threat": 1, "normal": 1, "label": "Threat"}),
    9: ({"conclusion": "insider-involved"}, {"conclusion": "cover-up"}),
    10: ({"config": "large-1"}, {"config": "small-1"}),
}
# Experiments before submitting: stage 3 spends its whole budget of 4 (then probes a fifth), stages 1 and 10 run two.
STAGE_RUNS = {1: ["iqr-1.5", "iqr-3"], 3: ["knn-1", "knn-5", "tree-2", "tree-5"], 10: ["small-1", "large-1"]}
# Teams that never submit stage 10: the round's end evaluates them.
SENTINEL_UNFINISHED_TEAMS = (15, 16)


def forbidden_keys(node, found=None, keys=None):
    found = set() if found is None else found
    keys = SENTINEL_FORBIDDEN_KEYS if keys is None else keys
    if isinstance(node, dict):
        for key, value in node.items():
            if key in keys:
                found.add(key)
            forbidden_keys(value, found, keys)
    elif isinstance(node, list):
        for item in node:
            forbidden_keys(item, found, keys)
    return found


class SentinelLedger:
    """Expected points per team, and proof that retries replay and that budgets and locks are refused."""

    def __init__(self):
        self.lock = threading.Lock()
        self.expected = {}
        self.saved = {}
        self.retries = self.retry_mismatches = 0
        self.probes = self.probes_refused = 0

    def saved_stage(self, team, points):
        with self.lock:
            self.expected[team] = self.expected.get(team, 0) + points
            self.saved[team] = self.saved.get(team, 0) + 1

    def replay(self, first, second):
        with self.lock:
            self.retries += 1
            if first != second:
                self.retry_mismatches += 1

    def probe(self, refused):
        with self.lock:
            self.probes += 1
            if refused:
                self.probes_refused += 1


class SentinelTeamClient(Client):
    """One team's browser: experiments and submissions in a random stage order, each followed by a fetch of the
    activity (like the page); 20% of writes are retried with the same key, some extra writes must be refused."""

    def __init__(self, run, token, team, room_id, gap):
        super().__init__(run, token, "/event/team/board")
        self.team, self.room_id, self.gap = team, room_id, gap
        self.activity_path = f"/event/team/rooms/{room_id}/activity"
        stages = list(range(1, 10 if team in SENTINEL_UNFINISHED_TEAMS else 11))
        goods = (team - 1) % 11
        good = set(random.sample(stages, min(goods, len(stages))))
        self.good = good
        random.shuffle(stages)
        self.plan = []
        for stage in stages:
            for config in STAGE_RUNS.get(stage, []):
                self.plan.append(("run", stage, config))
            if stage == 3:
                self.plan.append(("budget-probe", stage, "knn-15"))
            self.plan.append(("submit", stage, None))
        self.final_stage = stages[-1]

    def first_action(self):
        return self.run_state.started + random.uniform(1, 5)

    def next_time(self):
        return time.monotonic() + random.uniform(self.gap * 0.6, self.gap * 1.4)

    def post(self, path, body, key, retry=True):
        status, data = self.request("POST", path, body, key)
        if retry and status == 200 and random.random() < RETRY_SHARE:
            again_status, again = self.request("POST", path, body, key)
            if again_status == 200:
                self.run_state.sentinel.replay(data, again)
        return status, data

    def act(self):
        status, activity = self.request("GET", self.activity_path)
        if status == 200:
            leaked = forbidden_keys(activity)
            if leaked:
                self.run_state.metrics.error(f"team {self.team}: the activity carries {sorted(leaked)}")
        if not self.plan:
            return math.inf
        kind, stage, config = self.plan.pop(0)
        base = f"{self.activity_path}/stages/{stage}"
        if kind == "run":
            self.post(f"{base}/runs", {"config": config}, str(uuid.uuid4()))
        elif kind == "budget-probe":
            status, _ = self.request("POST", f"{base}/runs", {"config": config}, str(uuid.uuid4()), conflict_expected=True)
            self.run_state.sentinel.probe(status == 409)
        else:
            answer = STAGE_ANSWERS[stage][0 if stage in self.good else 1]
            status, _ = self.post(f"{base}/submit", answer, str(uuid.uuid4()))
            if status == 200:
                self.run_state.sentinel.saved_stage(self.team, 10 if stage in self.good else 0)
                if random.random() < DUPLICATE_PROBE_SHARE:
                    other = STAGE_ANSWERS[stage][1 if stage in self.good else 0]
                    status, _ = self.request("POST", f"{base}/submit", other, str(uuid.uuid4()), conflict_expected=True)
                    self.run_state.sentinel.probe(status == 409)
        return self.next_time() if self.plan else math.inf


class MissionResultsClient(Client):
    """The AI manager with the SENTINEL page open: a fresh results GET every 5 s."""

    def __init__(self, run, token, room_id):
        super().__init__(run, token, f"/event/rooms/{room_id}/board")
        self.results_path = f"/event/rooms/{room_id}/mission/results"

    def first_action(self):
        return self.run_state.started + RESULTS_POLL_SECONDS

    def act(self):
        self.request("GET", self.results_path)
        return time.monotonic() + RESULTS_POLL_SECONDS


def sentinel_run(args, api, config):
    admin, managers, teams = sign_in_everyone(api, config)
    set_clue_keys(api, managers)
    ai_manager = managers[list(config["managers"]).index("AI")]
    ai_room = api.setup("GET", "/event/home", ai_manager)["roomId"]
    api.setup("PUT", f"/event/admin/rooms/{ai_room}/activity", admin, {"activity": "AI_QUIZ"})
    api.setup("POST", f"/event/rooms/{ai_room}/mission/import", ai_manager, json.loads(MISSION_FILE.read_text(encoding="utf-8")))
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
    run.sentinel = SentinelLedger()
    # About 20 writes per team (each followed by a GET of the activity) spread over about 80% of the run.
    gap = args.seconds * 0.8 / 24
    clients = [Client(run, admin, "/event/admin/board"), MissionResultsClient(run, ai_manager, ai_room)]
    clients += [SentinelTeamClient(run, token, team, ai_room, gap) for team, token in enumerate(teams, 1)]
    print(f"Running {len(teams)} teams in the AI room for up to {args.seconds} s (a write every ~{gap:.1f} s)...",
          flush=True)
    run.begin(args.seconds)
    for client in clients:
        client.start()
    for client in clients:
        client.join()
    elapsed = time.monotonic() - run.started

    # The round's end evaluates the teams that did not submit their last stage (no scheduler).
    api.setup("POST", "/event/admin/round/timer/end", admin)
    results = api.setup("GET", f"/event/rooms/{ai_room}/mission/results", ai_manager)
    names = {t["id"]: t["name"] for t in organization["teams"]}
    by_name = {row["teamName"]: row for row in results["teams"]}
    sentinel = run.sentinel
    cross_check = []
    evaluated = passed = 0
    for team, email in enumerate(config["members"], 1):
        row = by_name.get(names[team_ids[email]], {})
        expected = sentinel.expected.get(team, 0)
        should_pass = expected >= SENTINEL_PASS_SCORE - 1e-9
        view = api.setup("GET", f"/event/team/rooms/{ai_room}/activity", teams[team - 1])
        result = view.get("result") or {}
        status = row.get("status")
        evaluated += status in ("PASSED", "NOT_PASSED")
        passed += status == "PASSED"
        leaked = forbidden_keys(view)
        ok = (row.get("stagesSaved") == sentinel.saved.get(team, 0) and abs(row.get("total", -1) - expected) < 0.005
              and status == ("PASSED" if should_pass else "NOT_PASSED") and row.get("clueGranted") == should_pass
              and row.get("evaluatedAt") is not None and result.get("clueUnlocked") == should_pass
              and (result.get("clue") == PRACTICE_KEYS["AI"]) == should_pass and not leaked)
        if not ok:
            run.metrics.error(f"team {team}: results {row.get('stagesSaved')} saved, total {row.get('total')}, "
                              f"status {status}, clue granted {row.get('clueGranted')}, saw clue "
                              f"{result.get('clueUnlocked')}; expected {sentinel.saved.get(team, 0)} saved, total "
                              f"{expected}, pass {should_pass}; leaked {sorted(leaked)}")
        cross_check.append({"team": team, "stagesSaved": row.get("stagesSaved"), "total": row.get("total"),
                            "expectedTotal": expected, "status": status, "clueGranted": row.get("clueGranted"),
                            "teamSawClue": result.get("clueUnlocked"), "ok": ok})
    if evaluated != len(teams):
        run.metrics.error(f"{evaluated} of {len(teams)} teams were evaluated")
    endpoints = endpoint_summary(run.metrics.latencies)
    worst_p95 = max(row["p95Ms"] for row in endpoints.values())
    report = {
        "testedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "environment": "Local Windows, PostgreSQL devfest_test, API 8082. Synthetic AI mission traffic; no WAN latency.",
        "baseUrl": args.base_url, "mode": "sentinel", "seconds": args.seconds, "elapsedSeconds": round(elapsed, 1),
        "teams": len(teams), "stages": 10,
        "endpoints": endpoints,
        "totals": {"requests": sum(e["requests"] for e in endpoints.values()), "errors": len(run.metrics.errors),
                   "worstP95Ms": worst_p95, "retries": sentinel.retries, "retryMismatches": sentinel.retry_mismatches,
                   "refusalProbes": sentinel.probes, "refusalsAnswered409": sentinel.probes_refused,
                   "passScore": SENTINEL_PASS_SCORE, "evaluations": evaluated, "passed": passed,
                   "notPassed": evaluated - passed},
        "resultsCrossCheck": cross_check,
        "errorSamples": run.metrics.errors[:50],
    }
    output = ROOT / "docs/event-load-sentinel.json"
    output.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print_summary(report)
    totals = report["totals"]
    if (totals["errors"] or totals["retryMismatches"] or totals["refusalsAnswered409"] != totals["refusalProbes"]
            or worst_p95 >= 300 or evaluated != len(teams)):
        raise SystemExit(f"SENTINEL load check failed; see {output}")
    print(f"SENTINEL load check passed; report in {output}")


# ---- Linux room mode ----------------------------------------------------------------------------------------

LINUX_UNPACKED_BYTES = 20 * 1024 * 1024 - 1024
LINUX_KEYS = {"path": "/srv/rami/uploads", "username": "tech9", "fileSize": "1337c", "pattern": "*.bak"}
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
                 "finalClue": "west tower"}
        wrong = {"path": "/tmp", "username": "nobody", "fileSize": "1c", "pattern": "*.x",
                 "finalClue": "WRONG"}
        chances = {"path": .7, "username": .7, "fileSize": .7, "pattern": .7, "finalClue": .7}
        picked = {k: (right if self.rng.random() < chances[k] else wrong)[k] for k in chances}
        points = sum(3 for k in ("path", "username", "fileSize", "pattern") if picked[k] == right[k])
        clue_ok = picked["finalClue"] == right["finalClue"]
        self.expected = points + (3 if clue_ok else 0)
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


# ---- Crypto final mode --------------------------------------------------------------------------------------

FINAL_CONTENT = ROOT / "docs/final/crypto-content.json"
FINAL_LAYER_ANSWERS = (52, 17, 8, 17)
FINAL_RECOVERY_ANSWER = "EIGHT BITS"
FINAL_RIGHT_SUSPECT, FINAL_WRONG_SUSPECT = 2, 1
ROOM_ORDER = ("AI", "PROBLEM_SOLVING", "LINUX", "HARDWARE")
# A member's final screens never carry one of these keys, at any depth (no verdict, deduction, score or plaintext).
FINAL_FORBIDDEN_KEYS = {"cheating", "deduction", "deductions", "score", "scores", "total", "points", "verdictCorrect",
                        "suspectCorrect", "yourVerdictCorrect", "correctSuspect", "plaintext", "privateExponent",
                        "acceptedAnswers", "answer", "hintDeduction"}
# Hints and cheating recorded by the managers while the rooms run: {(team, round)}.
FINAL_HINTS = {(t, 1) for t in range(1, 9)} | {(t, 2) for t in range(1, 5)}
FINAL_CHEATING = {(3, 2), (10, 2), (10, 3)}
FINAL_CANCELLED_HINTS = {(5, 1)}
# Team 1 already used its two hints: a third one must be refused with 409.
FINAL_REFUSED_HINTS = {(1, 3)}


def final_plan(team):
    """What each of the 16 teams does in the final. Teams 12 and 16 never submit a suspect: the close finalises them."""
    plan = {"clues": 4, "wrongUnlock": 0, "wrongLayers": 0, "verdict3": "NO", "suspect": FINAL_RIGHT_SUSPECT,
            "stopAfterLayer": None}
    if team == 12:
        plan["suspect"] = None
    if team == 13:
        plan.update(suspect=FINAL_WRONG_SUSPECT, wrongLayers=2)
    if team == 14:
        plan.update(verdict3="YES", wrongUnlock=2)
    if team in (15, 16):
        plan["clues"] = 3
    if team == 16:
        plan.update(stopAfterLayer=2, suspect=None)
    return plan


def final_expected(plan):
    """The final score parts the plan must produce (as the API rounds them)."""
    layers = plan["stopAfterLayer"] or 4
    verdicts = 0 if layers < 4 else (plan["verdict3"] == "NO") + 1
    suspect = 10 if plan["suspect"] == FINAL_RIGHT_SUSPECT else 0
    time_points = 15 if plan["suspect"] is not None and layers == 4 else 0
    return {"layers": 2.5 * layers, "verdicts": 2.5 * verdicts, "suspect": suspect, "time": time_points,
            "total": 2.5 * layers + 2.5 * verdicts + suspect + time_points}


def typed_score(team, rnd):
    return 8 + (team + rnd) % 8


def hit(run, token, method, path, body=None, key=None, conflict_expected=False):
    status, data, elapsed = run.api.call(method, path, token, body, key)
    run.metrics.record(method, path, status, elapsed, conflict_expected)
    return status, data


class FinalLedger:
    def __init__(self):
        self.lock = threading.Lock()
        self.retries = self.retry_mismatches = 0
        self.refusals = self.refusals_ok = 0
        self.samples = []

    @staticmethod
    def same_outcome(first, second):
        """A replayed correct answer says "Accepted." and omits the screen; what it decided must be the same."""
        if isinstance(first, dict) and "accepted" in first and isinstance(second, dict):
            return all(first.get(key) == second.get(key) for key in ("accepted", "status", "waitSeconds"))
        return first == second

    def replay(self, first, second, label=""):
        with self.lock:
            self.retries += 1
            if not self.same_outcome(first, second):
                self.retry_mismatches += 1
                if len(self.samples) < 12:
                    self.samples.append({"retry": label, "first": first, "again": second})

    def refusal(self, ok, label="", status=None, data=None):
        with self.lock:
            self.refusals += 1
            self.refusals_ok += bool(ok)
            if not ok and len(self.samples) < 24:
                self.samples.append({"refusal": label, "status": status, "data": data})


def run_room_round(run, rnd, teams, managers, team_ids, room_ids, plans, expected_rooms):
    """One room round: every team submits its clue and every manager types scores and records deductions, at once."""

    def code_of(team):
        return ROOM_ORDER[(((team - 1) * 4) // 16 + rnd - 1) % 4]

    barrier = threading.Barrier(len(teams) + len(managers))

    def team_work(team):
        barrier.wait()
        if plans[team]["clues"] == 3 and rnd == 4:
            return
        code = code_of(team)
        key = str(uuid.uuid4())
        body = {"technicalOutput": "Synthetic load submission.", "clueAnswer": PRACTICE_KEYS[code]}
        path = f"/event/team/rooms/{room_ids[code]}/submissions"
        status, receipt = hit(run, teams[team - 1], "POST", path, body, key)
        if status != 200 or not receipt.get("clueCorrect"):
            run.metrics.error(f"team {team}: the {code} clue was not collected in round {rnd}: {status}")
            return
        if random.random() < RETRY_SHARE:
            again_status, again = hit(run, teams[team - 1], "POST", path, body, key)
            if again_status == 200:
                run.final.replay(receipt, again, path)

    def manager_work(code):
        token = managers[code]
        room = room_ids[code]
        barrier.wait()
        for team in range(1, len(teams) + 1):
            if code_of(team) != code:
                continue
            tid = team_ids[team]
            hit(run, token, "PUT", f"/event/rooms/{room}/manual-scores/{tid}", {"score": typed_score(team, rnd)})
            if (team, rnd) in FINAL_HINTS:
                status, hint = hit(run, token, "POST", f"/event/rooms/{room}/hints", {"teamId": tid, "note": "Synthetic hint."})
                if status == 200 and (team, rnd) in FINAL_CANCELLED_HINTS:
                    hit(run, token, "POST", f"/event/deductions/{hint['id']}/cancel", {"reason": "Given by mistake."})
            if (team, rnd) in FINAL_CHEATING:
                hit(run, token, "POST", f"/event/rooms/{room}/cheating",
                    {"teamId": tid, "reason": "Synthetic violation.", "confirmed": True})
            if (team, rnd) in FINAL_REFUSED_HINTS:
                status, _ = hit(run, token, "POST", f"/event/rooms/{room}/hints", {"teamId": tid, "note": ""},
                                conflict_expected=True)
                run.final.refusal(status == 409, "third hint", status)
            hit(run, token, "GET", f"/event/rooms/{room}/board")

    threads = [threading.Thread(target=team_work, args=(team,), daemon=True) for team in range(1, len(teams) + 1)]
    threads += [threading.Thread(target=manager_work, args=(code,), daemon=True) for code in managers]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    for team in range(1, len(teams) + 1):
        expected_rooms.setdefault(team, {})[code_of(team)] = typed_score(team, rnd)


class FinalTeamClient(Client):
    """One team's browser in the final: it reloads the screen before every answer and plays its plan."""

    def __init__(self, run, token, team, plan, start_at, gap):
        super().__init__(run, token, "/event/team/final")
        self.team, self.plan, self.start_at, self.gap = team, plan, start_at, gap
        self.steps = []
        if plan["clues"] == 3:
            self.steps += [("recovery", "WRONG ANSWER", False), ("recovery", FINAL_RECOVERY_ANSWER, True)]
        self.steps += [("unlock", None, False)] * plan["wrongUnlock"] + [("unlock", None, True), ("start", None, True)]
        last = plan["stopAfterLayer"] or 4
        for layer in range(1, last + 1):
            if layer == 1:
                self.steps += [("layer", (1, "99999"), False)] * plan["wrongLayers"]
            self.steps.append(("layer", (layer, str(FINAL_LAYER_ANSWERS[layer - 1])), True))
        if last == 4:
            self.steps += [("verdict", (3, plan["verdict3"]), True), ("verdict", (4, "YES"), True)]
            if plan["suspect"] is not None:
                self.steps.append(("suspect", plan["suspect"], True))

    def first_action(self):
        return self.start_at + random.uniform(0, 0.3)

    def leak_check(self, data):
        leaked = forbidden_keys(data, keys=FINAL_FORBIDDEN_KEYS)
        if leaked:
            self.run_state.metrics.error(f"team {self.team}: a final response carries {sorted(leaked)}")

    def write(self, path, body, key):
        status, data = self.request("POST", path, body, key)
        self.leak_check(data)
        if key and status == 200 and random.random() < RETRY_SHARE:
            again_status, again = self.request("POST", path, body, key)
            if again_status == 200:
                self.run_state.final.replay(data, again, f"team {self.team} {path}")
        return status, data

    def repeat(self, path, body):
        """A verdict and a suspect are final: asking again is refused and changes nothing."""
        status, data = self.request("POST", path, body, None, conflict_expected=True)
        self.run_state.final.refusal(status == 409 or (status == 200 and data.get("accepted") is False),
                                     f"team {self.team} {path}", status, data)

    def act(self):
        if not self.steps:
            return math.inf
        self.refetch_board()
        self.leak_check(self.board)
        kind, arg, expect = self.steps.pop(0)
        key = str(uuid.uuid4())
        base = "/event/team/final"
        if kind == "recovery":
            status, data = self.write(f"{base}/recovery", {"answer": arg}, key)
            ok = status == 200 and data.get("correct") is expect
        elif kind == "unlock":
            codes = [PRACTICE_KEYS[code] for code in ROOM_ORDER]
            if not expect:
                codes[0] = "WRONG CODE"
            status, data = self.write(f"{base}/unlock", {"codes": codes}, key)
            ok = status == 200 and data.get("accepted") is expect
        elif kind == "start":
            status, data = self.write(f"{base}/start", {}, None)
            ok = status == 200
        elif kind == "layer":
            layer, value = arg
            status, data = self.write(f"{base}/layers/{layer}", {"value": value}, key)
            ok = status == 200 and data.get("accepted") is expect
        elif kind == "verdict":
            layer, value = arg
            status, data = self.write(f"{base}/verdicts/{layer}", {"verdict": value}, None)
            ok = status == 200 and data.get("accepted") is True
            self.repeat(f"{base}/verdicts/{layer}", {"verdict": "YES" if value == "NO" else "NO"})
        else:
            status, data = self.write(f"{base}/suspect", {"suspectId": arg}, None)
            ok = status == 200 and data.get("accepted") is True
            self.repeat(f"{base}/suspect", {"suspectId": arg % 6 + 1})  # another suspect than the one chosen
        if not ok:
            self.run_state.metrics.error(f"team {self.team}: step {kind} {arg} answered {status} {data}")
        self.refetch_board()
        return time.monotonic() + random.uniform(self.gap * 0.6, self.gap * 1.4) if self.steps else math.inf


class FinalAdminClient(Client):
    """The admin with the scoreboard and the live final table open: a fresh GET of both every 5 s."""

    def first_action(self):
        return self.run_state.started + 2

    def act(self):
        self.request("GET", "/event/admin/final/board")
        self.request("GET", "/event/admin/scoreboard")
        return time.monotonic() + 5


def final_run(args, api, config):
    admin, managers_list, teams = sign_in_everyone(api, config)
    managers = dict(zip(config["managers"], managers_list))
    set_clue_keys(api, managers_list)
    content = FINAL_CONTENT.read_text(encoding="utf-8")
    content = content.replace("PLACEHOLDER - replace this with the real Recovery Challenge question before the event.",
                              "How many bits are in a byte?").replace("PLACEHOLDER-REPLACE-ME", FINAL_RECOVERY_ANSWER)
    api.setup("PUT", "/event/admin/final/content", admin, json.loads(content))
    board = api.setup("GET", "/event/admin/board", admin)
    room_ids = {room["code"]: room["id"] for room in board["rooms"]}
    for room_id in room_ids.values():
        api.setup("PUT", f"/event/admin/rooms/{room_id}/activity", admin, {"activity": None})
    organization = api.setup("GET", "/event/admin/organization", admin)
    by_email = {team["accountEmail"]: team for team in organization["teams"]}
    team_ids = {number: by_email[email]["id"] for number, email in enumerate(config["members"], 1)}
    team_names = {number: by_email[email]["name"] for number, email in enumerate(config["members"], 1)}
    plans = {number: final_plan(number) for number in range(1, len(teams) + 1)}

    run = LoadRun(api)
    run.final = FinalLedger()
    run.begin(args.seconds)
    expected_rooms = {}
    print("Round phase: 16 teams collect clues while 4 managers type scores and record hints and cheating...", flush=True)
    for rnd in range(1, 5):
        api.setup("POST", "/event/admin/round", admin, {"round": rnd})
        for room_id in room_ids.values():
            api.setup("POST", f"/event/admin/rooms/{room_id}/open", admin)
        api.setup("POST", "/event/admin/round/timer/start", admin)
        run_room_round(run, rnd, teams, managers, team_ids, room_ids, plans, expected_rooms)
    api.setup("POST", "/event/admin/round", admin, {"round": 5})
    for room_id in room_ids.values():
        api.setup("POST", f"/event/admin/rooms/{room_id}/close", admin)
    api.setup("POST", "/event/admin/final/open", admin)

    # Every team starts together; about 14 writes per team spread over about 70% of the run.
    gap = args.seconds * 0.7 / 16
    start_at = time.monotonic() + 1
    run.begin(args.seconds)
    clients = [Client(run, admin, "/event/admin/board"), FinalAdminClient(run, admin, "/event/admin/final/board")]
    clients += [FinalTeamClient(run, token, number, plans[number], start_at, gap) for number, token in enumerate(teams, 1)]
    print(f"Final phase: {len(teams)} teams start together for up to {args.seconds} s (a write every ~{gap:.1f} s)...",
          flush=True)
    for client in clients:
        client.start()
    for client in clients:
        client.join()
    elapsed = time.monotonic() - run.started
    for client in clients:
        if isinstance(client, FinalTeamClient) and client.steps:
            run.metrics.error(f"team {client.team} did not finish its plan: {len(client.steps)} steps left")

    # The close finalises the teams that never submitted a suspect; closing twice changes nothing.
    rows_before = {row["teamName"]: row for row in api.setup("GET", "/event/admin/final/board", admin)}
    hit(run, admin, "POST", "/event/admin/final/close")
    hit(run, admin, "POST", "/event/admin/final/close", conflict_expected=True)
    rows = {row["teamName"]: row for row in api.setup("GET", "/event/admin/final/board", admin)}
    scoreboard = api.setup("GET", "/event/admin/scoreboard", admin)
    deductions = api.setup("GET", "/event/admin/deductions", admin)
    cross_check = []
    finalised = 0
    for number in range(1, len(teams) + 1):
        name = team_names[number]
        row, before = rows.get(name, {}), rows_before.get(name, {})
        plan, want = plans[number], final_expected(plans[number])
        got = {"layers": row.get("layersPoints"), "verdicts": row.get("verdictsPoints"), "suspect": row.get("suspectPoints"),
               "time": row.get("timePoints"), "total": row.get("finalScore")}
        submitted = plan["suspect"] is not None
        problems = []
        if row.get("status") != "COMPLETED":
            problems.append(f"not finalised: {row.get('status')}")
        else:
            finalised += 1
        if submitted and (row.get("finishedAt") is None or before.get("finishedAt") != row.get("finishedAt")):
            problems.append("finish time missing or changed after the team's own finish")
        if not submitted and before.get("status") == "COMPLETED":
            problems.append("finalised before the close although it never submitted a suspect")
        for part, value in want.items():
            if got[part] is None or abs(got[part] - value) > 0.005:
                problems.append(f"{part} {got[part]} != {value}")
        view = api.setup("GET", "/event/team/final", teams[number - 1])
        leaked = sorted(forbidden_keys(view, keys=FINAL_FORBIDDEN_KEYS))
        if view.get("status") != "COMPLETED" or leaked:
            problems.append(f"member view {view.get('status')} {leaked}")
        for problem in problems:
            run.metrics.error(f"team {number}: {problem}")
        cross_check.append({"team": number, "finalised": row.get("status") == "COMPLETED", "finishedBy": row.get("finishedBy"),
                            "got": got, "expected": want, "ok": not problems})

    # Room score = typed score less 2 per active hint and 7.5 for an active cheating record; total = rooms + final.
    active_hints = {}
    active_cheating = set()
    for entry in deductions:
        if entry["cancelled"]:
            continue
        pair = (entry["teamName"], entry["roomCode"])
        if entry["kind"] == "HINT":
            active_hints[pair] = active_hints.get(pair, 0) + 1
        else:
            active_cheating.add(pair)
    ranked_totals = []
    for row in scoreboard:
        scores = row["scores"]
        number = next(n for n, name in team_names.items() if name == scores["teamName"])
        problems = []
        rooms_sum = 0
        for line in scores["rooms"]:
            typed = expected_rooms[number][line["code"]]
            hints = active_hints.get((scores["teamName"], line["code"]), 0)
            cheating = (scores["teamName"], line["code"]) in active_cheating
            want = max(0, typed - 2 * hints - (7.5 if cheating else 0))
            rooms_sum += want
            if abs(line["score"] - want) > 0.005 or line["hints"] != hints or line["cheating"] != cheating:
                problems.append(f"{line['code']}: score {line['score']} hints {line['hints']} cheating {line['cheating']}; "
                                f"expected {want} / {hints} / {cheating}")
        want_total = rooms_sum + final_expected(plans[number])["total"]
        if abs(scores["total"] - want_total) > 0.005:
            problems.append(f"total {scores['total']} != {want_total}")
        for problem in problems:
            run.metrics.error(f"team {number} scoreboard: {problem}")
        if row["rank"] is not None:
            ranked_totals.append((row["rank"], scores["total"]))
    ranked_totals.sort()
    if any(a[1] < b[1] for a, b in zip(ranked_totals, ranked_totals[1:])):
        run.metrics.error("the ranking is not ordered by total score")
    if len(scoreboard) != len(teams):
        run.metrics.error(f"the scoreboard lists {len(scoreboard)} of {len(teams)} teams")
    if finalised != len(teams):
        run.metrics.error(f"{finalised} of {len(teams)} teams were finalised")

    endpoints = endpoint_summary(run.metrics.latencies)
    worst_p95 = max(row["p95Ms"] for row in endpoints.values())
    ledger = run.final
    report = {
        "testedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "environment": "Local Windows, PostgreSQL devfest_test, API 8082. Synthetic final traffic; no WAN latency.",
        "baseUrl": args.base_url, "mode": "final", "seconds": args.seconds, "elapsedSeconds": round(elapsed, 1),
        "teams": len(teams), "endpoints": endpoints,
        "totals": {"requests": sum(e["requests"] for e in endpoints.values()), "errors": len(run.metrics.errors),
                   "worstP95Ms": worst_p95, "retries": ledger.retries, "retryMismatches": ledger.retry_mismatches,
                   "refusalProbes": ledger.refusals, "refusalsAnswered": ledger.refusals_ok,
                   "expectedConflicts": run.metrics.expected_conflicts, "finalisedTeams": finalised,
                   "activeHints": sum(active_hints.values()), "activeCheating": len(active_cheating),
                   "deductionLogRows": len(deductions)},
        "finalCrossCheck": cross_check, "diagnostics": ledger.samples,
        "errorSamples": run.metrics.errors[:50],
    }
    output = ROOT / "docs/event-load-final.json"
    output.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print_summary(report)
    totals = report["totals"]
    if (totals["errors"] or totals["retryMismatches"] or totals["refusalsAnswered"] != totals["refusalProbes"]
            or worst_p95 >= 300 or finalised != len(teams)):
        raise SystemExit(f"Final load check failed; see {output}")
    print(f"Final load check passed; report in {output}")


def main():
    args = parse_args()
    api = Api(args.base_url)
    config = json.loads((ROOT / ".local/event-rehearsal.json").read_text(encoding="utf-8-sig"))
    if args.mode == "sse":
        sse_run(args, api, config)
        return
    if args.mode == "sentinel":
        sentinel_run(args, api, config)
        return
    if args.mode == "linux":
        linux_run(args, api, config)
        return
    if args.mode == "final":
        final_run(args, api, config)
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
