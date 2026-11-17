# Status — 2026-10-24

## Change set 2026-11-09: the 100-point scoring and the crypto final

- Four rooms of 15 plus a 40-point final; hints (−2, two per team across rooms) and cheating (−7.5, one active per team
  and room) are point deductions in an append-only log with cancellation, replacing the minute penalties, adjusted time,
  the per-room recovery challenges and the accusation final (migration V20; the old tables were dropped, audit entries
  kept). A manager-typed 0–15 score covers a room without an automatic score.
- The final "The Last Shipment": unlock, mission, four evidence files opened by passphrase (any order) and one suspect, its own terminal package, one Recovery Challenge for the 3-clue
  teams, one 40-minute timer for everyone (admin pause, resume and added time), time points from active minutes, exactly-once
  finalisation at time up. Content is `docs/final/last-shipment-content.json` (format 2), imported and validated by the admin.
- UI: the team's final screens (a lazy chunk), the manager's deduction actions on each current team card, the admin's
  Scores page (scoreboard, live final table, penalty log), content import and the final timer controls.
- `infrastructure/test-backend.ps1`: `Tests run: 511, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS (also after the cleanups below).
- Cleanups: "Hints: n of 2" shows once on a current team's card; deduction rows no longer leave a tall gap under the title; the judgement's own
  informational hint counter is gone from the API, the model and the tests (migration V21 drops `room_judgement.hints` and
  `room_judgement_history.hints`; nothing read it).
- `load-tests/event_load.py --mode final --seconds 90` (16 teams start the final together; hints, cheating, a cancelled hint and a refused third hint
  recorded; two teams through the Recovery Challenge; two teams finalised by the admin's close): 1009 requests, 0 errors, worst p95 143.4 ms,
  24 retries with the same key all equal, 45 of 45 repeats of one-time answers refused, 16 of 16 teams finalised exactly once, scores and ranking
  as predicted. Report: `docs/event-load-final.json`.
- Rehearsal dev server: the `ng serve` process was hard-killed (no exit event) when started from an agent shell; started from a scheduled task it
  survives. Dev-only, see the 2026-11-09 notes in docs/EVENT-PERFORMANCE.md.
- Browser: `event-final.spec.ts` (3) and `event-scores.spec.ts` (2) pass, as do the journey, live, evidence, clues,
  overrides, teams and contest specs; screenshots `docs/final-*.png` at 1440 and 390 px.

## Event prototype (ADMIN / MANAGER / MEMBER)

**Classification: foundation prototype, implemented and tested locally. Not deployed. No server backups exist yet. Practice content only.**

The generic investigation module (V2–V4, `dev.devfest.investigation`, its UI, scripts and tests) was replaced by the event module (`dev.devfest.event`, migration V5, `frontend/src/app/features/event`). Copies of every removed or rewritten file are in the ignored `.local/backup-old-investigation/`. Rules: [docs/EVENT-RULES.md](docs/EVENT-RULES.md). Design: [docs/EVENT-ARCHITECTURE.md](docs/EVENT-ARCHITECTURE.md).

### Implemented

- 4 fixed rooms, groups A–D, computed rotation over 4 rounds, admin round control with a display-only countdown.
- Team room submissions with server-side clue normalization, idempotent receipts (Idempotency-Key, request hash, append-only rows), client-side retry storage, and a rate limit of 20 per minute per team.
- Manager room board: judging with optimistic versions and history, hints (-2) and cheating (-7.5) as point deductions with cancellation, typed 0-15 scores for rooms without an automatic score, room clue-key editing until the final opens.
- Final: the crypto final (unlock, four layers, one-time verdicts, suspect) with one Recovery Challenge for 3-clue teams and one 40-minute timer, the 100-point ranking (`EventRanking`, computed on every read), publication.
- Admin control, teams & accounts, and content pages; CSV export; alerts for incomplete setup.
- Live refresh by a pushed revision number (Server-Sent Events, polling fallback every 3 s); every board is one aggregated endpoint.
- Development seed (development mode only) and an isolated rehearsal on `devfest_test`.

> Entries dated before 2026-11-09 below are history. The minute penalties, adjusted time, per-room recovery challenges and accusations they mention were retired by migration V20 (see the 2026-11-09 change set above).

### Tested locally (2026-10-24)

| Check | Result | Evidence |
|---|---|---|
| `infrastructure/test-backend.ps1` | `Tests run: 91, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS: 47 event integration tests (authorization, submissions/rotation, judging, penalties, final, ranking, revision, organization), 21 event domain unit tests, and the unchanged contest suites (PlatformIntegrationTest 17, RulesTest 5, StartupSafetyTest 6, JudgeTransportTest 2) | `.local/event-tests.log` |
| `mvnw -DskipTests package` / `npm run build` | Success / success; initial bundle 316.91 kB raw (86.20 kB transfer), Monaco only in the lazy coding workspace chunk | — |
| Playwright event journey (admin → member → manager → helper rounds → final → recovery → accusations → publication → ranking), 390 px overflow checks, no console errors | 1 passed | `docs/event-browser-results.json`, `docs/event-*.png` |
| Manual API checks on the rehearsal | member → admin board 403; AI manager → Linux board 404; anonymous → team board 401; member team board contains no clue key | [docs/EVENT-PERFORMANCE.md](docs/EVENT-PERFORMANCE.md) |
| EXPLAIN of board and ranking queries | Every filter/join has a usable index; all statements ≤ 0.5 ms | [docs/EVENT-PERFORMANCE.md](docs/EVENT-PERFORMANCE.md) |
| Load check 300 s at 1× (21 clients) and 3× (53 clients) | 0 errors, 0 duplicate receipts, all retries identical; worst p95 28.6 ms, max 30.8 ms | `docs/event-load-1x.json`, `docs/event-load-3x.json` |

### Change set 2026-11-08: AI Mission Room, SENTINEL (parts A, B and C)

- The AI room's quiz is replaced by **SENTINEL**: ten stages in three missions (PREDICT, SEE, NOOR), 100 points, on the
  round timer, each stage submitted once and then locked. Rules [docs/EVENT-RULES.md](docs/EVENT-RULES.md) §19, design
  [docs/EVENT-ARCHITECTURE.md](docs/EVENT-ARCHITECTURE.md) "AI mission room activity", manager guide and file format in
  the README, and the shipped, complete content file [docs/ai-room/sentinel-content.json](docs/ai-room/sentinel-content.json)
  (no clue inside).
- **Backend (A)**: migration V19 (drops the quiz tables, renames `quiz_evaluation` to `sentinel_evaluation`, adds
  `sentinel_content`, `sentinel_run`, `sentinel_submission`); pure solvers (KNN, confusion metrics, K-Means, A*, Naive
  Bayes, forward chaining) and scorers; a versioned content file imported with every problem listed and nothing saved on
  any problem; team endpoints (state, run, submit); budgets enforced under the team lock; one evaluation per team (last
  stage or the round's end); `SentinelScoreService.roomScore` (read-only, maximum 100). Kept: per-room activity
  (`AI_QUIZ` value), pass score (default 60, locked by the first evaluation), room-submission path, manager verdict,
  reset and snapshots. The event's ranking is unchanged.
- **Team page (B)**: lazy `sentinel-activity` chunk: start screen, three mission panels with stage status, the timer, one
  SVG screen per stage (no library), Field notes, both endings; "Room complete" instead of "Submitted" for rooms
  without an answer form (AI mission, contest results).
- **Organizer page (C)**: `/event/room/:roomId/mission` (lazy `mission-page`): import with confirmation and the problems
  list, export, pass score, **Print Field Guide** (`/event/room/:roomId/mission/guide`, one A4 page), live results (per-stage
  points, total / 100, status, last saved, finished; refreshes every 5 s; 6 SQL statements whatever the number of teams),
  CSV; the manual verdict stays on the room board. `load-tests/event_load.py --mode sentinel` replaces the quiz mode.
- **Harness fix**: the rehearsal script now also waits for the seeded accounts (the seed runs after the web server starts,
  so "health UP" could come before the accounts existed and the first API sign-in of a spec answered 401).
- **Outlier Hunt fix**: the readings a run flags are no longer written in the file; the server derives them per axis
  (IQR with Tukey's hinges, Z-score with the population standard deviation; flagged if outside on x or y), Ping 12 moved
  to (5.6, 4.7), the method is stated in the Field note and the Field Guide, and a test pins the flags (IQR 1.5: pings 12,
  13, 14; IQR 3: 13, 14; Z 2: 13, 14; Z 3: none). Another test checks each stage's texts and numbers against its own data.
- **SENTINEL fixes**: a refused import shows the server's headline ("The file has N problems.") with every problem under
  it as "Stage N · field: …" and a **Try again** button; Nearest Vehicle draws the unknown vehicle last as a hollow 12 px
  diamond (2.5 px dark outline, white halo); Threat Filter bars are exactly proportional on one shared scale with no
  minimum; Group the Frames labels sit inside the centroid's group, so they follow it (checked by a Playwright test);
  Route Planner has new costs and estimates (A* expands Library, Lab, Dorm, Admin, Garage, Gate 3 for cost 15 with no f
  tie on the frontier; greedy by h pays 47 via Cafeteria and Gym) and a test that no frontier tie exists; Rule Engine asks
  "This conclusion is not in what you know. Submit anyway?" before locking an unproven conclusion.
- The rehearsal's `ng serve` now runs with `--no-prebundle`: its Vite dependency re-optimization step was killing the dev
  server mid-run.
- Verification of this change set: see the report of the change (numbers are not repeated here so they cannot go stale).

### Change set 2026-11-07: Problem Solving room, website part (Codeforces contest results and clue release)

- The contest runs on Codeforces (the site never calls it). A room with the new `CONTEST_RESULTS` activity (admin switch,
  third choice next to the AI mission and Linux terminal; V18 turns it on for the Problem solving room on a fresh setup) shows
  teams no answer form. The room's manager or the admin sets one **https** contest link (audited with the host only,
  content lock, moves the revision); only the team assigned to the room this round sees it, while its timer is
  RUNNING or PAUSED, with "Your results are being reviewed by the room organizer."; after the release "Clue unlocked" and
  the clue in the existing masked clue card. No member response carries a count, point, penalty, place or eligibility
  (raw-JSON tests in every round and timer state), and the clue is in none before its release.
- Organizer page `/event/room/{id}/contest` (manager or admin; everyone else 404): teams grouped by round, ticks for
  problems A–E and a whole-minute penalty per team, saved per team (audited, no revision bump), and the computed solved,
  points (A=2, B=3, C=3, D=2, E=2), bonus (1st 3, 2nd 2, 3rd 1; none for 0 solved), total / 15, place, eligible (>= 3
  solved) and released; sorted by place, refreshing every 5 s, CSV through the formula-safe helper. Pure
  `ContestScoring` (tie places 1, 1, 3, 4 give bonuses 3, 3, 1, 0); totals are never stored. `ContestScoreService.roomScore`
  is the read-only hook for a later global ranking; the event ranking is unchanged.
- Release (single team or a whole round): 409 when not eligible or without a clue key; idempotent; one transaction
  under the event_state, teams, room lock order; recorded through the ordinary room-submission path as CORRECT with a
  server-made summary, adds the ROOM clue, finishes the room for the team, one revision bump, broadcast after commit,
  audited. The manager's Accept / Reject still grants or revokes it; key changes never re-check it; editing results
  later changes no clue and marks "Released, but no longer eligible". Admin board warnings for a missing link or clue key.
  V18: `contest_room_link`, `contest_result`, `contest_release` (append-only); resets and snapshots include the last two.
- Tested: `test-backend.ps1` -> `Tests run: 425, Failures: 0, Errors: 0, Skipped: 0` (was 376: ContestScoringTest 16,
  ContestUrlTest 4, EventContestTest 29); organizer page 4 SQL statements with 2 and with 14 teams, team view 6 with 2 and
  with 14; initial bundle 356.45 kB -> 357.60 kB raw (+0.32%), 97.96 -> 98.11 kB transfer, the page is the lazy
  `contest-results-page` chunk (18.84 kB). Playwright on a fresh rehearsal: `npx playwright test -c playwright.event.config.ts`
  -> `18 passed (6.6m)`, exit 0, including `event-contest.spec.ts` (earlier attempts on this low-memory machine lost the
  rehearsal's UI dev server mid-run without an error and were discarded). Screenshots:
  `docs/ui-after/contest-team-1440/390`, `contest-team-unlocked-1440/390`, `contest-organizer-1440/390`.
  Rules §20, README guide "Event: Problem Solving room contest".

### Change set 2026-11-07: Linux room form without the encrypted message

- The target file holds one encrypted clue, so the Linux team form has **five** fields (PATH, USERNAME, FILE_SIZE,
  PATTERN, Final clue) and the answer keys four. The fifth scored item is **Clue** (3 points when the Final clue is
  correct, still checked against the room's clue key and collected as before); the total stays 15. Overriding it still
  never grants or revokes a clue. V17 drops `terminal_form.message_value` and `terminal_answer_key.message_key` and
  renames the override item `MESSAGE_CLUE` to `CLUE` (existing overrides carry over). The CSV loses the message column
  (`cluePoints` replaces `messageCluePoints`). The event's final and its encrypted message are untouched.

### Change set 2026-11-07: Linux room, challenge package tools, file dates and terminal clock

- **Extra tools** for rooms with a server package (not for other rooms): `file` (+ magic database), `base64`, `bzip2`,
  `bunzip2`, `bzcat`, `zip` and GNU `find` (+ `xargs`), from official Alpine Linux v3.24 x86 (musl) packages verified
  against Alpine's signed index and pinned by SHA-256 (`infrastructure/build-terminal-tools.py`,
  `infrastructure/terminal-tools.lock.json`), shipped as one static, deterministic archive
  `frontend/public/terminal/devfest-tools.tgz` (1,886,271 bytes, not part of the room package), unpacked into
  `/usr/local` during the invisible setup with `/usr/local/bin` first on `PATH`. Licenses: `TOOLS-LICENSES.txt`.
  Package rooms use an 80 MB guest (measured necessary: 6 MB free with 64 MB, 21 to 22 MB with 80 MB).
- **Manifest 5th column** `mtime` (`2026-03-14T09:26:00Z`, 1970 to 2099): files and folders keep exact dates; folders are
  written after their contents; four-column manifests unchanged.
- **Terminal clock** per room (V16 `room_terminal_bundle.terminal_clock`, `PUT /rooms/{id}/terminal-bundle/clock`): set
  without re-upload, content-locked, audited, member/other manager 404; the setup runs `date -s`.
- Tests: backend 376 (was 362), 0 failures; Playwright event suite 17 passed, `--repeat-each=2` 34 passed; Linux load
  test (16 teams, tools archive included) 0 errors, p95 26.3 ms excluding downloads. Details: LINUX-ROOM.md §4, §6, §10.

### Change set 2026-11-06: Linux room, part C (organizer dashboard and scoring)

- A separate page `/event/room/{id}/dashboard` for the room's manager and the admin (everyone else: 404): the **answer
  keys** (PATH, USERNAME, FILE_SIZE, PATTERN with several values one per line, and the message; empty = not auto-awarded,
  with an admin board warning), every active team's form with ✓/✗ per item, an **Award / Deny / Automatic** override
  per item (audited, "Overridden", clearable), the score out of 15 (3 per correct parameter, 3 when message and clue are
  both right; computed on read, never stored), submit time, sorted by total, refreshing every 5 s, CSV export through the
  formula-safe helper, and Test terminal with the current package. Overriding the fifth item never grants or revokes a
  clue. The 15 points stay in the room; the ranking is unchanged. V15 `terminal_answer_key`, `terminal_form_override`;
  resets and snapshots include the overrides. Rules §18.8, [docs/LINUX-ROOM.md](docs/LINUX-ROOM.md) §7.

### Change set 2026-11-06: Linux room, part B (team form)

- A room with the `LINUX_TERMINAL` activity (the admin's activity switch, now with a second checkbox; V13 turns it on
  for the Linux room on a fresh setup) shows the team a six-field form next to the terminal instead of the answer form:
  PATH, USERNAME, FILE_SIZE, PATTERN, Encrypted message, Final clue. Save (repeatable, Idempotency-Key) and Final
  submit (confirmation, irreversible; V14 `terminal_form`, `terminal_form_request`). State comes from the server; only
  the assigned team, while its round runs, the room is open and the form is not submitted (404 / the usual 409s
  otherwise); the team sees only Saved / Submitted. The final answer — by the team or at the round's end for a form
  with something saved, once, inside the existing round-end transition — is recorded through the ordinary
  room-submission path (technical output = a server-made summary; Final clue checked against the manager's clue key; a
  correct one is added as a ROOM clue). "Use selection as technical output" is hidden for this room; "Copy selection"
  stays. Rules §18.7, [docs/LINUX-ROOM.md](docs/LINUX-ROOM.md) §6.
- The answers other than the clue are stored but not checked yet (the organizer dashboard, scoring and answer keys are
  part C).

### Change set 2026-11-06: Linux room, part A (server package for the terminal)

- A room manager (or the admin) uploads **one ZIP** on the room board (**Server package**); the server validates it
  (every problem at once), builds one gzip-compressed ustar archive and stores it with its SHA-256 in
  `room_terminal_bundle` (V13), apart from the room rows. The assigned team's terminal downloads it under exactly the
  gates of the text files (`GET /team/rooms/{id}/terminal-bundle`; ETag and `If-None-Match` → 304 without loading the
  archive), installs it from `/` with `gzip -dc | tar -xf - -C /`, adds the owners to `/etc/passwd` and `/etc/group`
  before the shell starts, and shows "Rami's server is mounted. There is no internet in this terminal.". Rooms without
  a package keep the text files unchanged. `RoomActivity` has a second value, `LINUX_TERMINAL` (V13 sets it for the
  Linux room); it changes nothing for teams yet (the team form and the organizer dashboard are parts B and C).
- Rules [docs/EVENT-RULES.md](docs/EVENT-RULES.md) §18.6, author guide [docs/LINUX-ROOM.md](docs/LINUX-ROOM.md)
  (ZIP and manifest format with an example, commands available and missing), design in
  [docs/EVENT-ARCHITECTURE.md](docs/EVENT-ARCHITECTURE.md) "Server package".
- Tested: see the numbers recorded in the report of this change; screenshots in `docs/ui-after/`
  (`terminal-package-1440/390`, `terminal-package-errors-1440/390`).

### Change set 2026-10-27: team evidence with masked clues

- After a room is finished for a team (its round is over, by rotation or admin exception; round 5 or the final
  finishes every room), the team home shows an Evidence card: latest technical output, clue answer with
  Correct/Incorrect, and — only if the team collected that clue — the room's official clue behind an eye toggle
  (auto-hides after 15 s, on tab hidden and on navigation; Copy without revealing). The final page lists all four
  rooms under "Your evidence" (except LOCKED/CLOSED). Rule change: a member may now receive a clue key, but only
  for a finished room whose clue its own team collected ([docs/EVENT-RULES.md](docs/EVENT-RULES.md) §5.7, §17).
  Team board and final carry `evidence` (`RoomEvidence`); decision logic in `EvidenceVisibility`. No schema change.
- Tested: `test-backend.ps1` → `Tests run: 185, Failures: 0, Errors: 0, Skipped: 0` (new: EventEvidenceTest 8,
  EvidenceVisibilityTest 5; EventAuthorizationTest's key check rewritten to the new rule); team board 5 statements
  for 2 or 18 teams; backend package and frontend build succeed (initial bundle 350.00 kB); Playwright event suite
  8 passed, then `--repeat-each=3` 24 passed (new `e2e/event-evidence.spec.ts`); screenshots in `docs/ui-after/`
  (`team-evidence-masked`, `team-evidence-revealed`, `final-evidence`, each at 1440 and 390 px).

### Change set 2026-10-26: live revision push (Server-Sent Events)

- Every committed revision is pushed to open screens over `GET /api/v1/event/stream` (single-use 30-second ticket
  from `POST /api/v1/event/stream-ticket`; no JWT in URLs). The broadcast is wired in one place
  (`EventStateRepository.bumpRevision` → after-commit listener), so rolled-back writes are never announced. 15-second
  heartbeats re-check each stream's session; revoked sessions (replaced, organizer sign-out, deactivated, password
  reset, logout) lose their streams right after the revocation commits. The browser's `RevisionSync` replaces the
  10-second poller: catch-up `GET /revision` on every (re)connect, 3-second polling while the stream is down,
  reconnect after 1/2/5/10 s, 30-second safety poll while healthy. No schema change. Design:
  [docs/EVENT-ARCHITECTURE.md](docs/EVENT-ARCHITECTURE.md) "Revision push (SSE) with polling fallback".
- Tested: `test-backend.ps1` → `Tests run: 172, Failures: 0, Errors: 0, Skipped: 0` (new: EventStreamTest 6 — tickets,
  push within 1 s, rollback sends nothing, revoked sessions closed, heartbeats, 100 streams without pool
  exhaustion); backend package and frontend build succeed (initial bundle 349.99 kB, +1.87 kB); Playwright event
  suite 7 passed, then `--repeat-each=3` 21 passed (new `e2e/event-live.spec.ts`: every screen within 1.5 s,
  polling fallback, second sign-in signs the first device out within 2 s); SSE load 21 streams 420/420 and
  150 streams 3 000/3 000 deliveries, p95 0.5 ms / 3.8 ms after the action response, no thread growth; manual
  check through the 4201 dev proxy: a second admin window followed a change in 7–11 ms.

### Change set 2026-10-24: admin overrides toolkit

- New admin page **Overrides**: swap/move teams with a rotation preview, per-team room exception for the current
  round, manual clues, time adjustments, disqualify/reinstate, snapshots (list, download, take now), and resets of
  the final, one team or the whole event (RESET + password). Every override is admin only, needs a reason, is
  audited and confirmed; every reset saves a snapshot first. V9 (room exceptions, snapshots, audit reasons,
  disqualification, MANUAL clues, TIME_ADJUSTMENT penalties, reset-only deletes of append-only rows). Guide:
  [docs/EVENT-ADMIN-OVERRIDES.md](docs/EVENT-ADMIN-OVERRIDES.md).
- Tested: `test-backend.ps1` → `Tests run: 165, Failures: 0, Errors: 0, Skipped: 0` (new: EventOverrideTest 11,
  two EventRankingTest cases, RotationPreviewTest 6, RoomAssignmentTest 3, one PenaltyRulesTest case); backend
  package and frontend build succeed; Playwright event suite 4 passed, then `--repeat-each=3` 12 passed (new
  `e2e/event-overrides.spec.ts`); manual check: RESET_TEAM snapshot holds the team's 3 submissions, 3 verdicts and
  1 clue; 1× load check 1 467 requests, 0 errors, worst p95 34.2 ms. Restoring a snapshot is not implemented.

### Change set 2026-10-24: manual round timer and arrival check

- "Next round" now only assigns rooms (phase Assigned); the admin starts, pauses, resumes, adds or removes time
  and ends the round, and room managers confirm "All teams are here". Answers are accepted only while the timer
  runs. V8 adds the timer fields to `event_state`, `room_arrival` and `round_time_adjustment`; all timer math is
  in the `RoundTimer` domain class. Procedure: README "Event-day round procedure".
- Tested: `test-backend.ps1` → `Tests run: 142, Failures: 0, Errors: 0, Skipped: 0` (new: RoundTimerTest 10,
  EventRoundTimerTest 8); backend package and frontend build succeed; Playwright event suite 3 passed, then
  `--repeat-each=3` 9 passed (an earlier repeat run lost 1 of 9 to a OneDrive lock on a screenshot file; the
  screenshot write now retries); manual check: paused at 1495 s, 1495 s after 30 s, resumed at 1495 s;
  1× load check 1 469 requests, 0 errors, worst p95 30.6 ms.

### Change set 2026-10-24: room managers own the clue key; results follow key changes

- **Managers must set their room's clue key on the room board before the admin opens the room**; the seed and
  the application contain no keys. V7 adds `submission_clue_verdict` (+ history): saving a changed key
  re-checks every answer of the room (4 SQL statements, any count) and recomputes ROOM clues (RECOVERY clues
  untouched); managers can Accept / Reject / Automatic per answer; teams see only Correct / Incorrect and
  "Updated after review". Technical output stays the manager's Pass / Fail decision.
- Tested: `test-backend.ps1` → `Tests run: 124, Failures: 0, Errors: 0, Skipped: 0` (new: EventClueReviewTest 6,
  EventClueRecheckStatementsTest 1, ClueCheckTest 3, ClueVerdictTest 3); backend package and frontend build
  succeed; Playwright event suite 3 passed, then `--repeat-each=3` 9 passed (each test restarts the rehearsal
  backend on reset data); 1× load check 1 465 requests, 0 errors, worst p95 26.7 ms; no practice key in
  `backend/src/main`, `frontend/src` or the migrations.

### Change set 2026-10-24: organizer-controlled team count and one device per team

- V6: `event_state.planned_team_count`, `auth_session.revoked_reason` and `last_seen_at`. Planned team count N
  (1–64) with group cap ceil(N / 4) and balance warnings; bulk team generation with one-time passwords; groups
  locked from round 1; MEMBER accounts keep one session (latest sign-in wins); 401 responses name the reason;
  admin sees signed-in / last-seen per team and can sign a team out; seed size via `DEVFEST_EVENT_SEED_TEAMS`.
- Tested: `test-backend.ps1` → `Tests run: 111, Failures: 0, Errors: 0, Skipped: 0` (new: EventSessionTest 6,
  5 more EventOrganizationTest cases, GroupCapacityTest 5, RoundRobinGroupsTest 4); backend package and
  frontend build succeed; Playwright event suite 2 passed (new `e2e/event-teams.spec.ts` + the journey); manual
  API check: second sign-in → first token 401 `REPLACED`; 1× load check 1 468 requests, 0 errors, worst p95
  28.0 ms ([docs/EVENT-PERFORMANCE.md](docs/EVENT-PERFORMANCE.md)).

### Not yet verified

- No deployment, HTTPS/reverse proxy, or event hardware/Wi-Fi measurement.
- No backup/restore procedure or process-restart rehearsal for the event schema yet. The old investigation evidence files in `docs/investigation-*` describe the removed module.
- No organizer sign-off on the real event content, or on the assumptions listed in the delivery report.
- No formal accessibility audit; the browser matrix is Edge only.

`docs/INVESTIGATION-OPERATIONS.md` is outdated and kept for reference only.

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
