"""Sustained synthetic load against the isolated event rehearsal (API 8082, devfest_test data only).

Simulates 16 or 48 team clients, 4 room managers and 1 admin polling the revision endpoint,
refetching their board when it changes, submitting room answers with idempotent retries and
judging concurrently. Results are written to docs/event-load-<scale>x.json.
"""
import argparse
import datetime as dt
import json
import math
from pathlib import Path
import random
import re
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
    args = parser.parse_args()
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


def main():
    args = parse_args()
    api = Api(args.base_url)
    config = json.loads((ROOT / ".local/event-rehearsal.json").read_text(encoding="utf-8-sig"))
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
