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
| `GET /event/team/board` | 4 |
| `GET /event/team/final` | 3–6 by stage |
| `GET /event/rooms/{id}/board` | 4 |
| `GET /event/admin/board` | 6 |
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

## Not measured

Event hardware, WAN/Wi-Fi latency, a reverse proxy, process restarts under load, and browser rendering cost on
low-end phones.
