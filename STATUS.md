# Status — 2026-10-23

## Investigation competition (ADMIN / MANAGER / MEMBER)

**Classification: implemented and verified locally on the rehearsal stack. Not deployed. Hosting backups unverified. Business rules await organizer sign-off.**

| Check (run 2026-10-23) | Result | Evidence |
|---|---|---|
| `infrastructure/test-backend.ps1` | 43/43 passed (13 investigation integration tests: isolation, direct API requests, hidden content, concurrent retries, deadlines, draft versions, final lock, scoring edits/publication, ranks, expired/revoked sessions, DB score limit, lifecycle audit) | `.local/investigation-tests.log` |
| Three-role browser flow (admin → manager → member → manager scoring → publication) | Passed twice on a freshly reset fixture. The earlier recorded timeout was a test race (room URL read before navigation finished), now fixed | `docs/investigation-browser-results.json` |
| Actual backend process kill and restart | Receipt, report, snapshot and score identical after restart and a new sign-in | `docs/investigation-restart-results.json` |
| Backup → restore into a new database | All 12 tables match row count and content hash | `docs/investigation-restore-results.json` |
| Synchronized 80 and 160 concurrent teams (browse/draft/submit/retry/history) | 0 failures; all receipts unique; retries returned identical receipts; max 568 ms | `docs/investigation-load-results.json` |
| Frontend `npm run build` | Passed | — |

Limits of this evidence: a single local Windows host with no WAN latency, and one burst cycle rather than sustained traffic. All submissions and draft saves serialize on one settings-row lock (by design, for consistency); this held at 160 concurrent teams locally but has not been measured on event hardware. No staging deployment exists. No error-monitoring service is attached (logs plus `/api/v1/health` only). Operations, preflight and incident guide: [docs/INVESTIGATION-OPERATIONS.md](docs/INVESTIGATION-OPERATIONS.md).

---

## Coding contest (original Code Arena) — status as of 2026-10-20


**Classification: verified local MVP / working prototype. Not ready for competition rehearsal on an event deployment.** The app runs independently at `C:\Users\hassa\OneDrive\Desktop\devfest`. TeamQora stayed read-only; its Git working-tree status remained clean and no source database was used. No deployment, publication or subscription was performed.

## Implemented and verified locally

- Separate Angular frontend, Spring Boot backend, DevFest development DB and dedicated test DB; Flyway V1 applied on both new databases.
- Bootstrap administrator, admin-created participants, role protection, active-account checks and no public registration.
- BCrypt, issuer-checked JWT, locked refresh rotation, replay revocation, immediate logout/deactivation/password-change revocation; no hashes in responses.
- Contest overview/times, problem list/details, public examples and hidden tests, pinned C++/Java/Python, locally served Monaco and templates.
- Custom-input real Run and hidden-test real Submit; durable IDs, immutable code/problem/runtime snapshot, idempotency and per-user outstanding limits.
- Local/server draft autosave, offline-save recovery, refresh recovery, version conflict resolution and separate language/user keys. Unsaved edits also survive logout and session-expiration redirects.
- Ownership and hidden-output confidentiality; correct start/deadline boundaries and persisted retries after closing; accepted work finishes after closing.
- PostgreSQL queue, bounded concurrency, participant fairness, leases, bounded retries, stale-owner result rejection and no duplicate scoring.
- **Actual process restart:** killed the identified DevFest backend while a synthetic submission was RUNNING, rebuilt/restarted, and the same ID finished ACCEPTED with attempts=2 and one evaluation generation.
- Admin participant lists/forms, activation and contest settings, versioned problem editing, submission history/filtering, scoreboard and rejudge. Two problems have reference solutions in all three languages.

## Evidence and commands

| Check | Actual result | Evidence |
|---|---|---|
| `infrastructure/test-backend.ps1` | 17 PostgreSQL integration + 5 rule tests passed | `backend/target/surefire-reports/TEST-dev.devfest.*.xml`, `.local/backend-tests.log` |
| `mvnw.cmd -Dtest=StartupSafetyTest test` | 6 new configuration-safety tests passed | dedicated Surefire report |
| `mvnw.cmd -Dtest=JudgeTransportTest test` | 2 new body-size/stall-timeout tests passed | dedicated Surefire report |
| Backend final `mvnw.cmd -DskipTests package` | Passed; final artifact started successfully | `backend/target/devfest-backend-0.1.0.jar`, `.local/backend.log` |
| Frontend final `npm run build` | Passed after the draft/logout fix; 304.56 kB initial bundle and local Monaco assets generated | `frontend/dist/devfest/browser` |
| Original two Playwright journeys | 2 passed, about 31 s | `docs/browser-results.json` and screenshots |
| Targeted session recovery browser regression | 1 passed | `docs/session-recovery-test.txt` |
| `python tools/verify-real-execution.py` | 14/14 real cases passed | `docs/real-execution-results.json` |
| `python tools/verify-worker-restart.py` | Same durable ID recovered and ACCEPTED | `docs/worker-restart-results.json` |
| 80-user browse, 30 s | 3,600 requests, zero errors; p95 ~31 ms, p99 ~63 ms | `docs/load-browse.json` |
| 80-user drafts, 30 s | 1,600 GET/PUT requests, zero errors; p95 ~63 ms, p99 ~141 ms | `docs/load-draft.json` |
| Results CSV smoke check | Admin 200, participant 403, anonymous 401; headers and all rows parse correctly | `docs/export-smoke-results.json` |
| Resource sample around draft check | Java maximum working set ~284.34 MiB; ~26.69 cumulative CPU seconds across the 60 s observation | `docs/resource-samples.csv` |

The **30 backend tests passed across the original suite and two targeted additions**, not one newly repeated full run. The user's continuation request was honored: prior passing tests were not repeated without a code change/failure reason. Browser regression and provider transport checks were added for newly discovered edge cases. The final worker restart also exercised the updated real HTTP transport.

Real cases: custom stdin/output; both examples in Python/C++/Java; Wrong Answer; Compilation Error; Runtime Error; Time Limit Exceeded; excessive output; Memory Limit Exceeded; real rejudge. Test solution files are synthetic, not contestant data. No mock provider is installed. Queue tests deliberately apply controlled outcomes only inside the dedicated test DB and are not represented as real judging evidence.

Initial failures corrected: a reserved SQL index name before its first migration applied, generated styling encoding, browser test reload/textarea assertions, JAR packaging while Windows held the running file, and stable draft ownership during logout. Whole-body provider reads now have an enforced time/size bound.

## Implemented but not fully verified

- CSV export was verified for admin access, denied participant/anonymous access and parseable rows. Formula-prefix protection is implemented; full spreadsheet import/format review remains optional.
- Admin password-reset UI/API, all validation edge cases and all combinations of problem limits have not been exhaustively exercised in a browser.
- Provider outage/slow-queue behavior has controlled retry/transport tests, but prolonged network outages and restart under mixed 80-user load have not been rehearsed.
- Full 80-user result polling with individual evaluation records, synchronized 80 Run requests, and 80 Submit requests with realistic hidden-test counts are prepared, **not executed**. Overall 80-contestant capacity is unverified.
- Optional Docker setup, reverse proxy/TLS/CSP and a clean second-machine installation are documented, not executed.
- Screenshots were inspected and mobile horizontal overflow checked; no formal accessibility audit or full browser/device matrix.

## Known limitations and infrastructure gates

1. The real checks used public `ce.judge0.com`. Its actual patch state, sandbox/host isolation, private-data retention and event quota were not established. It is blocked outside explicit development mode. `JUDGE0_SECURITY_REVIEWED` is an operator declaration, not an automated attestation.
2. Pinned public runtimes work, but are not certified maintained; Java JDK 17.0.6 is notably old. Obtain patched runtime images on a vetted provider, update IDs/names and rerun all language/error cases before student use.
3. No execution infrastructure was deployed. Use a dedicated patched Linux VM or vetted service, separate databases/networks, restricted egress and enforced limits. A container alone is insufficient.
4. Thirty-second local API measurements exclude internet latency and provider resources; they cannot justify an 80-user execution claim. Resource monitoring covers the Java process and frontend parent, not all child processes or Judge0 workers.
5. A crash between provider acceptance and token persistence can duplicate external work, while platform ID/result application remain protected. Rejudge uses original test versions; pending rejudge temporarily removes its old score contribution.
6. Local sessionStorage remains XSS-readable. Drafts remain in per-user localStorage and can be inspected by someone controlling the same browser profile. No claim of cross-profile isolation is made.
7. Monaco 0.55.1 can emit a **Canceled** error from its own disposal task during rapid route teardown. It did not lose drafts or block navigation in the regression. This exact library-only warning is recorded in `docs/browser-teardown-warnings.json`; the test still rejects other browser errors. It has not been hidden by global error suppression.
8. Basic JSON editing for examples/hidden tests and simple status filters favor maintainability. Scoreboard is manually refreshed. Rate/session history retention maintenance is not automated.
9. Development/test/load accounts exist only in the new DevFest database and are clearly labeled. Load accounts appear on its scoreboard. Use a clean rehearsal/event database; never run the mutation-heavy verification helpers against event data.
10. No source LICENSE was found; reuse attribution is preserved, but authors' distribution rights must be confirmed before publishing.

## Deferred

Emailed password reset, own-password-change screen (API exists), richer audit UI, advanced filters, per-case visual test editor, theme switch and extra polish. AI assistance for contestants, full IDE/debugger/terminal/packages, multi-file projects, teams, simultaneous contests, plagiarism tooling and all Arduino integrations remain excluded. Arduino future options are documented separately without assuming Wokwi embedding/event rights.

## Essential completion criteria

| Criterion | Local evidence |
|---|---|
| 1 Independent startup | Separate builds, ports and databases; no runtime source-path references |
| 2 Bootstrap admin | Fresh DevFest DB bootstrap succeeded |
| 3 Participant and contest configuration | Admin APIs used; browser forms inspected |
| 4 Load problems/hidden tests | Two versioned seeds and all-language references |
| 5 Participant login | Browser journey |
| 6 Monaco editing | Browser journey |
| 7 Refresh draft recovery | Browser/offline/conflict/logout regressions |
| 8 Real custom-input Run | API and browser, expected stdout |
| 9 Durable Submit ID | Real integration, duplicate test and actual restart |
| 10 Real hidden-test verdict | Both examples × three languages |
| 11 Admin/private-resource protection | PostgreSQL/MockMvc integration tests |
| 12 Server deadline enforcement | Exact-boundary and after-deadline retry/completion tests |

All twelve have local evidence. Their event-environment equivalents remain unverified, especially execution isolation and 80-way capacity. This does not turn the prototype into an approved contest service.

## Next steps in dependency order

1. Follow README locally, read architecture/reuse audit, and review event problem content.
2. Select and validate isolated execution hosting, maintained runtimes, network controls and capacity/quota; record evidence before disabling development mode.
3. Set final runtime pins, secrets, origins and HTTPS/CSP; rerun real cases on that host.
4. Run separate and mixed 80-user polling/Run/Submit scenarios; record queue waits, throughput, provider resources and every acknowledged ID.
5. Rehearse provider outages, process restart under load, backup/restore and the exact deadline on the event network.
6. Complete organizer/participant rehearsal, fix findings, freeze versions, and clear synthetic accounts by starting a fresh event database.

Remaining estimate: **30–45 hours**, assuming suitable execution hosting is available. Building secure sandbox hosting from scratch can add 20–40+ hours and exceed the two-week budget. See the 77-hour daily plan.
