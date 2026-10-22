"""Synthetic local investigation admission test. Only the isolated 8082 rehearsal is accepted."""
import concurrent.futures
import datetime as dt
import json
from pathlib import Path
import secrets
import statistics
import threading
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
BASE = "http://127.0.0.1:8082/api/v1"
CONFIG = json.loads((ROOT / ".local/investigation-rehearsal.json").read_text(encoding="utf-8-sig"))


def request(method, path, token=None, data=None, key=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if key:
        headers["Idempotency-Key"] = key
    payload = json.dumps(data).encode() if data is not None else None
    start = time.perf_counter()
    with urllib.request.urlopen(urllib.request.Request(BASE + path, data=payload, headers=headers, method=method), timeout=60) as response:
        raw = response.read()
    return (json.loads(raw) if raw else None), (time.perf_counter() - start) * 1000


def login(email, password):
    return request("POST", "/auth/login", data={"email": email, "password": password})[0]["accessToken"]


def main():
    admin = login(CONFIG["adminEmail"], CONFIG["adminPassword"])
    stamp = secrets.token_hex(4)
    room = request("POST", "/investigation/rooms", admin, {"name": "Synthetic load " + stamp, "archived": False})[0]["id"]
    now = dt.datetime.now(dt.timezone.utc)
    challenge = request("POST", f"/investigation/rooms/{room}/challenges", admin, {
        "title": "Synthetic load answer", "instructions": "Synthetic load data only", "evidenceLinks": [],
        "answerFormat": "Text", "rubric": "One synthetic point", "maxScore": 1, "kind": "ANSWER", "state": "OPEN",
        "opensAt": (now - dt.timedelta(hours=1)).isoformat(), "closesAt": (now + dt.timedelta(hours=2)).isoformat(), "version": 0,
    })[0]["id"]
    password = secrets.token_urlsafe(24)
    tokens = []
    # Setup is deliberately excluded from the admission timing measurement.
    for i in range(160):
        email = f"load-{stamp}-{i}@investigation.test"
        user = request("POST", "/investigation/accounts", admin, {"name": f"Synthetic {stamp} {i}", "email": email, "password": password, "role": "MEMBER"})[0]["id"]
        request("POST", "/investigation/teams", admin, {"name": f"Load {stamp} {i}", "roomId": room, "representativeId": user, "archived": False})
        tokens.append(login(email, password))
        if (i + 1) % 40 == 0:
            print(f"Prepared {i + 1}/160 synthetic team accounts", flush=True)

    results = []
    for count in (80, 160):
        barrier = threading.Barrier(count)
        timings = {name: [] for name in ("browse", "draft", "submit", "retry", "history")}
        failures = []
        receipts = []

        def actor(index):
            token = tokens[index]
            barrier.wait(timeout=60)
            observed = {}
            _, observed["browse"] = request("GET", f"/investigation/rooms/{room}/challenges", token)
            draft, _ = request("GET", f"/investigation/challenges/{challenge}/draft", token)
            report = {"conclusion": f"Synthetic {count} {index}", "reasoning": "Load verification", "evidence": "Synthetic"}
            _, observed["draft"] = request("PUT", f"/investigation/challenges/{challenge}/draft", token, {"report": report, "version": draft["version"]})
            key = str(uuid.uuid4())
            receipt, observed["submit"] = request("POST", f"/investigation/challenges/{challenge}/submissions", token, report, key)
            retry, observed["retry"] = request("POST", f"/investigation/challenges/{challenge}/submissions", token, report, key)
            assert receipt == retry, "Retry did not return the exact durable receipt"
            history, observed["history"] = request("GET", f"/investigation/rooms/{room}/submissions", token)
            assert len([s for s in history if s["id"] == receipt["id"]]) == 1, "Receipt missing or duplicated"
            return observed, receipt["id"]

        started = time.perf_counter()
        with concurrent.futures.ThreadPoolExecutor(max_workers=count) as pool:
            for future in concurrent.futures.as_completed([pool.submit(actor, i) for i in range(count)]):
                try:
                    observed, receipt = future.result()
                    receipts.append(receipt)
                    for name, value in observed.items():
                        timings[name].append(value)
                except Exception as error:
                    failures.append(type(error).__name__ + ": " + str(error))
        elapsed = time.perf_counter() - started
        summary = {name: {"requests": len(values), "p95Ms": round(sorted(values)[max(0, int(len(values) * .95) - 1)], 2), "maxMs": round(max(values), 2)} for name, values in timings.items() if values}
        result = {"concurrentTeams": count, "elapsedSeconds": round(elapsed, 2), "uniqueReceipts": len(set(receipts)), "failures": failures, "operations": summary}
        results.append(result)
        print(json.dumps(result), flush=True)
    evidence = {"testedAt": dt.datetime.now(dt.timezone.utc).isoformat(), "environment": "Local Windows, PostgreSQL, API 8082. Synthetic investigation traffic; no code execution or WAN latency.", "scope": "One synchronized browse/draft/submit/retry/history cycle per team, at 80 and 160 teams. Not a sustained event capacity certification.", "scenarios": results}
    (ROOT / "docs/investigation-load-results.json").write_text(json.dumps(evidence, indent=2), encoding="utf-8")
    if any(result["failures"] for result in results):
        raise SystemExit("Load verification failed; see docs/investigation-load-results.json")


if __name__ == "__main__":
    main()
