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
