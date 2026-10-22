# Fourteen-day plan — 77 hours

A solo plan at **5–6 hours/day**, not a claim that 77 hours were already spent. Much of the code now exists; your remaining work is understanding it, configuring event infrastructure, verifying that environment and rehearsing.

| Day | Hours | Dependency | Measurable outcome | Current evidence / remaining work |
|---|---:|---|---|---|
| 1 | 5 | Source access | Independent project and reuse inventory | Implemented; read audit and trace one request |
| 2 | 6 | Provider access | Real execution and hosting choice | Public spike passed; vetted execution hosting required |
| 3 | 5 | Separate PostgreSQL | Schema/config/admin/participant accounts | Implemented locally; reproduce README |
| 4 | 6 | Auth/schema | Login/refresh/revocation/ownership | Tests passed; review event HTTPS/CSP |
| 5 | 5 | Problem model | Two reviewed problems and tests | Seed problems and all-language references implemented; review event content |
| 6 | 6 | Problem API | Monaco and conflict-aware draft recovery | Browser tests passed; rehearse shared devices/multiple tabs |
| 7 | 6 | Execution host | Real Run in pinned runtimes with limits | Public examples passed; repeat on patched event runtimes |
| 8 | 6 | Queue schema | Durable Submit, duplicate/timing/recovery handling | Integration tests passed; rehearse process failures |
| 9 | 5 | Vertical slice | Essential administration | Implemented; JSON test editor intentionally basic |
| 10 | 5 | Evaluations | Scoring, history, rejudge and CSV | Implemented; review tie/version policies |
| 11 | 6 | Vetted event provider | 80-user individual scenarios, preserved IDs | Local API measurements available; execution bursts outstanding |
| 12 | 5 | Load evidence | Fix bottlenecks, outage/restart and restore drills | Reserved for resilience/debugging |
| 13 | 6 | Stable essentials | Full rehearsal on event network/devices | Outstanding |
| 14 | 5 | Rehearsal | Fixes, frozen versions and recovery checklist | Outstanding; no scope expansion |

Total **77 hours**. Days 12–14 reserve 16 hours for integration, debugging and rehearsal. Do not spend that buffer on dashboard polish.

## Remaining effort

Budget **30–45 hours** from this handoff to understand/reproduce the implementation, obtain and verify a suitable execution service, update runtime pins, run 80-way execution and mixed load, exercise failures/backups and rehearse. This assumes available maintained hosting or competent Linux operations support. Building and securing a new sandbox platform alone can add **20–40+ hours**, exceeding the 70–84-hour budget. Current evidence cannot guarantee that infrastructure work fits the two-week deadline.

Essentials: auth, problem viewing, editor/real Run, durable Submit/hidden evaluation, draft recovery, essential admin, isolation and verification. Code works locally; event isolation and capacity remain gates.

Optional: friendlier test editor, date pickers, advanced filters, audit viewer, theme switch, scoreboard auto-refresh, password-change screen (API exists), emailed reset, visual polish.

## Fallback

Keep one contest, two reviewed problems, basic admin forms and manual scoreboard refresh. Drop optional polish/filtering before weakening authorization or durability. Reduce languages only through an explicit event decision, never an unnoticed runtime substitution. If secure execution infrastructure is unavailable, use only synthetic local demonstrations and postpone automatic student-code judging. Do not substitute mock results or present unsafe execution as ready.

Before the event: repeat the 12 completion criteria on the actual topology; pass execution bursts and mixed load; verify operator access, backup restoration and incident handling; rehearse both participant and organizer instructions.
