# DevFest platform

One Angular + Spring Boot application with two parts:

- **Investigation event** (ADMIN, MANAGER, MEMBER): 4 fixed rooms, 16 teams in groups A–D rotating over 4 rounds, automatic clue checking, manager judging and penalties, a final with recovery and accusation, and a published ranking. Rules: [docs/EVENT-RULES.md](docs/EVENT-RULES.md). Design: [docs/EVENT-ARCHITECTURE.md](docs/EVENT-ARCHITECTURE.md). Performance evidence: [docs/EVENT-PERFORMANCE.md](docs/EVENT-PERFORMANCE.md).
- **Legacy coding contest** (PARTICIPANT, Judge0, Monaco): kept in the codebase and hidden from event navigation; documented further below.

Both are **local prototypes, not deployed**. See [STATUS.md](STATUS.md) for what is verified.

## Event: run locally from an empty database

1. Start the dedicated local PostgreSQL cluster (first run creates it and generates ignored credentials). In a non-interactive shell run it in the background and wait for `ready to accept connections` in `.local/postgres.log`:
   ```powershell
   .\infrastructure\start-local-database.ps1
   ```
2. Edit the ignored `backend/.env` (generated on first run) for the event:
   ```properties
   DEVFEST_DEVELOPMENT=true
   DEVFEST_EVENT_SEED_PASSWORD=<choose a 12+ character password for the seeded event accounts>
   DEVFEST_EVENT_SEED_TEAMS=16
   DEVFEST_WORKER_ENABLED=false
   JUDGE0_URL=
   DEVFEST_SEED=false
   ```
   `DEVFEST_ADMIN_EMAIL` / `DEVFEST_ADMIN_PASSWORD` (already generated) create the organizer account once. The event seed runs only when `DEVFEST_EVENT_SEED_PASSWORD` is set **and** `DEVFEST_DEVELOPMENT=true` (the backend refuses to start if the seed password is set outside development), and only while no team exists. `DEVFEST_EVENT_SEED_TEAMS` (default 16, allowed 1–64) is the number of seeded teams and also becomes the planned team count. Remove the seed password after the first start.
3. Backend: `.\infrastructure\start-backend.ps1` (API on 8081; Flyway applies V1–V9).
4. Frontend: `cd frontend; npm ci; npm start` and open http://localhost:4201.

Seeded development accounts (all use the seed password; nothing secret is in the repository):

| Role | Accounts |
|---|---|
| Organizer (ADMIN) | the `DEVFEST_ADMIN_EMAIL` from `backend/.env` |
| Room managers | `manager.ai@event.test`, `manager.ps@event.test`, `manager.linux@event.test`, `manager.hw@event.test` |
| Teams (MEMBER) | `team01@event.test` … `teamNN@event.test` (NN = `DEVFEST_EVENT_SEED_TEAMS`) in balanced consecutive blocks; with 16: Team 01–04 group A, 05–08 B, 09–12 C, 13–16 D |

The seed also sets practice recovery challenges, six suspects, the correct suspect and a practice encrypted message; replace them on the Content page before the event. It sets **no clue keys**.

### Event-day round procedure (admin)

1. **Next round** — the round begins *Assigned*: teams see "Go to the … room. The timer starts soon." and
   cannot submit yet. Open the rooms as usual.
2. **Wait for 4 × Ready** — each room manager presses **All teams are here** on their board; the control
   screen's timer panel shows *Ready ✓* or *Waiting* per room.
3. **Start timer** — if rooms are still waiting, closed or without a clue key, you are asked to confirm.
4. **Pause / Resume / +1 min / +5 min / Adjust…** as needed; teams see "The round is paused." while paused.
5. **End round** (or let the timer reach 0) — teams see "Time is up for this round." and answers stop; adding
   time reopens them.
6. **Next round** — repeat. Changing "Round minutes" only affects rounds that begin later.

### Event: admin overrides

The **Overrides** page (admin only) fixes real-world problems: swap or move teams (with a preview of missed or
repeated rooms), send a team to another room for the current round, grant or revoke a clue, adjust a team's time,
disqualify or reinstate a team, take and download snapshots, and — in the red danger zone — reset the final, one
team, or the whole event (type RESET and your password). Every override needs a reason and is audited; every reset
saves a snapshot first. See [docs/EVENT-ADMIN-OVERRIDES.md](docs/EVENT-ADMIN-OVERRIDES.md).

### Event: clue keys (room managers, before each room opens)

- Each room manager sets their room's **clue key** on their room board (card "Clue key" → **Set clue key** → **Save key**). Until then the card shows "Not set — the room cannot open", and the admin's **Open room** is refused with "Set the clue key for this room before opening it." and a link to that room's board.
- Fixing the key later (until the final opens) re-checks every answer already submitted to the room; the card confirms "Saved. Re-checked N answers." and teams see the new result at their next refresh, marked "Updated after review".
- For a single answer, the manager can choose **Accept**, **Reject** or **Automatic** under the clue answer. Technical output is never checked automatically: Pass / Fail ("Technical output — your decision") is the manager's call.

### Event: Linux room terminal (room manager, before the event)

Rooms with **Terminal enabled** (admin, **Content** page; on by default for the Linux room only) give teams a Linux
terminal that runs inside their browser — no internet, nothing runs on the server (rules §18).

- On your room board, open **Terminal files** → **Add file**. Enter a **Path** relative to the team's home, e.g.
  `logs/lock.log` (teams find it as `~/logs/lock.log`), and paste the **Content** or pick a `.txt`/`.log` file from your
  computer (it is read in your browser). **Save file**. Limits: 20 files, 2 MB per file, 5 MB per room.
- Press **Test terminal** and try the commands teams will need (`grep`, `sort`, `uniq -c`, `awk`, `cut`, `wc -l`,
  `head`, `tail`, `less`). The admin control screen warns while an enabled terminal has no files.
- Files can be changed until the final opens; a team sees changes the next time its terminal starts (or after
  **Restart terminal**).
- **Server package (a whole fake server instead of text files).** Under **Server package** on the same card, upload
  **one ZIP** (up to 25 MB, 5000 entries, 20 MB unpacked). Paths in the ZIP count from `/` and are written without the
  leading slash: `srv/rami/logs/a.log` appears as `/srv/rami/logs/a.log`. An optional `devfest-manifest.csv` in the ZIP
  root (`path,owner,group,mode`, `/**` for a folder and everything below it) sets owners, groups and modes; defaults
  are `root:root`, `0644` files, `0755` folders. A package with any problem is refused with **every** problem listed
  and nothing is stored. While a package exists teams get it **instead of** the text files. Format, limits, the
  commands the image has and an example: [docs/LINUX-ROOM.md](docs/LINUX-ROOM.md). A room with a package also gets
  `file`, `base64`, `bzip2`, `zip` and **GNU find** (a static tools archive, `infrastructure/build-terminal-tools.py`),
  the manifest takes an optional fifth column `mtime` (`2026-03-14T09:26:00Z`) for exact file and folder dates, and the
  package card has an optional **Terminal clock** (so `find -mtime` gives the same result on any day).
- **Team form.** With the **Linux terminal activity** switched on for the room (admin, **Content** page; on by default
  for the Linux room), teams get a six-field form next to the terminal (PATH, USERNAME, FILE_SIZE, PATTERN, Encrypted
  message, Final clue) instead of the technical-output and clue-answer boxes. **Save** is repeatable, **Final submit**
  locks the form, and a saved form is submitted automatically when the round ends. The final answer is recorded as the
  room's submission and its Final clue is checked against your clue key; teams only ever see Saved or Submitted.
- **Organizer dashboard.** The room board links to it (manager of the room and admin only). Set the **answer keys**
  there (one accepted value per line for PATH, USERNAME, FILE_SIZE and PATTERN), then follow every active team's answers
  with a tick or cross per item, its score out of 15 (3 per correct parameter, 3 when the message and the clue are both
  right), award or deny single items by hand, export the table as CSV and open **Test terminal** with the current
  package. It refreshes every 5 seconds; the 15 points stay in this room and do not change the event's ranking.
- Teams receive the files only while their Linux round is Assigned, Running or Paused; at Time's up the terminal says
  "Time is up" and stops taking input. They still type the answer into the usual form (the **Use selection as
  technical output** button copies selected terminal text into it).

**Event-day tips:** ask teams to open the Linux room page on a **laptop** as soon as the round is *Assigned* — the page
downloads the terminal (about 8 MB) in the background, so it starts in a few seconds when the timer starts. Phones
work, but the 80-column terminal scrolls sideways there. Refreshing the page or leaving it ends the terminal session
(the browser asks first); files in it are not saved.

### Event: AI room quiz (room manager, before the event)

Rooms with the **AI quiz activity** (admin, **Content** page; on by default for the AI room only) give teams 45
multiple-choice questions and 3 code repairs instead of the answer form. Each team is evaluated once, when it submits
its last repair or when the round ends: a total at or above the room's **pass score** records a correct result and
gives the team your clue key as the room's clue; otherwise it gets nothing. Teams never see a score (rules §19).

- On your room board, open **AI quiz → Questions and repairs**. Add questions and repairs one by one, or prepare
  them in one JSON file and use **Import a JSON file** (you see how many items it holds and confirm; it replaces the
  room's current questions and repairs). **Export JSON** downloads the same format, e.g. as a backup.
- The **Plan** table shows questions and points per section; a warning lists what differs from 20 Easy, 15 Medium,
  6 Advanced, 3 Landmark, 1 Recent, 3 repairs and 100 points (warnings never block). Set **Multiple-choice minutes**
  (default 14): after that much running time, or when a team presses **Finish MCQs**, its repairs open. Set the
  **Pass score** (0–100 points, default 60) and press **Save settings**; it locks when the first team is evaluated.
- The room needs a **clue key** and at least one question: otherwise the admin board shows a blocking alert and the
  admin can start the round's timer only with **Start anyway**.
- Once any team has answered, a question or repair it answered can no longer be changed and an import is refused;
  everything is read-only after the final opens.
- During the round open **AI room results**: the pass score, scores per team and section, each repair, the total,
  each team's status (In progress / Passed / Not passed), whether its clue is granted, and how many teams got each
  question right. It refreshes every 5 seconds; **Export CSV** saves it. Teams only ever see "Response saved", then
  "Clue unlocked" with the clue, or "You did not reach the required score for this room's clue."
- Each result also appears on your room board as the team's submission, so you can still **Accept** or **Reject** it
  like any clue verdict. If the timer runs out by itself, unfinished teams are evaluated when the admin presses
  **End round** or moves to the next round.

**JSON format.** `format` is 1; `mcqMinutes` and `passScore` (0–100) are optional (the room keeps its values when
they are missing; a different `passScore` is refused once a team was evaluated). A question has `section` (`EASY`, `MEDIUM`, `ADVANCED`,
`LANDMARK` or `RECENT`), `prompt`, 2–6 `options`, `correctOption` (the 0-based index of the correct option), `points`
(1–10) and an optional `explanation` (organizers only). A repair has `title`, `instructions`, `points` (default 10)
and 4–6 `snippets` **in their correct order** (teams get them shuffled; indentation is kept). Example with two
questions and one repair:

```json
{
  "format": 1,
  "mcqMinutes": 14,
  "passScore": 60,
  "questions": [
    {
      "section": "EASY",
      "prompt": "Which of these is a supervised learning task?",
      "options": ["Clustering", "Classification", "Dimensionality reduction"],
      "correctOption": 1,
      "points": 1,
      "explanation": "Classification learns from labelled examples."
    },
    {
      "section": "MEDIUM",
      "prompt": "What does a pooling layer reduce?",
      "options": ["The learning rate", "The spatial size of feature maps", "The number of classes", "The batch size"],
      "correctOption": 1,
      "points": 2
    }
  ],
  "repairs": [
    {
      "title": "Fix the training loop",
      "instructions": "Put the lines in the order that makes the code work.",
      "points": 10,
      "snippets": ["for batch in loader:", "    optimizer.zero_grad()", "    loss = model(batch)", "    loss.backward()"]
    }
  ]
}
```


### Event: teams, sign-ins and devices

- **Planned teams.** On **Teams & accounts**, enter the number of teams taking part (1–64) and press **Save**. A group may then hold at most ceil(N / 4) active teams; the page and the control screen warn when the team count or group balance is off (rules 14 in `docs/EVENT-RULES.md`).
- **Generate teams and hand out sign-ins.** Under **Generate teams**, enter how many teams to create and a name prefix (default "Team") and confirm. You get teams "Team 01", … in the smallest groups first, with sign-in emails `team01@event.local`, … and random 16-character passwords. The passwords are shown **once**, in a dialog with **Copy all** and **Download CSV** (`event-team-credentials.csv`: team, group, email, password, built in the browser). Print or share each team's line privately, then delete the file. The server keeps only BCrypt hashes; a lost password is replaced with **Reset password** in the accounts table.
- **One device per team.** A team account can be signed in on one device; signing in elsewhere signs the earlier device out with a message. Organizer and room manager accounts are not limited.
- **Sign out team.** Each team row shows "Signed in" / "Not signed in" (seen within the last 2 minutes) and the last-seen time; reload the page for the latest presence. **Sign out team** (with confirmation) ends the team's session, for example when a team signed in on the wrong laptop; the device shows "An organizer signed your team out." and the team can sign in again.
- Team groups can be changed only before round 1 starts.

## Event: tests, rehearsal, browser journey and load check

```powershell
.\infrastructure\test-backend.ps1        # all backend tests on the disposable devfest_test database
cd backend; .\mvnw.cmd -DskipTests package; cd ..
cd frontend; npm run build; cd ..
pwsh -File .\infrastructure\start-event-rehearsal.ps1   # PowerShell 7; API 8082, UI 4202; resets ONLY event tables + app_user in devfest_test and seeds (no clue keys; add -PracticeKeys for a manual rehearsal)
cd frontend; npx playwright test --config playwright.event.config.ts; cd ..   # each test restarts the rehearsal backend on reset data (-ResetBackend); screenshots go to docs/event-*.png
python load-tests/event_load.py --scale 1 --seconds 300   # 21 clients; --scale 3 simulates 48 teams; refuses non-local URLs
pwsh -File .\infrastructure\stop-event-rehearsal.ps1    # stops only the recorded rehearsal processes
```

Rehearsal credentials are written to the ignored `.local/event-rehearsal.json`. Do not run backend tests while the rehearsal is running (both use `devfest_test`). Stop the rehearsal before rebuilding the JAR (Windows locks the running file).

---

# Legacy coding contest (Code Arena)

An independent Angular + Spring Boot programming-contest prototype adapted from selected TeamQora foundations. The complete participant journey has been exercised locally through a real Judge0 service. This is a **verified local MVP, not competition-ready hosting**.

Everything required by the application is inside this project. TeamQora was inspected read-only; its database, secrets, uploads, dependencies, Git history and product features were not copied. No symlinks or runtime references to TeamQora are required.

## Versions

| Component | Pinned or verified version |
|---|---|
| JDK | Java 25; tested with Temurin 25.0.4.1 |
| Spring Boot / Maven wrapper | 4.1.1 / 3.9.16 |
| PostgreSQL | 18; tested native Windows 18.6 |
| Node / npm | 24.15.0 / 11.12.1 |
| Angular runtime / CLI and builder | 22.1.4 / 22.1.6 |
| TypeScript / RxJS | 6.0.3 / 7.8.2 |
| Monaco / Playwright | 0.55.1 / 1.64.1 |
| JJWT | 0.12.6 |
| Python verification scripts | 3.11, standard library only |

`frontend/package-lock.json` locks dependencies. `docs/backend-dependencies.txt` records resolved backend versions. The optional Compose PostgreSQL 18.3 alternative has not been run because Docker's engine was unavailable. Native PostgreSQL 18.6 is the verified path.

Execution languages differ from the app's Java runtime:

| Language | Provider ID | Exact runtime name |
|---|---:|---|
| C++ | 105 | C++ (GCC 14.1.0) |
| Java | 91 | Java (JDK 17.0.6) |
| Python | 109 | Python (3.13.2) |

The adapter checks exact names. These versions passed synthetic execution checks; this does **not** certify their present security maintenance. The public Java runtime is old. Before the event, obtain patched toolchain images, update `ProblemData.Language` and frontend labels, and repeat execution tests. Never silently substitute compiler versions.

## Start on this Windows machine

```powershell
cd C:\Users\hassa\OneDrive\Desktop\devfest
.\infrastructure\start-local-database.ps1
```

This creates or starts **only** `.local/postgres` at `127.0.0.1:55432`, with `devfest` and a separate `devfest_test` database/test role. It never contacts the system PostgreSQL service or TeamQora. Existing generated configuration is preserved. Startup rejects database names other than `devfest` or `devfest_test` before Flyway runs.

First use generates random development credentials in ignored files:

- `backend/.env`: DB credentials, JWT secret, AI quiz shuffle secret (`DEVFEST_QUIZ_SHUFFLE_SECRET`, at least 32
  characters; `start-local-database.ps1` adds a random one once), administrator email/password, seed flag and provider
  URL. Without the shuffle secret a development backend (`DEVFEST_DEVELOPMENT=true`) uses a fixed fallback and logs a
  warning; any other backend refuses to start.
- `.local/database.json`: local cluster and test-role credentials.

Open `backend/.env` locally to obtain the administrator credentials. Do not commit or share it. The admin is created only when no administrator exists; changing bootstrap variables later does not reset its password. `DEVFEST_SEED=true` loads both examples only when there are no problems. Disable that flag after setup. A new contest starts tomorrow by default: set actual times in **Contest Settings**.

In a second PowerShell terminal:

```powershell
cd C:\Users\hassa\OneDrive\Desktop\devfest
.\infrastructure\start-backend.ps1
```

In a third terminal:

```powershell
cd C:\Users\hassa\OneDrive\Desktop\devfest\frontend
npm ci
npm start
```

Open [http://localhost:4201](http://localhost:4201). Backend API: **8081**; Angular proxies `/api` to it. PostgreSQL: **55432**. These differ from TeamQora's defaults.

On another machine, install the listed tools and pass `-PgBin 'C:\your\PostgreSQL\18\bin'` if necessary. Do not transfer `.local` as a source dependency. Avoid active OneDrive synchronization of live database files; event databases belong on dedicated storage with backups. The requested development files remain in this directory.

Stop foreground app terminals with Ctrl+C. Stop this database with `./infrastructure/stop-local-database.ps1`, never by stopping an unrelated system service.

## Docker database alternative

Copy `infrastructure/.env.example` to `infrastructure/.env`, supply distinct generated passwords, then:

```powershell
cd infrastructure
docker compose up -d database
docker compose --profile test up -d test-database
```

This uses `devfest` on 55432 and `devfest_test` on **55433**. Do not run it beside the native cluster on the same port. Configure the backend from `backend/.env.example`. For Docker tests set `DEVFEST_DB_URL=jdbc:postgresql://127.0.0.1:55433/devfest_test`, `DEVFEST_DB_USER=devfest_test`, its password and a test JWT secret, then run Maven. The supplied `test-backend.ps1` specifically uses the native cluster on 55432. Tests truncate only the dedicated test database; do not put valuable work there.

## Execution provider

Only the backend connects to Judge0. Development configuration:

```properties
DEVFEST_DEVELOPMENT=true
JUDGE0_URL=https://ce.judge0.com
JUDGE0_KEY=
JUDGE0_AUTH_HEADER=X-Auth-Token
JUDGE0_RAPIDAPI_HOST=
```

The public URL worked without a key for synthetic examples. Its availability, quota, deployed patch status and data retention are not guaranteed. Use only non-sensitive development programs. No paid subscription was created.

For an authenticated provider set its HTTPS URL and key. RapidAPI can use `JUDGE0_AUTH_HEADER=X-RapidAPI-Key` and the assigned `JUDGE0_RAPIDAPI_HOST`. Keys never belong in frontend configuration. Outside development, the app rejects the public endpoint and requires `JUDGE0_SECURITY_REVIEWED=true` for a private provider. This flag records an operator's review; it does not perform one. Follow [infrastructure/JUDGE0.md](infrastructure/JUDGE0.md) first.

With no provider, leave `JUDGE0_URL` empty and set `DEVFEST_WORKER_ENABLED=false` while configuring the contest. No mock verdicts are substituted. Enable workers after configuring a real provider; admin can rejudge failed submissions.

## Complete participant journey

1. Sign in as the configured administrator. Create a participant with a unique password.
2. Set start in the past and end in the future, with timezone offsets. Review both seeded problems and hidden tests.
3. Sign out and sign in as the participant. Open **Problems → A · Two Numbers**.
4. Select Python; enter `print(sum(map(int, input().split())))`. Wait for **Saved**, refresh and verify recovery.
5. Enter `4 9` as custom input, click **Run**, and expect real output `13`.
6. Click **Submit solution**. A durable ID arrives before evaluation; expect **ACCEPTED**, 5/5. Further edits do not change submitted source.
7. Inspect **My Submissions** and **Scoreboard**.
8. Participant admin requests are forbidden; another participant's submission ID returns 404. Tests cover these cases.
9. Once the contest closes, new submissions are rejected. Retrying an already accepted request key returns its existing ID; queued work may finish after closing.

`python tools/verify-real-execution.py` automates synthetic real Run, both problems in three languages, failure cases and rejudge. It opens the development contest for eight hours and creates a labeled participant. **Never run it against event data.** Its credentials are stored only in `.local/verification-account.json`.

## Builds and tests

```powershell
# Dedicated native test DB must be running.
.\infrastructure\test-backend.ps1
cd backend
.\mvnw.cmd -DskipTests package
cd ..\frontend
npm ci
npm run build
# Apps and provider must be running; verification helper creates the test account.
npm test
```

Browser tests use installed Microsoft Edge headlessly; adjust `playwright.config.ts` for another installed browser. They execute real code and read only ignored local credentials. `npm test` runs the browser journeys; no separate Angular unit suite is claimed. Backend durability tests use controlled synthetic outcomes, distinct from the real-provider evidence.

See [load-tests/README.md](load-tests/README.md) for separate 80-user browse, draft, poll, Run and Submit scenarios. Do not stress the public demo provider.

## Troubleshooting

- **55432 occupied:** identify the owner; never reuse an unidentified DB. Stop only an earlier DevFest cluster or consistently change its port/configuration.
- **JWT/DB configuration error:** fill `backend/.env`, launch from `backend`, and remove stale environment variables overriding it. No production JWT default exists.
- **Bootstrap password changed but login fails:** existing accounts are preserved. Retain original administrator credentials; use admin reset for participants.
- **Problems unavailable:** the contest has not started. Set server times with an explicit timezone.
- **SYSTEM ERROR:** check provider access, quotas, runtime names and supported limits. Fix the provider, then rejudge. Participants receive sanitized messages.
- **Monaco unavailable:** run `npm ci` and check `/monaco/vs/loader.js`. Assets are local, with no CDN dependency.
- **Draft conflict:** choose explicitly between local and server versions; loading server first saves a local backup. Failed saves keep edits.
- **401:** expired/replayed/revoked session or inactive account; sign in again.
- **429:** wait for the window or queued jobs to clear. At most three outstanding evaluations per participant.
- **Docker unavailable:** use the tested native PostgreSQL setup. Do not place Judge0 workers on the application host as a shortcut.

For event hosting, configure `frontend/public/config.js`, `DEVFEST_ORIGINS` and HTTPS. Screens learn about changes through a Server-Sent Events stream (`GET /api/v1/event/stream`): a reverse proxy in front of the app must disable response buffering for that path and allow long-lived connections (nginx: `proxy_buffering off;` and `proxy_read_timeout` of at least 35 minutes; see the dedicated location in `infrastructure/nginx.conf.example`). If the stream cannot connect, pages fall back to polling every 3 seconds. `infrastructure/nginx.conf.example` is an unverified starting point; test its CSP with Monaco. Read architecture, attribution and the 14-day plan before distributing or operating the project.
