# Architecture

## Modules

One standalone Angular application, one modular Spring Boot application and a DevFest PostgreSQL database. Judge0 is an external execution boundary. No company, tenant, discussion, Question/Answer, reputation or voting model remains.

Backend packages under `dev.devfest`:

- `auth`: BCrypt, JWTs, sessions, rotating hashed refresh tokens and rate limits.
- `contest`: one contest, versioned problems, drafts and deterministic scoring.
- `submission`: immutable snapshots, idempotency, ownership and evaluation history.
- `judge`: replaceable `JudgeClient`, real Judge0 adapter, durable queue and workers.
- `admin`: accounts, problem editing, contest settings, rejudge, CSV and bootstrap.
- `api`: safe error responses and startup configuration guards.

Angular uses reactive forms, signals, an API service, bearer interceptor and copied TeamQora controls/tokens. Monaco loads local assets. Plain text bindings display statements, source and output; participant HTML is never rendered.

Spring JDBC replaces TeamQora's company-oriented JPA hierarchy. Explicit SQL keeps row locks, queue claims and transaction boundaries visible. Flyway owns schema changes; no ORM auto-update exists. No additional microservices are introduced.

## Authentication

Only ADMIN and PARTICIPANT exist; no public registration. Bootstrap reads environment configuration once. Participant creation has no role field and unknown JSON properties are rejected. BCrypt cost 12, with passwords of 12–72 UTF-8 bytes. No response includes a password hash.

JWTs last 15 minutes and require issuer `devfest`. Server sessions have a fixed 12-hour lifetime. Each request checks current session revocation and account activation/role. Refresh tokens are random; only SHA-256 hashes persist. Rotation locks the session/token rows. Replaying a consumed token revokes the session, and that revocation commits even when the response is 401. Concurrent rotations cannot create two usable families.

Logout immediately revokes the current session. Password changes/resets revoke all sessions; deactivation also revokes them. Reactivation requires sign-in. Previously accepted submissions still finish after deactivation; new requests are blocked. Admin paths are protected on the server. Submission access additionally requires ownership or ADMIN. Responses use 401 for authentication, 403 for role denial, and 404 for inaccessible private IDs.

Tab-scoped sessionStorage holds tokens. Concurrent 401s share a refresh request and retry once. No authentication cookies are used, so CSRF protection is disabled for explicit bearer authentication. XSS can still read sessionStorage: do not introduce unsafe HTML or remote scripts. HTTPS and a tested CSP/reverse proxy remain event requirements. Local drafts use per-user localStorage keys; shared browser profiles are not strong privacy boundaries. Clear site data on shared devices after the event.

## Data model

`app_user` owns `auth_session` and `refresh_token`. `contest` is constrained to ID 1. `problem` points at its current `problem_version`; immutable version documents include statements, public examples and hidden tests. The participant DTO explicitly excludes hidden tests.

`draft` is keyed by user, contest, problem and language, with a concurrency version. `submission` stores participant, contest, problem/version, RUN/SUBMIT, language ID and exact runtime name, source, custom stdin, receipt timestamp, idempotency key and request hash. `evaluation` stores each generation, verdict, counters, lease and timestamps. `execution_task` persists provider tokens by evaluation/test index. Transactional `audit_event` contains admin IDs/actions, never source/passwords/tests. `rate_bucket` survives app restarts.

Administrators are trusted to access tests and submitted code. Participant responses are whitelisted. Dedicated tests use `devfest_test` and verify its name before truncation; startup validates destination names before Flyway. Deployment still requires restricted database roles and backups. The native bootstrap role belongs to a new isolated local cluster, never TeamQora's service.

## Draft saving and recovery

Local writes and server saves debounce by 700 ms. Failed saves keep local edits and retry every five seconds. Navigation flushes local state. Refresh compares local source/version with the server, not browser timestamps. An in-flight save does not erase edits made while waiting.

The backend updates only a matching version, or inserts version 1 when the client sends 0 and no row exists. Conflicts return 409 and display both choices while preserving local text. Choosing local explicitly uses the current server version; choosing server first makes a local backup. Storage quota errors are visible. A crash inside the debounce window can lose the latest unsaved keystrokes; this is not a synchronous keystroke journal.

## Run and Submit

Both actions take an explicit snapshot and persist before returning HTTP 202 plus UUID. The browser keeps uncertain request UUID/body for exact retry. The server locks the participant row, looks for an existing key and payload hash first, then checks timing, language and outstanding quota. Reusing a key for different code returns 409. Submission and initial QUEUED evaluation commit atomically.

Run uses participant stdin. Submit loads the immutable recorded problem version and evaluates server-side hidden tests. Every test must execute and match output; compilation success is insufficient. Comparison normalizes CRLF/CR to LF, removes trailing spaces/tabs per line and trailing newlines, while preserving leading whitespace, internal spaces and internal blank lines. No floating-point tolerance or custom checker is present.

Run returns bounded stdout/stderr/compiler diagnostics. Submit returns the owner's source, verdict and aggregate test count only; hidden stdout/stderr/compiler text is neither persisted in platform evaluation rows nor returned. Provider errors are sanitized. The execution operator necessarily sees code and hidden input; actual contest secrets need a vetted authenticated provider.

## Queue and worker recovery

Default four worker threads; configurable from one to eight. A PostgreSQL advisory scheduler lock, row locking and SKIP LOCKED bound concurrent active leases across app instances. One evaluation per participant runs at once. Ordering by last dispatch gives other participants a turn. Creation caps three outstanding evaluations and 15 execution requests/minute per participant. Up to 20 sequential hidden tests can occupy a worker longer than a short Run; measure this in rehearsal.

Leases last 45 seconds and renew before provider calls. HTTP timeout is 12 seconds; each provider token has a 120-second polling budget per attempt. Expired leases recover automatically. Infrastructure failures retry at most three attempts with five-second delay. Exhausted work becomes SYSTEM_ERROR; queued work waiting more than 30 minutes is also marked SYSTEM_ERROR.

Final writes require matching lease owner, RUNNING state and unexpired lease. Abandoned/repeated workers cannot apply results twice. Persisted provider tokens are reused after recovery. A crash after provider acceptance but before token persistence can duplicate external computation; exactly-once provider execution is not claimed. Platform submission and scoring remain authoritative. Controlled recovery tests and an actual local process-kill/restart passed (see docs/worker-restart-results.json). Process-kill/provider-outage rehearsal on the event topology is still required.

Rejudge appends a generation and keeps history, using the original problem/test snapshot. Test edits affect future submissions only. Rejudging against a different version would need an explicit new operation. While rejudge is pending, its old acceptance no longer contributes to scoring.

## Timing and scoring

Receipt time is captured by the server inside the creation transaction after acquiring the participant lock. It is not browser click time or completion time. Eligibility is `startAt <= receivedAt < endAt`. An exact persisted request retry is resolved before checking the current deadline. Workers do not re-check contest timing, so accepted work can finish after closing.

Each distinct solved problem contributes one point. Use the earliest accepted receipt time for each solved problem; the last of these is the tie-break. Sort solved count descending, then this timestamp ascending. Exact ties share competition rank (1,1,3); user ID only stabilizes display order. Zero-solve users share a rank below positive scores. Inactive accounts retain historical scores. Scores are recomputed from current evaluation generations; rejudge cannot duplicate them. Replace `ContestService.scores()` to change the rule.

## Isolation and extension points

No untrusted compiler runs on the app host. The adapter accepts no callback URL, arbitrary compiler options, additional files or package installation. It requests disabled networking and bounded CPU, wall time, memory, process count, stack and output. Provider responses are size-limited. These requests do not prove remote enforcement; see [infrastructure/JUDGE0.md](infrastructure/JUDGE0.md).

Add a problem using Admin → Problems; every save creates a version. JSON example/test editing is deliberately simple. Add a language by changing `ProblemData.Language`, adding a Flyway migration for language constraints, updating frontend types/labels/templates, and testing the exact provider runtime. Restart to repeat runtime checks. Replace Judge0 by implementing `JudgeClient` while keeping queue and confidentiality contracts.

Judge0 does not always separate allocation failure from runtime error. Memory-at-limit failures map to MEMORY_LIMIT_EXCEEDED; ambiguous failures remain RUNTIME_ERROR. Excessive output maps to RUNTIME_ERROR. The tested synthetic memory/output cases behaved as expected, but each event runtime still needs validation.

## Arduino: separate future phase

1. **Compile-only:** restricted sketch/board target in a vetted worker with pinned board packages. No arbitrary package installation or promise of hardware behavior.
2. **Circuit simulation:** investigate supported boards, browser integration and specific embedding/event licenses. Wokwi embedding and event rights have **not been verified** and are not assumed.
3. **Physical-board upload:** a consent-based local USB/serial bridge, browser/device compatibility and safe firmware workflow. This is outside Judge0 and the two-week contest scope.
