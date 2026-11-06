# Capacity rehearsal

Capacity for 80 simultaneous contestants is NOT established by a build or small execution test. Use a fresh DevFest rehearsal database and a vetted, private execution provider with documented quota. This harness refuses execution bursts against ce.judge0.com.

Prerequisites: backend running with seeded problems; contest open; backend/.env contains bootstrap credentials for the existing DevFest admin. Run `python tools/verify-real-execution.py` first to prove the integration. Harness creates 80 clearly labeled load accounts (passwords stored only in ignored .local/load-accounts.json). Authentication is prepared outside measurements and throttled to respect login limits. Existing data is preserved; load accounts affect the scoreboard, so use a rehearsal copy, never the event database.

From project root:
```
python load-tests/load.py browse --users 80 --seconds 60
python load-tests/load.py draft --users 80 --seconds 60
python load-tests/load.py poll --users 80 --seconds 60
python load-tests/load.py run --users 80 --allow-execution-burst
python load-tests/load.py submit --users 80 --allow-execution-burst
```
Run requests use custom stdin; Submit uses the seed problem's 5 hidden tests (80 jobs = 400 individual provider executions; extend to 10/20 tests for worst-case planning). Run and Submit are separate synchronized bursts, not 80 logins confused with 80 executions. Do not increase concurrency before verifying host isolation and provider limits.

In a second PowerShell terminal run `./load-tests/monitor-resources.ps1 -Seconds 300`. It records the app parent processes' cumulative CPU time and working set, not provider resource usage. Monitor provider workers, its separate PostgreSQL/Redis, host memory/CPU and sandbox failures separately using the provider's monitoring. Angular's npm parent is not the complete frontend process tree.

JSON reports include p50/p95/p99 API latency, acknowledged/preserved IDs, completed evaluations, per-job queue wait and evaluation time, throughput, errors and test count. Client defaults: API p95 < 1 s, no failed requests, all IDs preserved and terminal. Event-specific queue targets (suggest initial run p95 <30 s, submit p95 <120 s) must also be assessed; the harness does not claim these passed. A terminal SYSTEM_ERROR is recorded and must fail event acceptance even if durability passed. Poll mode uses history lists and per-ID detail when records exist; prepare completed jobs before claiming per-ID polling load was exercised.

Repeat after terminating an application worker during queued/running jobs, restarting it, and briefly disconnecting the provider. Verify no lost IDs or duplicate scoring and recovery after 45-second leases. Unit/integration recovery tests use controlled DB state; this full process-level rehearsal remains separate. Run mixed browsing+autosave+polling alongside bursts only after individual cases pass. Keep raw reports and resource evidence, and document machine, network, deployment version, runtime fingerprints and provider quota.

## Event load check

`load-tests/event_load.py` (Python standard library only) puts sustained, realistic traffic on the investigation event API. It refuses any base URL whose host is not `127.0.0.1` or `localhost` and reads credentials from the ignored `.local/event-rehearsal.json`, so it only ever runs against the isolated rehearsal on port 8082 with disposable `devfest_test` data — never the event database.

From the project root, in PowerShell 7:
```
./infrastructure/start-event-rehearsal.ps1      # resets devfest_test event data, starts API 8082 + UI 4202, waits until healthy
python load-tests/event_load.py --scale 1 --seconds 300
./infrastructure/stop-event-rehearsal.ps1
./infrastructure/start-event-rehearsal.ps1      # fresh data before the second run
python load-tests/event_load.py --scale 3 --seconds 300
./infrastructure/stop-event-rehearsal.ps1
```
Run it on a freshly started rehearsal (not after the browser test) and do not run backend tests at the same time; they share `devfest_test`.

What it does:
- Setup (not measured): signs in the admin, the 4 seeded room managers and the 16 seeded teams once each (inside the 10 logins per account per minute limit), sets round 1 and opens all four rooms, so every group has an open scheduled room for the whole run.
- Clients: `--scale 1` runs 16 team clients, `--scale 3` runs 48 (three clients share each team's token, like three browser tabs), plus 4 managers and 1 admin.
- Every client polls `GET /event/revision` every 10 s (jittered start) and refetches its one board when the revision changes (`/event/team/board`, `/event/rooms/{id}/board` or `/event/admin/board`).
- Each team client submits to its scheduled room about every 60 s with a fresh `Idempotency-Key`; about 20% are immediately retried with the same key and body and must return the identical receipt. This stays well under the 20 attempts per minute per team limit.
- Each manager saves a judgement for a team in its room about every 20 s; a 409 version conflict is counted separately as expected and followed by a board refetch.
- After the run, `GET /event/team/submissions` for each team must list every receipt that team was given.
- `--seconds` is limited to 30–780 because access tokens last 15 minutes and the harness signs in only once.

Output: `docs/event-load-1x.json` / `docs/event-load-3x.json` with per-endpoint (method + route template) request counts, errors, p50/p95/p99/max latency, totals (requests, errors, expected judgement conflicts, accepted submissions, retries, retry mismatches, duplicate receipt ids), the per-team submission cross-check and up to 50 error samples. A summary table is printed. The script exits non-zero on any unexpected non-2xx response or network failure, any retry that returned a different receipt, any receipt id produced by two different keys, or any missing receipt in the cross-check. It measures the local machine only; it is not a hosting capacity certification.

### SSE mode (live revision push)

```
./infrastructure/start-event-rehearsal.ps1
python load-tests/event_load.py --mode sse --streams 21
python load-tests/event_load.py --mode sse --streams 150
./infrastructure/stop-event-rehearsal.ps1
```
Setup as above (sign-ins, clue keys, round 1 with all rooms open and the timer running), then it takes `--streams` single-use tickets round-robin over the 21 seeded accounts (inside the 30 tickets per account per minute limit), opens that many `GET /event/stream` connections, and performs `--actions` (default 20) admin actions, alternating timer pause and resume `--gap-ms` (default 500) apart. For every action and stream it records when the first `revision` event with at least the action's revision arrived. Output `docs/event-load-sse-<N>.json`: deliveries vs. expected, missing deliveries, p50/p95/max/min of the time from the action's HTTP response to the event (negative = the event arrived before the client finished reading the response) and from the request start, admin action latency, errors, and the backend JVM's heap, live threads and working set (via `jcmd` and `tasklist`) before, with the streams open and after the actions. It exits non-zero on any error or missing delivery. Both SSE runs can follow each other on the same rehearsal.

### Quiz mode (AI room activity)

```
./infrastructure/start-event-rehearsal.ps1
python load-tests/event_load.py --mode quiz --seconds 300
./infrastructure/stop-event-rehearsal.ps1
```
Setup (not measured): sign-ins and clue keys as above; the admin switches the AI quiz on for the AI room, the AI
manager imports 45 questions (20/15/6/3/1) and 3 repairs (100 points) with pass score 30, the admin sets round 1, sends the 12 teams of
groups B–D to the AI room with room exceptions, opens the room and starts the timer — so all 16 teams play the quiz in
one round. Each team client then answers every question (a random option, about 80 % of `--seconds` for all writes),
presses Finish MCQs and submits its 3 repairs in a random order (the last one evaluates the team), then reads its
result; after each write it fetches the activity again, like the page. About 20 % of saves are retried
with the same Idempotency-Key and must return the identical receipt; about 5 % of answers are followed by a different
answer with a new key, which must be refused with 409 ("Already answered."). Every client also polls the revision
every 10 s; the AI manager fetches the results every 5 s, like the results page.

Afterwards the AI room results must show, for every team, exactly 45 answers and 3 repairs and a total equal to the
score the harness computed from the organizer's answer key; all 16 teams must be evaluated exactly once, each with
the outcome its own total predicts (status Passed / Not passed, clue granted, and the team seeing the clue only on a
pass), and the activity JSON must never name a total, points or the pass score. Output
`docs/event-load-quiz.json` (per-endpoint latencies, totals, results cross-check). The script exits non-zero on any
unexpected error, retry mismatch, accepted duplicate answer, wrong total, wrong or missing evaluation, or an endpoint p95 ≥ 300 ms.

### Linux mode (terminal package, team form and organizer dashboard)

```
./infrastructure/start-event-rehearsal.ps1
python load-tests/event_load.py --mode linux --seconds 90
./infrastructure/stop-event-rehearsal.ps1
```
Setup (not measured): sign-ins and clue keys as above; the admin switches the AI quiz off and the Linux terminal
activity on, the Linux manager uploads a package of just under 20 MB unpacked (one incompressible file) and saves the
answer keys, the admin sends the 12 teams of groups A, B and D to the Linux room with room exceptions, opens it and
starts round 1 — so all 16 teams use the room in one round. Each team client starts its terminal (the files
description, the package download, the extra tools archive (`/terminal/devfest-tools.tgz`, a static file of the site read from
`--ui-url`, default `http://localhost:4202`, so the UI must be running), then a restart whose `If-None-Match` must be answered 304), saves its six-field
form five times (early saves are partly blank; about 20 % are retried with the same Idempotency-Key and must return the
identical answer) and submits it once; the last two teams only save, so the round's end submits them. The Linux manager
keeps the organizer dashboard open (a GET every 5 s). The package download is recorded as `GET download` and the tools archive as `GET download tools`; they are the
only traffic excluded from the 300 ms p95 limit; the backend JVM's heap and working set are sampled every second for
the first 30 s (the downloads).

Afterwards, for every team: exactly one submission on the room board, status Submitted on the dashboard, a dashboard
total equal to the score its own answers predict (3 per correct parameter, 3 when message and clue are right) and its
clue only when its final clue was right. Output `docs/event-load-linux.json`. The script exits non-zero on any error,
retry mismatch, restart that is not a 304, team without a submitted form, or p95 ≥ 300 ms outside the downloads.
