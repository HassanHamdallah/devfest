# Investigation competition — operations guide

For organizers running the investigation competition (roles ADMIN, MANAGER, MEMBER). The coding contest (PARTICIPANT) is documented in the root README.

## Rules currently implemented (require organizer sign-off)

The server enforces the text shown under **Investigation rules** on the organizer page (source: `InvestigationScoring.RULES`). Submissions are refused until an organizer clicks **Confirm these rules**. In short:

- **Resubmission:** ordinary answers may be resubmitted until closing. Every accepted version is kept (database trigger forbids update/delete). Only the latest submission per team and challenge counts.
- **Final report:** one per team; submitting locks it permanently.
- **Deadline:** open when `opensAt <= serverTime < closesAt`, with time read on the server after transaction locks. An exact retry of a request that was already committed returns its original receipt, even after closing.
- **Totals:** sum of manual scores on each team's latest submissions; unscored work counts 0 and is shown as pending.
- **Ranking:** within each room only. Equal totals share rank (1, 1, 3). There is no further tie-break.
- **Publication:** results stay private until published. Any accepted submission, score, challenge, room or team change automatically unpublishes them.

Change these only in code, together with the rules text and tests.

## Preflight (day before and 30 minutes before start)

1. Sign in as ADMIN → **Rooms and teams**. **Organizer preflight** must show no issues. It flags rooms without an active manager or a final investigation, teams without an active representative or with an archived room, unconfirmed rules, and missing rooms or teams.
2. Each manager signs in and checks every challenge in their room: content, evidence links, maximum points, and opening and closing times *with timezone*. Content locks once any draft or answer exists, but times and state can still change.
3. Each representative signs in once, sees exactly one room, and confirms the challenges they can see.
4. `GET /api/v1/health` returns `{"status":"UP"}`.
5. Take a fresh backup (see below), and record where it is stored.

## Backups and restore

- Logical backup: `pg_dump -Fc` of the event database, taken before the start, hourly during the event, and after the final close. Store copies off the application host.
- Restore procedure, rehearsed locally: `infrastructure/verify-investigation-restore.ps1`. It dumps `devfest_test`, restores into a **newly created** database (never over an existing one), and compares the row count and content hash of every investigation table. The last run passed (`docs/investigation-restore-results.json`).
- **Not yet verified:** the hosting platform's own backup facility, and a restore on that platform. Do not call event backups verified until a restore there has succeeded.

## Incident guide

**Site down or 5xx errors.** Check `/api/v1/health` (503 means the database is unreachable) and the backend log. Restarting the backend is safe: accepted submissions, drafts and scores are in PostgreSQL, and an actual process kill and restart preserved them (`docs/investigation-restart-results.json`). Tell teams that drafts saved before the outage are on the server, and that any unsaved local edits remain in their browser.

**Deadline extension.** The room's manager (or the admin) edits the challenge **Closing time**. This takes effect immediately, is audited (`INV_CHALLENGE_*`) and unpublishes results. Announce the new time with its timezone. To close early, set state **Closed**.

**Team says "I submitted but have no receipt".** Ask them to open the challenge page. If a request was in flight, the page shows **Retry saved submission**. Retrying sends the same request key and returns the original receipt if the server committed it, so nothing is duplicated. Then reconcile against the submission history in the room, or the admin export (`investigation_submission`: id, team_id, received_at). A receipt ID that the team holds and the database lacks would indicate data loss; escalate and restore.

**Lost access.** Admin → account → **Reset password**. This revokes all sessions. Deactivating an account also revokes its sessions immediately. A team's work belongs to the team, not the account: assign a new representative only if the team has no saved work (otherwise the server refuses; archive the team and create a new one only as a last resort).

**Wrong score.** Edit it. Each change is versioned with the reviewer and time in **Score history**, and totals are recomputed from current scores, so there is never double counting. Results unpublish automatically; republish after review.

**Data recovery.** Restore the latest backup into a *new* database. Compare it with the admin export (**Export investigation records**) and the receipts that teams hold, then repoint the backend. Never restore over the live database.

## Audit and export

Audited actions include account creation, activation and password resets, manager assignment, room and team changes, challenge state, observed opening and closing (every 15 s), accepted submissions (ID only), score changes (old → new points), rule confirmation, publication and export. The audit log stores no passwords, tokens or answer text. The admin-only export (`GET /api/v1/investigation/export`) is a single consistent JSON snapshot that includes answer content: handle it as confidential.
