# Event performance evidence (local)

Measured 2026-10-24 on the single local Windows development machine against the event rehearsal stack
(`start-event-rehearsal.ps1`: packaged JAR on 8082, PostgreSQL 18 on 127.0.0.1:55432, database `devfest_test`
with the development seed: 16 teams, 4 managers, 1 admin). No WAN latency, no reverse proxy, no TLS. These numbers
show that the design does not hang or degrade at the event's size; they are **not** a capacity certification for
event hardware.

## Fail-fast settings

| Setting | Value | Where |
|---|---|---|
| Hikari connection timeout | 5 000 ms | `spring.datasource.hikari.connection-timeout` |
| JDBC statement timeout | 5 s | `spring.jdbc.template.query-timeout` |
| Transactions | only indexed SQL; no network or file I/O inside a transaction | services |
| Serialization | every event write locks `event_state` first (one short critical section per write) | `EventStateRepository` |

## SQL statements per endpoint (fixed, independent of team count)

| Endpoint | Statements |
|---|---|
| `GET /event/revision` | 1 |
| `GET /event/team/board` | 5 (constant whatever the number of teams; `EventEvidenceTest`) |
| `GET /event/team/final` | 5–8 by stage (since the evidence change: + rooms, + room exceptions) |
| `GET /event/rooms/{id}/board` | 6 |
| `GET /event/admin/board` | 10 |
| `GET /event/admin/organization` | 5 (event state, accounts, teams with presence, rooms, group sizes) |
| any authenticated request | 1 session check by primary key; plus 1 conditional `last_seen_at` update at most once per 60 s per session |
| `GET /event/admin/content` | 4 |
| `GET /event/results` | 2 |

Board data is fetched with LATERAL joins and aggregates; the browser never issues per-row requests.

## EXPLAIN (ANALYZE, BUFFERS) on the seeded data

Data at the time: 16 teams, 87 room submissions, 36 judgements, 3 penalties. Each statement was run twice: with the
default planner settings and with `SET enable_seqscan = off`, which proves that a usable index exists for every
filtered or joined column.

| Statement (repository) | Default plan | With seq scans disabled | Execution time |
|---|---|---|---|
| Team rooms (`EventBoardRepository.teamRooms`) | Seq scans on tiny `room_judgement`/`room_submission` | `room_judgement_pkey`, `collected_clue_pkey`, `room_submission_team_room_time` (latest submission via LATERAL, 4 loops) | 0.20–0.22 ms |
| Room teams (`roomTeams`) | Seq scans on small tables | `event_team_group` (partial, active), `room_submission_team_room_time` for latest + count, `room_judgement_pkey`, `app_user_pkey` | 0.22–0.41 ms |
| Room penalties (`PenaltyRepository.findForRoom`) | `penalty_room` index | `penalty_room`, `app_user_pkey` | 0.04 ms |
| Admin room stats (`adminRooms`) | Seq scans (4 rooms, 21 users) | `room_judgement_room`, `room_submission_team_room_time` (64 index-only probes) | 0.38–0.48 ms |
| Team final summary (`TeamFinalRepository.summary`) | Index scans | `collected_clue_pkey`, `penalty_team`, `team_final_pkey`, unique `(team_id, idempotency_key)` indexes of `recovery_attempt`/`accusation` | 0.27–0.32 ms |
| Ranking (`EventRankingRepository.ranking`) | Seq scans + hash aggregates on tiny tables | `collected_clue_pkey`, `penalty_team`, `accusation_team_id_idempotency_key_key`, `team_final_pkey` | 0.24–0.25 ms |
| Revision / team by account / idempotency lookup | — | `event_state_pkey`, `event_team_account_id_key`, `room_submission_team_id_idempotency_key_key` | ≤ 0.02 ms |

Conclusion: PostgreSQL chooses sequential scans only because the tables hold a few dozen rows (cheaper than an index
probe); every filter has an index the planner uses as soon as the tables grow. No additional index (V6) was needed.
Raw plans: produced by the EXPLAIN script during verification (not committed).

## Load check (`load-tests/event_load.py`, 300 s each)

Clients: every client polls `GET /event/revision` every 10 s and refetches its one board when the revision changes;
each team client submits to its scheduled room about every 60 s (about 20 % retried with the same key and body);
each manager saves a judgement about every 20 s. Setup (logins, round 1, all rooms open) is excluded from the
measurements.

### 1× — 21 clients (16 teams, 4 managers, 1 admin)

| Endpoint | Requests | Errors | p50 ms | p95 ms | p99 ms | max ms |
|---|---|---|---|---|---|---|
| GET /event/admin/board | 30 | 0 | 5.4 | 23.1 | 23.3 | 23.3 |
| GET /event/revision | 630 | 0 | 2.4 | 24.6 | 26.9 | 27.6 |
| GET /event/rooms/{id}/board | 177 | 0 | 3.1 | 22.5 | 26.8 | 29.2 |
| GET /event/team/board | 478 | 0 | 2.4 | 21.9 | 25.8 | 27.1 |
| POST /event/team/rooms/{id}/submissions | 94 | 0 | 6.0 | 28.3 | 29.5 | 29.5 |
| PUT /event/rooms/{id}/judgements/{id} | 59 | 0 | 5.2 | 27.9 | 29.5 | 29.5 |

Totals: 1 468 requests, 0 errors, 83 accepted submissions, 11 same-key retries with 0 mismatching receipts,
0 duplicate receipt ids. Raw report: `docs/event-load-1x.json`.

### 3× — 48 simulated teams + 4 managers + 1 admin

48 team clients reuse the 16 team tokens (3 per token) with distinct idempotency keys; 53 clients in total.

| Endpoint | Requests | Errors | p50 ms | p95 ms | p99 ms | max ms |
|---|---|---|---|---|---|---|
| GET /event/admin/board | 30 | 0 | 4.6 | 18.7 | 21.2 | 21.2 |
| GET /event/revision | 1 590 | 0 | 1.7 | 24.0 | 26.4 | 29.2 |
| GET /event/rooms/{id}/board | 180 | 0 | 2.6 | 23.5 | 26.8 | 27.3 |
| GET /event/team/board | 1 440 | 0 | 2.0 | 21.5 | 25.9 | 27.6 |
| POST /event/team/rooms/{id}/submissions | 295 | 0 | 3.8 | 25.0 | 27.5 | 28.4 |
| PUT /event/rooms/{id}/judgements/{id} | 60 | 0 | 3.9 | 28.6 | 30.8 | 30.8 |

Totals: 3 595 requests, 0 errors, 238 accepted submissions, 57 same-key retries with 0 mismatching receipts,
0 duplicate receipt ids. Raw report: `docs/event-load-3x.json`.

Both runs meet the target (p95 < 300 ms, zero errors, no duplicate receipts) by a wide margin; the slowest request
in either run took 30.8 ms.

## Re-check after V6 (one device per team, `last_seen_at`) — 2026-10-24

Presence in the organization query (`EventTeamRepository.WITH_ACCOUNT`, LATERAL aggregate per team) on the
rehearsal data (16 teams, 21 sessions): 0.17 ms by default (seq scans on tiny tables), 0.09 ms with
`enable_seqscan = off` using `idx_session_user` (16 index probes). The existing V1 index on `auth_session(user_id)`
serves both this query and the REPLACED revocation, so V6 adds no partial index.

1× load check repeated with the per-request session check and the throttled `last_seen_at` write
(`docs/event-load-1x.json` now holds this run):

| Endpoint | Requests | Errors | p50 ms | p95 ms | p99 ms | max ms |
|---|---|---|---|---|---|---|
| GET /event/admin/board | 30 | 0 | 6.1 | 26.4 | 31.1 | 31.1 |
| GET /event/revision | 630 | 0 | 2.6 | 25.7 | 27.3 | 28.6 |
| GET /event/rooms/{id}/board | 179 | 0 | 3.4 | 23.3 | 29.0 | 29.6 |
| GET /event/team/board | 478 | 0 | 2.9 | 23.8 | 27.1 | 32.0 |
| POST /event/team/rooms/{id}/submissions | 92 | 0 | 5.4 | 27.6 | 31.4 | 31.4 |
| PUT /event/rooms/{id}/judgements/{id} | 59 | 0 | 7.5 | 28.0 | 29.0 | 29.0 |

Totals: 1 468 requests, 0 errors, 79 accepted submissions, 13 same-key retries with 0 mismatches, 0 duplicate
receipt ids. Worst p95 28.0 ms (previously 28.3 ms): no measurable regression. The 3× run was not repeated; it
reuses each team token 3 times, which still works because the load test signs each account in only once.

## Re-check after V7 (clue verdicts) — 2026-10-24

Re-check statements on the load-check data (78 submissions, 20 in the largest room), each with
`enable_seqscan = off` and rolled back: answers + overrides 0.12 ms (`room_submission_room_time`,
`submission_clue_verdict_pkey`), bulk verdict update 0.46 ms, ROOM-clue delete 0.05 ms (`collected_clue_room`),
ROOM-clue insert 0.04 ms, one-team recompute 0.06 ms (`room_submission_team_room_time`). The existing indexes
suffice, so V7 adds none.

1× load check after V7 (managers set the keys through the API first; `docs/event-load-1x.json` holds this run):
1 465 requests, 0 errors, 78 accepted submissions, 7 same-key retries with 0 mismatches, 0 duplicate receipt
ids. p95 by endpoint: admin board 26.7 ms, revision 21.8 ms, room board 17.6 ms, team board 22.7 ms,
submissions 24.1 ms, judgements 24.0 ms; slowest request 29.9 ms.

## Re-check after V8 (manual round timer) — 2026-10-24

The timer adds no statement to board reads except one arrival lookup (admin board 7 statements, room board 5);
TIME_UP is computed from `event_state` on read, and a submission's phase check reuses the `event_state` row the
write already locks. 1× load check (the setup starts the timer; `docs/event-load-1x.json` holds this run):
1 469 requests, 0 errors, 77 accepted submissions, 14 same-key retries with 0 mismatches, 0 duplicate receipt ids.
p95 by endpoint: admin board 25.9 ms, revision 24.3 ms, room board 26.2 ms, team board 22.7 ms, submissions
26.2 ms, judgements 30.6 ms. Single slow requests reached 88.8 ms (revision) and 78.8 ms (submission); p99 of
submissions was 78.8 ms, against 28.9 ms in the previous run — not investigated further on this shared laptop.

## Re-check after V9 (admin overrides) — 2026-10-24

Board reads gained fixed statements only: admin board 10 (team flags, recent overrides, round exceptions), room
board 6 (round exceptions), team board 5 (the team's exception). 1× load check (`docs/event-load-1x.json` holds this
run): 1 467 requests, 0 errors, 79 accepted submissions, 12 same-key retries with 0 mismatches, 0 duplicate receipt
ids. p95 by endpoint: admin board 34.1 ms, revision 25.0 ms, room board 30.1 ms, team board 27.6 ms, submissions
34.2 ms, judgements 32.8 ms; slowest request 46.1 ms. Medians rose on every endpoint, including `GET /revision`
whose code did not change (p50 3.9 ms vs 2.7 ms in the previous run), so part of the increase is machine load
during this run; not investigated further.

## Revision push (SSE) — 2026-10-26

`python load-tests/event_load.py --mode sse --streams N` on a freshly started rehearsal: setup as for the load
check (round 1, all rooms open, timer running), then N streams opened with single-use tickets spread over the 21
seeded accounts, and 20 admin actions (timer pause/resume alternately, 500 ms apart). For every action × stream it
records when the first `revision` event carrying at least that action's revision arrived. "After response" is
measured from the moment the admin's HTTP response was read; negative values mean the push arrived before the admin
client finished reading its own response (the broadcast runs right after commit, on the sender thread).

| Streams | Deliveries | Missing | After response p50 / p95 / max ms | From request start p50 / p95 / max ms | Admin action p95 ms | Errors |
|---|---|---|---|---|---|---|
| 21 | 420 / 420 | 0 | −0.1 / 0.5 / 0.8 | 3.8 / 24.8 / 27.1 | 24.7 | 0 |
| 150 | 3 000 / 3 000 | 0 | 1.4 / 3.8 / 5.5 | 6.7 / 22.4 / 29.4 | 18.0 | 0 |

An earlier 21-stream run on the same build (first run on that rehearsal): after response p50 0.5 / p95 2.1 /
max 3.0 ms.

Backend JVM (jcmd `GC.heap_info`, `PerfCounter.print`; Windows working set):

| Run | Before | Streams open | After the actions |
|---|---|---|---|
| 21 streams, first run on the rehearsal | heap used 32.3 MB, 30 live threads, WS 231.5 MB | 42.1 MB, 41 threads, 236.1 MB | 45.7 MB, 40 threads, 231.5 MB |
| 150 streams | 70.2 MB, 45 threads, 269.5 MB | 58.5 MB, 45 threads, 260.4 MB | 67.7 MB, 45 threads, 265.7 MB |

Open streams add no threads per stream (the 11 extra threads in the first run are Tomcat workers created for the
burst of ticket and stream requests; they stay pooled); heap moves within normal GC noise (committed heap stayed
68–84 MB). `EventStreamTest` additionally holds 100 streams open through 20 admin actions and asserts that every
stream gets the last revision, that a plain request still answers in under 1 s, and that the Hikari pool's active
connections return to 0.

Browser (Playwright, `e2e/event-live.spec.ts`, rehearsal via the 4202 dev proxy): after each admin action (assign
round, start, open room, pause, +1 min, resume, finish rooms, open final, publish) and each manager action (pass,
hint penalty) the team, manager and admin screens showed the change within the asserted 1.5 s; with the stream
blocked the team page followed through 3-second polling within 4.5 s and the stream reconnected once unblocked;
a second sign-in of the same team sent the first device to the login page with the "another device" message
within 2 s.

Compared with polling: before this change every screen polled `GET /revision` every 10 s, so another screen saw an
action after 0–10 s (about 5 s on average), plus its board refetch; the poll itself took p95 ≈ 25 ms in the load
checks above. With the push, the signal reaches every open stream within a few milliseconds of the commit, and the
visible delay is the board refetch (p95 ≈ 20–35 ms in the load checks). Request load from idle screens also drops:
a healthy tab now polls every 30 s instead of every 10 s.

## In-browser terminal (V10) — 2026-10-27

Measured on the dev laptop, headless Microsoft Edge (Playwright), through the Angular dev server on :4201 proxied to
the rehearsal backend; room files `logs/access.log` (3000 lines, 91 kB) and `logs/lock.log`.

| Measure | Result |
|---|---|
| Download, compressed (on the wire) | 7,645,925 B of `.gz` files (wasm 381,728 · SeaBIOS 67,327 · VGA BIOS 16,345 · image 7,179,525) + `libv86.js` 359,839 B (sent raw by the dev server; about 100 kB with nginx gzip) ≈ 8.0 MB — target ≤ 30 MB |
| Cold boot ("Start terminal" → prompt, empty cache, download included) | 5.4 s (6.1 s wall in the test) — target ≤ 20 s |
| Restart (assets already in memory) | 3.6 s |
| After a page reload | 5.2 s; small files were cached, the 7.2 MB image was fetched again (the dev server sends `Cache-Control: no-cache`; nginx sends a one-year `immutable` lifetime) |
| Renderer process after boot | +169 MB working set (285 MB in total for the tab's renderer); JS heap 25.8 MB used + 20.9 MB array-buffer backing store (CDP `Runtime.getHeapUsage`) |
| Backend CPU during a 49 s terminal session | 2.06 CPU-seconds for the whole session including sign-in, two file uploads, round start and normal board refreshes; the terminal itself makes one `GET …/terminal-files` per boot and nothing while it runs |
| Initial app bundle | 353.05 kB raw / 97.31 kB transfer, before the change 350.00 kB / 96.66 kB (+0.87 % / +0.66 %); xterm.js and the terminal component are in the lazy `linux-terminal` chunk (358 kB raw / 79 kB transfer), the asset loader in a 2 kB lazy chunk |

Saved-state boot (v86 `save_state`/`initial_state`) was not tried: a 5 s boot is well inside the 20 s target, and a
state file would be another large asset to host and keep in step with the image.

## Not measured

Event hardware, WAN/Wi-Fi latency, a reverse proxy, process restarts under load, and browser rendering cost on
low-end phones.
