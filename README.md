# DevFest · Code Arena

An independent Angular + Spring Boot programming-contest prototype adapted from selected TeamQora foundations. The complete participant journey has been exercised locally through a real Judge0 service. This is a **verified local MVP, not competition-ready hosting**. See [STATUS.md](STATUS.md) for evidence and remaining gates.

The same app also hosts the **investigation competition** (rooms, teams, room managers, challenges, final reports and manual scoring). Organizer setup, preflight, backups and incident handling: [docs/INVESTIGATION-OPERATIONS.md](docs/INVESTIGATION-OPERATIONS.md). Isolated rehearsal: `.\infrastructure\start-investigation-rehearsal.ps1` (ports 8082/4202, resets only `devfest_test`), then `npx playwright test --config playwright.investigation.config.ts` from `frontend`. For development seed accounts (1 manager, 2 members, 2 rooms, 2 teams), set `DEVFEST_INVESTIGATION_SEED_PASSWORD` with `DEVFEST_DEVELOPMENT=true`.

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

- `backend/.env`: DB credentials, JWT secret, administrator email/password, seed flag and provider URL.
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

For event hosting, configure `frontend/public/config.js`, `DEVFEST_ORIGINS` and HTTPS. `infrastructure/nginx.conf.example` is an unverified starting point; test its CSP with Monaco. Read architecture, attribution and the 14-day plan before distributing or operating the project.
