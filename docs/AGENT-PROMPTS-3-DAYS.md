# DevFest Investigation — 3-day execution prompts

How to use: fill in **Decisions** below first. Then send prompts P1→P7 to the implementing agent **one at a time, in order**. Each prompt tells the agent to read this file's *Common rules* and *Decisions*, so no duplication is needed. After each prompt, bring the agent's report back for review before sending the next.

| Day | Prompts | Outcome |
|---|---|---|
| 1 | P1, P2, P3 | Git baseline · agreed business rules · deployable package + staging |
| 2 | P4, P5 | Operations (monitoring, backups, exports) · role UIs usable |
| 3 | P6, P7 | Full verification on staging · rehearsal + release handoff |

---

## Decisions (fill in before sending P2)

```
D1 RESUBMISSION SCORING  : LATEST            # LATEST (current) | BEST  — which submission's score counts per team+challenge
D2 TIE-BREAK             : SHARED_RANK       # SHARED_RANK (current: 1,1,3) | EARLIEST_FINAL (earlier final-report receipt wins, then shared)
D3 RUBRIC VISIBLE TO TEAMS: YES              # YES (current) | NO — hide rubric from members (also inside submission snapshots)
D4 INTERFACE LANGUAGE    : EN                # EN (current) | AR (Arabic + RTL) | BOTH (switcher)
D5 EXPECTED TEAMS        : __                # number of concurrent teams on event day; load tests use N and 2N
D6 STAGING HOST          : __                # e.g. "VPS 1.2.3.4 with Docker, SSH user X" | Render | Railway | Fly.io
D7 STAGING DOMAIN        : __                # e.g. staging.devfest-example.com (must contain "staging")
D8 EVENT TIMEZONE        : Asia/Amman        # IANA zone shown to managers when entering times
```

---

## Common rules (every prompt)

You are the implementing engineer for the DevFest Investigation Platform at `C:\Users\hassa\OneDrive\Desktop\devfest` (Windows, PowerShell + Git Bash).

Stack — do not replace it: Angular 22 standalone + signals (`frontend/`), Spring Boot 4 / Java 25 with Spring JDBC (no JPA) and explicit SQL (`backend/`), PostgreSQL 18 with Flyway migrations (`backend/src/main/resources/db/migration`, currently V1–V4). Investigation backend: `backend/src/main/java/dev/devfest/investigation/` (`InvestigationAccess` = the single place for authorization; `InvestigationChallenges`, `InvestigationSubmissions`, `InvestigationScoring`, `InvestigationOrganization`, `InvestigationOperations`, `InvestigationLifecycle`, `InvestigationSeed`). Frontend: `frontend/src/app/features/investigation/` (`overview` = admin/manager/member landing, `room` = challenges + review + results, `answer` = member answer/final report). Shared UI controls: `frontend/src/app/shared/ui/`. The original coding contest (role PARTICIPANT, Judge0) must keep working: do not break it.

Read first: `STATUS.md` (top section), `ARCHITECTURE.md`, `docs/INVESTIGATION-OPERATIONS.md`, and this file's Decisions.

Already verified on 2026-10-23 (do not redo without reason): 43/43 backend tests; three-role Playwright flow; actual process restart persistence; backup→restore into a new DB with table fingerprints; 80/160 concurrent-team burst with 0 failures.

Hard rules:
1. Authorization is enforced on the server for every read and write, via `InvestigationAccess`. Members: own team/room only. Managers: assigned rooms only. Changing an ID in a request must return 404, never another team's data. Hiding a button is never sufficient.
2. Never weaken submission guarantees: commit before success, receipt (id + server time), idempotency key + request hash, the immutable-submission trigger, the final-report unique index, and deadline checks inside the transaction after locks.
3. Schema changes only through **new** Flyway migrations (V5, V6, …). Never edit an applied migration.
4. Never run anything against the `devfest` (event/dev) database that mutates test data. Tests and rehearsals use only `devfest_test`. Backend tests and the rehearsal share `devfest_test`: never run them at the same time.
5. No secrets in source control. `backend/.env` and `.local/` are ignored; keep it so.
6. Do not deploy anything to production, access production data, or expand permissions without explicit approval from the user. Staging (D6/D7) is authorized only after the user fills it in.
7. Clean code: small cohesive functions, business rules out of controllers/templates, no broad `any`, no unexplained casts, no speculative abstractions, no new dependencies without a concrete reason, comments only for non-obvious decisions, remove debug code. Match the existing code style.
8. Do not silently invent scoring, tie-break, deadline or resubmission rules; use the Decisions above. If something is still undecided, stop and ask.

Known environment gotchas:
- `infrastructure/start-local-database.ps1` can hang after `pg_ctl start` in a non-interactive shell. Run it in the background and check `.local/postgres.log` for "ready to accept connections".
- The rehearsal (`infrastructure/start-investigation-rehearsal.ps1`, API 8082 / UI 4202) runs `backend/target/devfest-backend-0.1.0.jar`. Rebuild with `.\mvnw.cmd -DskipTests package` after backend changes, and stop the rehearsal first, because Windows locks the running JAR.
- `frontend/e2e/investigation.spec.ts` is not idempotent. Restart the rehearsal (which resets `devfest_test`) before every run.
- `gh` CLI is not installed. GitHub Project access is unavailable unless the user installs it.

Standard checks (run whatever is relevant before reporting):
```powershell
.\infrastructure\test-backend.ps1                      # backend tests on devfest_test
cd backend; .\mvnw.cmd -DskipTests package; cd ..      # JAR for rehearsal
cd frontend; npm run build; cd ..                      # frontend build
.\infrastructure\stop-investigation-rehearsal.ps1; .\infrastructure\start-investigation-rehearsal.ps1
cd frontend; npx playwright test --config playwright.investigation.config.ts; cd ..
```

Report format for every prompt: (1) what changed, with file paths; (2) checks run, with actual results; (3) what is still unverified; (4) blockers and questions. Be precise about the difference between implemented, tested, deployed and operationally verified. If any check fails, say so with the output; never claim success from a build alone.

---

## P1 — Version control and baseline (Day 1, ~1 h)

```
Read docs/AGENT-PROMPTS-3-DAYS.md → "Common rules". Task P1: establish version control and a clean baseline.

1. The project folder is NOT a git repository. Run `git init`, review the existing .gitignore (it must exclude node_modules, target, dist, .angular, .env files, .local/, test-results, playwright-report, .idea, logs). Also make sure `frontend/test-results`, `backend/target` and generated screenshots that are large are excluded or intentionally kept. `docs/*.png` evidence may be committed.
2. Before committing, scan for secrets: grep the tree (excluding ignored paths) for "password", "secret", "PGPASSWORD", "JWT", base64-looking 32+ char strings and email+password pairs. `.local/investigation-rehearsal.json` and `backend/.env` must not be committed. Report what you checked.
3. Create branch `main`, commit everything as "Baseline: existing DevFest platform with investigation module".
4. Create a working branch `investigation-completion` for the following prompts. Commit after each later prompt with a clear message.
5. Run the standard checks once to confirm the baseline is green (backend tests, frontend build, rehearsal + Playwright investigation flow). Record the results.
6. Delete leftover restore databases named `devfest_restore_*` on the local cluster (port 55432) ONLY if their names match that pattern exactly. List them first. Then update `infrastructure/verify-investigation-restore.ps1` so it drops its own destination database after a successful verification (keep it on failure for inspection), and say so in the script output.

Acceptance: git history with a baseline commit; no secrets committed (show the scan); baseline checks green; restore script cleans up after success and was re-run successfully.
Do not push to any remote unless the user gives a remote URL.
```

## P2 — Business rules per Decisions D1–D3 (Day 1, ~4 h)

```
Read docs/AGENT-PROMPTS-3-DAYS.md → "Common rules" and "Decisions". Task P2: make scoring, ranking and rubric visibility match decisions D1, D2, D3 exactly, with tests. If a decision value is still blank or "__", stop and ask.

Current behavior (InvestigationScoring.results and RULES text):
- Totals = sum of scores on each team's LATEST submission per challenge (CTE `latest` using DISTINCT ON … ORDER BY sequence DESC). Unscored latest work counts 0 and is counted in `pending`.
- Rank = rank() OVER (ORDER BY total DESC) within a room → shared ranks 1,1,3.
- Members see the rubric in GET /rooms/{room}/challenges and inside `challengeSnapshot` on their submission responses.

Implement:
A. D1. If LATEST: keep. If BEST: per team+challenge use the maximum awarded points among that team's scored submissions; `pending` = challenges where the team has submissions but none is scored. Keep the "latest" flag in submission responses (it is about versions, not scoring), but the reviewer UI must show which submission currently counts. Final reports are single-submission anyway.
B. D2. If SHARED_RANK: keep. If EARLIEST_FINAL: order by total DESC, then the team's FINAL submission received_at ASC (teams without a final report rank after those with one at equal total), then remaining ties share rank. Implement it in SQL, not in Java sorting, so it is one consistent definition. Expose the tie-break value in the Result record so the UI can show why.
C. D3. If YES: keep. If NO: (1) remove `rubric` from the challenge payload returned to MEMBER (use a member-specific record/DTO, not a nulled field on the shared one if that is cleaner); (2) member submission responses must not expose the rubric through `challengeSnapshot` — return a member-safe snapshot (title, instructions, answerFormat, maxScore, evidenceLinks) or omit the snapshot for members; (3) frontend answer.html must not render a rubric section for members; (4) managers/admin still see everything.
D. Update `InvestigationScoring.RULES` text so it states exactly what is implemented (resubmission, final lock, deadline: opens inclusive / closes exclusive by server time, totals, tie-break, publication auto-unpublish). Because the rules text changed, rules confirmation must be reset: add migration V5 that sets `rules_confirmed=false, results_published=false`, so the organizer re-confirms. Mention this in the report.
E. Tests in backend/src/test/java/dev/devfest/InvestigationIntegrationTest.java (follow its existing setup helpers). Add meaningful cases, not implementation mirrors:
   - totals with resubmissions under the chosen D1 (e.g. first answer scored 90, later answer scored 40 → LATEST gives 40, BEST gives 90);
   - score edit changes totals correctly and repeated identical score requests do not change totals or history;
   - ranks with equal totals under the chosen D2, including a team without a final report;
   - D3=NO: a member's challenge list and submission responses contain no rubric text (assert on the raw JSON string, not on a field);
   - unpublished results: member GET results → 403; manager of another room → 404.
F. Update docs/INVESTIGATION-OPERATIONS.md "Rules currently implemented" to match, and the rules section of STATUS.md.

Acceptance: all backend tests pass (report counts); Playwright investigation flow still passes after the rehearsal is restarted (adjust its expectations only if the rule change legitimately changes them, e.g. the total cell); the rules text, docs and code agree. Commit.
```

## P3 — Deployable package and staging deployment (Day 1, ~4 h)

```
Read docs/AGENT-PROMPTS-3-DAYS.md → "Common rules" and "Decisions" (D6, D7). Task P3: make the app deployable reproducibly and deploy it to STAGING only. If D6/D7 are blank, do steps 1–4 locally and stop before step 5, then ask.

Current state: no Dockerfiles; infrastructure/compose.yaml defines only PostgreSQL services; infrastructure/nginx.conf.example is an unverified draft; frontend/public/config.js holds runtime frontend config; backend reads env vars (see backend/.env.example and backend/src/main/resources/application.properties); StartupSafety refuses unsafe production configs (public Judge0, etc.).

1. backend/Dockerfile: multi-stage (Maven wrapper build with tests skipped → slim JRE 25 runtime), non-root user, only the JAR copied, healthcheck hitting GET /api/v1/health. No secrets baked in.
2. frontend/Dockerfile: multi-stage (npm ci + build → nginx serving dist/devfest/browser), SPA fallback to index.html, /api proxied to the backend service, gzip, sensible cache headers (hashed assets long cache, index.html no-cache), security headers (CSP compatible with the locally served Monaco assets, X-Content-Type-Options, Referrer-Policy, frame-ancestors none). Base it on infrastructure/nginx.conf.example and fix what does not work.
3. Extend infrastructure/compose.yaml with an `app` profile: database (volume, not exposed publicly), backend, frontend. All secrets come from an env file that is gitignored; update infrastructure/.env.example with every required variable and a comment for each. For the investigation-only event set DEVFEST_WORKER_ENABLED=false and JUDGE0_URL empty unless the coding contest is also used; confirm StartupSafety accepts this in non-development mode, and adjust StartupSafety only if it wrongly blocks that configuration (with a test).
4. Run the whole stack locally with Docker if available. If Docker is unavailable on this machine, say so explicitly; then validate the Dockerfiles as far as possible and test on the staging host instead.
5. Staging (D6/D7): deploy with HTTPS (Caddy or the platform's TLS), DEVFEST_DEVELOPMENT=false, a fresh database, a generated JWT secret and admin password stored only on the host / platform secret store, DEVFEST_ORIGINS set to the staging origin. Do NOT enable the investigation seed on staging; create rooms/teams/accounts through the admin UI or API instead, with clearly synthetic names.
6. Write docs/DEPLOYMENT.md: prerequisites, env vars, first deploy, update/redeploy (zero data loss: migrations run on startup via Flyway — say what happens if a migration fails), rollback (previous image + DB restore), where logs are, how to rotate the admin password and JWT secret (rotating the JWT secret signs everyone out — say so).

Acceptance: `curl https://<staging>/api/v1/health` → UP; admin sign-in works over HTTPS; a member and manager created on staging can complete one submission and one scoring; response headers checked (show them); no secret in the repo or images (`docker history` / image inspection). Report the exact deployed commit. Commit the packaging.
```

## P4 — Operational reliability: monitoring, backups, exports (Day 2, ~4 h)

```
Read docs/AGENT-PROMPTS-3-DAYS.md → "Common rules". Task P4 (feature F09): make incidents diagnosable and recoverable on staging.

Current state: GET /api/v1/health (DB SELECT 1 only); audit_event table + admin GET /api/v1/investigation/audit; admin JSON export GET /api/v1/investigation/export; local restore script infrastructure/verify-investigation-restore.ps1; no error monitoring; no request IDs; no scheduled backups.

1. Request correlation: a servlet filter that assigns/propagates an X-Request-Id header, puts it in the logging MDC and returns it in responses. Error responses from dev.devfest.api.Errors must include the requestId so a team can quote it. Never log request bodies, passwords, tokens, Authorization headers or answer text. Add a test that an error response carries the requestId and that the log pattern contains it.
2. Structured logs: one line per request (method, path template if available, status, duration ms, user id, role, requestId) at INFO; 5xx at ERROR with stack trace. Keep it with Spring Boot's built-in logging (no new logging framework).
3. Health: keep /api/v1/health public and cheap. Add an admin-only GET /api/v1/investigation/status returning: DB reachable, server time, last lifecycle observation time (from InvestigationLifecycle), counts of open challenges, submissions in the last 5 and 60 minutes, unscored latest submissions per room, active sessions count, results published flag. This feeds the admin UI in P5.
4. Actionable error monitoring: integrate the staging platform's alerting if it has one; otherwise configure an external uptime check on /api/v1/health (document which service and who is alerted) and a simple log-based alert for ERROR lines. If no external account is authorized, document the exact setup steps for the user instead of creating accounts yourself.
5. Backups on staging: use the hosting platform's managed backups if available, AND a scheduled pg_dump (custom format) every 30 minutes during the event window and daily otherwise, with retention (e.g. keep 48 dumps) and copies off the app host. Script it under infrastructure/ with a dry-run mode.
6. Restore verification on staging: restore the latest staging backup into a NEW separate database (never over the live one), and compare per-table counts + content hashes like verify-investigation-restore.ps1 does (write a bash equivalent if the host is Linux). Record docs/staging-restore-results.json. Only after this succeeds may docs call staging backups "verified".
7. Admin results export: add GET /api/v1/investigation/rooms/{room}/results.csv (admin only, and managers for their own rooms) with rank, team, total, pending, and per-challenge points; protect against CSV formula injection exactly like the existing coding-contest CSV export (find and reuse that helper; do not duplicate it). Tests: admin 200, other-room manager 404, member 403/404, anonymous 401, formula prefix neutralized.
8. Update docs/INVESTIGATION-OPERATIONS.md (incident guide: how to find a request by requestId, where alerts go, backup schedule, restore command) and STATUS.md.

Acceptance: backend tests pass; on staging: requestId visible in an error response and the matching log line; status endpoint works for admin and is 403 for others; a backup was taken and restored into a separate DB with matching fingerprints (show the results file). Commit.
```

## P5 — Role interfaces usable and clear (Day 2, ~5 h)

```
Read docs/AGENT-PROMPTS-3-DAYS.md → "Common rules" and "Decisions" (D4, D8). Task P5 (feature F08): make each role's essential workflow clear on phones and laptops, reusing frontend/src/app/shared/ui components and the existing styles. No new UI framework.

Known problems to fix:
1. Managers must type ISO timestamps with offsets into text inputs ("Opening time (with timezone)"). Replace with `datetime-local` inputs interpreted in the event timezone D8 (show the zone next to the field and convert to an ISO instant before sending). Show existing times in the event timezone AND the viewer's local time if they differ. Keep server validation unchanged. Put the conversion in a small tested utility, not inside the component.
2. room.html/room.ts mixes challenge editing, the review queue, the scoring panel and results (~450 lines). Split into focused standalone components: challenge-editor, review-queue (with filters: "unscored", "latest only", by challenge, by team), score-panel (rubric, report, points, feedback, score history rendered as a readable table instead of raw JSON), results-table. The room page composes them. No behavior change apart from the listed improvements.
3. Member landing: show team name, room, and for each visible challenge a status chip: Not started / Draft saved / Submitted (receipt id + time) / Final locked / Closed, plus the closing time with a countdown when under 1 hour. Use data from existing endpoints; if one request per challenge would be needed, add one member endpoint that returns the summary in a single query (authorization through InvestigationAccess.member).
4. Admin overview: an operational status panel using GET /api/v1/investigation/status from P4 (auto-refresh every 30 s, with manual refresh), preflight issues with links to the place to fix them, and the audit log list (paged, readable action names).
5. Branding: the sidebar says "DevFest CODE ARENA" and its brand link goes to /contest even for investigation roles. For MANAGER/MEMBER, show "DevFest Investigation" and link to /investigation.
6. States: every page must have explicit loading, empty, error-with-retry, saving/saved and closed/locked states. Disabled buttons must say why (e.g. "Closed", "Rules not confirmed", "Already submitted").
7. D4 language: if EN, keep English but make all strings consistent. If AR or BOTH: set lang/dir on <html>, use CSS logical properties (margin-inline, padding-inline, inset-inline, text-align: start) across shared UI and investigation styles, mirror icons/arrows, keep receipts/IDs/URLs/numbers in LTR spans (`dir="ltr"`), format dates with the Arabic locale while keeping the timezone explicit. Translate member and manager screens fully; admin screens may remain English if time is short (say so). Use one simple translation mechanism consistently; do not mix approaches.
8. Accessibility basics: labelled inputs, focus visible, status messages in role="status"/"alert", contrast OK, tap targets ≥ 40px on mobile, no horizontal scroll at 390px.

Verification:
- npm run build passes.
- Update frontend/e2e/investigation.spec.ts for changed labels (keep all existing assertions about drafts, the ambiguous-retry receipt, the final lock, scoring, unpublished results and publication). Add assertions for: member status chips after submission; manager "unscored" filter; datetime entry via the new inputs.
- Run the flow at desktop 1440px and mobile 390px; save screenshots to docs/ (member landing, answer, manager review, admin status). Look at them and fix visual problems before reporting.
Commit.
```

## P6 — Full verification on staging (Day 3, ~5 h)

```
Read docs/AGENT-PROMPTS-3-DAYS.md → "Common rules" and "Decisions" (D5). Task P6 (feature F10): verify the complete competition on STAGING with synthetic data, and document tested capacity. Do not use real participant data.

Prepare: a synthetic admin, 2 managers, 2 rooms, D5×2 synthetic member accounts and teams (script it through the API; store credentials only in an ignored local file).

Verify, recording evidence for each item in docs/staging-verification.md (command/test, expected, actual, pass/fail):
1. Isolation via direct API requests (script, not just UI): member A reading team B's submission by ID → 404; member reading another room's challenges/submissions/results → 404; manager of room 1 on room 2 challenges/submissions/score/history → 404; member/manager on admin endpoints (accounts, export, status, audit, preflight) → 403; anonymous → 401; draft or not-yet-open challenge content absent from member responses (search the raw JSON for its title text).
2. Sessions: expired access token refreshes transparently; revoked session (logout, deactivation, password reset) → 401 on the next request; a member whose session expires while editing a draft keeps the edits (local recovery) and can save after signing in again. Add this as a Playwright test.
3. Submissions: double-click submit → exactly one receipt; retry with same key → identical receipt; same key + different body → 409; 20 parallel submits from one team with different keys → 20 distinct accepted versions, none lost; final report: second final → 409, edits after lock → 409.
4. Deadlines: set a challenge to close ~2 minutes ahead; submit at T-10s (accepted, receipt time < closesAt), at T+5s (409), and retry of the T-10s request after closing (original receipt returned). Server time is authoritative; report the clock offset of the client used.
5. Persistence: restart the backend container mid-test; all receipts, drafts and scores identical afterwards (reuse the logic of infrastructure/verify-investigation-restart.ps1).
6. Scoring: score, edit, identical retry; totals and ranks correct per the D1/D2 rules; results invisible to members until published; any new score auto-unpublishes.
7. Backup restore on staging into a separate DB (from P4) — re-run and attach the results.
8. Load: adapt load-tests/investigation.py to take --base-url, --teams and --minutes (credentials from a file, refuse to run against a URL without "staging" or localhost). Run (a) a synchronized burst at D5 and 2×D5 teams, and (b) a sustained 10-minute run at D5 teams with realistic pacing (draft save every 5 s per team, a submission every ~2 min, history polling every 30 s). Record p50/p95/p99/max per operation, error count, DB CPU/memory and app memory. State the tested capacity and its limits. Note: submissions and draft saves serialize on the investigation_settings row lock; if p95 exceeds 1 s at 2×D5, report it with measurements and propose (do not silently implement) a fix.

Fix any defect found (with a regression test), redeploy to staging, and re-run the affected checks. Update STATUS.md: list each verification as implemented / tested locally / tested on staging / operationally verified. Commit.
```

## P7 — Organizer rehearsal and release handoff (Day 3, ~3 h)

```
Read docs/AGENT-PROMPTS-3-DAYS.md → "Common rules". Task P7: prepare and support the organizer rehearsal on staging, then produce the release handoff. Do not deploy to production; prepare it for the user's approval.

1. Write docs/REHEARSAL-SCRIPT.md: a 45-minute timed script for real organizers using the three roles on staging. Admin: preflight, accounts, room/team setup, rules confirmation, manager assignment. Managers: prepare and open challenges (one closes during the rehearsal), the review queue, scoring and a score edit. Members (at least 3 people on phones and laptops): browse, draft, refresh, submit, resubmit, the final report and lock. Incidents to simulate: backend restart, deadline extension, a team that "lost" its receipt (reconcile via history/export), a forgotten password (admin reset), publication then a late score edit (auto-unpublish → republish). Include a checklist for observers to record issues.
2. Write docs/EVENT-DAY-CHECKLIST.md: T-1 day, T-2 h, T-30 min, during (what to monitor on the admin status panel every 15 min), at close, after close (final backup, export, publish), plus who does what and the contacts placeholder.
3. Production readiness gate in docs/RELEASE.md: the list of conditions that must be true before production (all P6 items pass on staging, restore verified on the production platform, secrets rotated and not shared with staging, seed disabled, DEVFEST_DEVELOPMENT=false, rules confirmed after final content, synthetic accounts absent, backups scheduled, alerting tested by triggering it once). Mark each item's current state honestly.
4. Clean up: remove debug code and temporary workarounds; make sure README, ARCHITECTURE (add an Investigation section: data model, authorization, submission/receipt flow, scoring, lifecycle), STATUS, DEPLOYMENT and INVESTIGATION-OPERATIONS agree with the code. Run all standard checks one final time.
5. Final handoff report to the user: features F01–F10 mapped to implemented / tested / deployed / operationally verified; all checks with results; remaining limitations; setup, migration and deployment instructions (links); organizer preflight and recovery instructions (links); the single next most important action.
Commit and tag `rehearsal-ready` (do not push without a remote from the user).
```

---

## After each prompt

Bring the agent's report back here for review. Typical review questions: did it run the checks or only build; did it change authorization outside InvestigationAccess; did it edit an applied migration; did docs claim "verified" without evidence.
