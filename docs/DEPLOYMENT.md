# Deploying DevFest to one server (beginner guide for Windows)

You will put the whole event on **one Ubuntu server** (a DigitalOcean "Droplet", 2 GB RAM, 1 vCPU). You work from your
Windows laptop in **PowerShell**; every server command is typed into an **SSH** window (a remote terminal on the server).
No Docker.

Words used below:

- **Laptop** = your Windows PC. **Server** = the Droplet. A command marked `PS>` runs in PowerShell on the laptop, a command
  marked `server#` runs on the server over SSH.
- `SERVER_IP` = the Droplet's IP address. `YOUR_DOMAIN` = the site name, for example `devfest.example.com`.
- In the repository the domain is the placeholder `REPLACE_DOMAIN` (see "Where REPLACE_DOMAIN goes" below).

What ends up on the server:

| Piece | Where | Notes |
|---|---|---|
| nginx | ports 80/443 | serves the website, proxies `/api/` to the backend, handles HTTPS |
| Backend (Spring Boot, Java 25) | `127.0.0.1:8081`, service `devfest-backend`, user `devfest` | not reachable from outside |
| PostgreSQL 18 | `127.0.0.1:5432` | not reachable from outside |
| Files | `/srv/devfest/` | `backend/` (`.env`, `devfest.jar` link), `frontend/` (`current` link), `releases/`, `backups/`, `bin/` (scripts) |
| Firewall (ufw) | only 22, 80, 443 open | |

## Where REPLACE_DOMAIN goes

The files in `infrastructure/deploy/` keep the placeholder `REPLACE_DOMAIN`. You type your domain **once**, when
`setup-server.sh` asks for it, and the script fills it in for you in these places:

1. `nginx-site.conf` and `nginx-site-http-only.conf` (installed copy: `/etc/nginx/sites-available/devfest`): `server_name`, redirect and certificate paths.
2. `env.production.example` -> `/srv/devfest/backend/.env`: `DEVFEST_ORIGINS=https://YOUR_DOMAIN`.
3. `/srv/devfest/bin/deploy.conf`: remembered by the deploy scripts for their HTTPS check.

You also type it yourself in the `certbot` command (section 4) and in the DNS record (section 1). If you ever change the domain,
edit `DEVFEST_ORIGINS` in `.env`, then run `setup-server.sh` again after deleting `/srv/devfest/bin/deploy.conf`.

---

## 1. Create the Droplet and your SSH key

**1a. Make an SSH key on the laptop** (a key pair replaces passwords: the private half stays on your laptop).

```powershell
PS> ssh-keygen -t ed25519 -C "devfest-laptop"
```
*Creates a key pair in `C:\Users\<you>\.ssh\`. Press Enter for the default file; a passphrase is optional but good.*

```powershell
PS> Get-Content $env:USERPROFILE\.ssh\id_ed25519.pub
```
*Prints the PUBLIC key (one line starting with `ssh-ed25519`). Copy it. Never share the file without `.pub`.*

**1b. Create the Droplet** at cloud.digitalocean.com -> Create -> Droplets:

- Region: closest to the venue. Image: **Ubuntu, the latest LTS**.
- Size: Basic, regular, **2 GB RAM / 1 vCPU**.
- Authentication: **SSH Key** -> New SSH Key -> paste the line from 1a.
- Create. Copy the Droplet's IP address (`SERVER_IP`).

**1c. Point the domain at it.** In your domain provider's DNS page add an **A record**: name = your (sub)domain, value = `SERVER_IP`.
Wait until this works on the laptop (it must print `SERVER_IP`):

```powershell
PS> nslookup YOUR_DOMAIN
```
*Asks DNS which address the name points to.*

**1d. First login.**

```powershell
PS> ssh root@SERVER_IP
```
*Opens a terminal on the server. Answer `yes` to the fingerprint question the first time. `exit` leaves.*

---

## 2. First-time server setup

**2a. Upload the deploy folder** (from the repository folder on the laptop):

```powershell
PS> cd C:\path\to\devfest
PS> scp -r infrastructure\deploy root@SERVER_IP:/root/devfest-deploy
```
*Copies the folder `infrastructure\deploy` to `/root/devfest-deploy` on the server.*

**2b. Check the files kept Unix line endings** (the #1 beginner trap: Windows line endings break shell scripts):

```bash
server# cd /root/devfest-deploy
server# grep -lU $'\r' *.sh *.service *.timer *.conf
```
*Lists files that contain Windows line breaks. It must print nothing.* If it lists files, fix them with
`sed -i 's/\r$//' *.sh *.service *.timer *.conf` (*removes the extra character at each line end*). The repository's
`.gitattributes` forces LF for `*.sh`, `*.service`, `*.timer` and `*.conf`; on the laptop you can check with
`git ls-files --eol infrastructure/deploy` (every line should say `w/lf`).

**2c. Run the setup:**

```bash
server# bash setup-server.sh
```
*Installs and configures everything. It takes 5-10 minutes and asks you:*

1. *Your domain* (`YOUR_DOMAIN`).
2. *A database password* (press Enter to let it generate one; it is stored only in `/srv/devfest/backend/.env`).
3. *The first admin's email and password* (12+ characters). You change this password after the first sign-in.

What it does, in order: 2 GB swap, nginx + certbot + ufw + fail2ban + unattended-upgrades, PostgreSQL 18 from the official
PostgreSQL repository, Java 25 (Ubuntu's package if there is one, otherwise Eclipse Temurin), the `devfest` user, the
folders, the database role and database, `.env` (`chmod 600`, owner `devfest`), the systemd services, the nginx site
(HTTP-only for now), the firewall (22, 80, 443) and the backup timer. **Last**, it turns off SSH password login, but only
if it finds a key in `authorized_keys` (the key from step 1); otherwise it warns and leaves passwords on.

It is safe to run again; it skips what exists and never asks for the passwords twice.

**2d. Prove SSH still works before you close anything:** open a **second** PowerShell window and run `ssh root@SERVER_IP`.
If it logs in, all is well. Only then close the first window.

**2e. Quick look:**

```bash
server# systemctl is-active nginx postgresql fail2ban
server# ufw status
server# java -version
server# ls -l /srv/devfest/backend/.env
```
*Each service should say `active`; the firewall lists 22, 80, 443; Java says 25; `.env` shows `-rw------- devfest devfest`.*

---

## 3. First deploy

**3a. Build the release on the laptop** (builds the frontend and the backend, then makes one zip):

```powershell
PS> cd C:\path\to\devfest
PS> powershell -ExecutionPolicy Bypass -File infrastructure\deploy\build-release.ps1
```
*Runs `ng build` for production, `mvnw package` (with the backend tests) and zips the result into
`release\devfest-<date>-<git hash>.zip`.* If the tests need a database you do not have running, add `-SkipTests`
(only when you already ran the tests).

**3b. Upload the zip:**

```powershell
PS> scp release\devfest-20261112-ab12cd3.zip root@SERVER_IP:/srv/devfest/releases/
```
*Copies the file into the server's releases folder (use your zip's real name).*

**3c. Install it:**

```bash
server# /srv/devfest/bin/deploy.sh /srv/devfest/releases/devfest-20261112-ab12cd3.zip
```
*Unpacks the zip, backs up the database, switches the jar and the website files, restarts the backend and waits until
`/api/v1/health` says `UP`. It ends with `DEPLOY OK` and prints the health answer.*

**3d. Check by hand:**

```bash
server# curl -s http://127.0.0.1:8081/api/v1/health
```
*Asks the backend directly. Expected: `{"status":"UP"}`.*

---

## 4. HTTPS (free certificate from Let's Encrypt)

The DNS record from step 1c must already point at the server.

```bash
server# certbot certonly --webroot -w /var/www/certbot -d YOUR_DOMAIN --agree-tos -m YOUR_EMAIL --no-eff-email
```
*Asks Let's Encrypt for a certificate; it proves you own the domain by placing a file that nginx serves on port 80.
Replace `YOUR_EMAIL` with a real address (expiry warnings go there).*

```bash
server# bash /root/devfest-deploy/setup-server.sh
```
*Run the setup again. It finds the certificate and switches nginx from the temporary HTTP-only site to the full HTTPS site.*

```bash
server# curl -sI https://YOUR_DOMAIN | head -n 5
server# certbot renew --dry-run
```
*The first shows `HTTP/2 200` with the security headers; the second rehearses the automatic renewal (a timer renews
by itself; nginx reloads after each renewal).*

Then open `https://YOUR_DOMAIN` in a browser. Why this is safe: login tokens are kept in the browser's session storage and
sent in an `Authorization` header (there are **no cookies**), the site forces HTTPS with HSTS, and nginx tells the backend
the real client IP and `https` through `X-Forwarded-*` headers (the backend trusts them only from the local nginx).

---

## 5. Updating the site (build, upload, deploy, rollback)

```powershell
PS> powershell -ExecutionPolicy Bypass -File infrastructure\deploy\build-release.ps1
PS> scp release\devfest-NEWNAME.zip root@SERVER_IP:/srv/devfest/releases/
PS> ssh root@SERVER_IP "/srv/devfest/bin/deploy.sh /srv/devfest/releases/devfest-NEWNAME.zip"
```
*Build, copy, and run the install on the server in one go. A database backup (`predeploy-...dump`) is taken first. The
previous release stays in `/srv/devfest/releases/`.*

**Something is wrong after a deploy -> go back:**

```bash
server# /srv/devfest/bin/rollback.sh
```
*Puts the previous jar and website files back and restarts. Running it again returns to the newer release.*

> **WARNING: `rollback.sh` restores the CODE only, never the database.**
> If the release you are leaving added a database migration (a new file in `backend/src/main/resources/db/migration/`),
> the old jar can fail to start or misread the newer tables. In that case do **both, in this order**:
>
> 1. `server# /srv/devfest/bin/rollback.sh`  *(old jar and website back, restarted)*
> 2. `server# /srv/devfest/bin/restore.sh $(cat /srv/devfest/releases/NEWRELEASE/PREDEPLOY_BACKUP) --live`
>    *(the database as it was just before that deploy; `NEWRELEASE` is the name of the release you rolled back from,
>    for example `devfest-20261112-ab12cd3`. `rollback.sh` also prints this path.)*
>
> Step 2 throws away everything entered after that deploy (scores, submissions), so it is for emergencies only.
> Not sure whether the release had a migration? Ask whoever built it, or look in `journalctl -u devfest-backend` for
> Flyway's "Migrating schema" lines from that start.

The scripts themselves: after changing anything in `infrastructure/deploy/`, upload the folder again (step 2a) and run
`bash /root/devfest-deploy/setup-server.sh`.

---

## 6. Backups and restore

A systemd timer runs `backup.sh` **every 10 minutes** (`pg_dump -Fc`, newest 144 kept = 24 hours; also one `latest.dump`
link). Deploys make their own `predeploy-*` backups (newest 20 kept). Files are in `/srv/devfest/backups/`.

```bash
server# ls -lh /srv/devfest/backups | tail
server# systemctl list-timers devfest-backup.timer
server# /srv/devfest/bin/backup.sh
```
*Lists backups; shows when the timer runs next; makes one backup right now.*

**Switch to daily outside the event** (03:00 every day):

```bash
server# mkdir -p /etc/systemd/system/devfest-backup.timer.d
server# printf '[Timer]\nOnCalendar=\nOnCalendar=*-*-* 03:00:00\n' > /etc/systemd/system/devfest-backup.timer.d/schedule.conf
server# systemctl daemon-reload && systemctl restart devfest-backup.timer
```
*Overrides the schedule (first empty line clears the 10-minute one).* **Back to every 10 minutes for the event:**
`rm -r /etc/systemd/system/devfest-backup.timer.d && systemctl daemon-reload && systemctl restart devfest-backup.timer`.

**Copy backups to your laptop** (a backup that lives only on the server dies with the server):

```powershell
PS> mkdir C:\devfest-backups
PS> scp root@SERVER_IP:/srv/devfest/backups/latest.dump C:\devfest-backups\devfest-latest.dump
PS> scp "root@SERVER_IP:/srv/devfest/backups/devfest-2*.dump" C:\devfest-backups\
```
*The first copies the newest backup; the second copies all of them. Do it before and after the event, and now and then
during it.*

**During the event: copy the newest backups to the laptop every hour.** Keep one PowerShell window for this and run the
same command at the start of each hour (the dump names carry UTC time):

```powershell
PS> $h=(Get-Date).ToUniversalTime().AddHours(-1).ToString("yyyyMMddTHH"); scp "root@SERVER_IP:/srv/devfest/backups/devfest-$h*.dump" "root@SERVER_IP:/srv/devfest/backups/latest.dump" C:\devfest-backups```
*Copies the six backups of the previous hour, plus `latest.dump` (the newest one, overwritten each time on the laptop).
If the server dies you lose at most about 10 minutes of data and still have every hour on the laptop.*

**Practice restore (safe, the live site is not touched):**

```bash
server# /srv/devfest/bin/restore.sh /srv/devfest/backups/latest.dump
```
*Asks you to type `yes`, restores into a scratch database `devfest_restore_check`, prints table row counts, and deletes it.
`RESTORE CHECK OK` means the backup is usable.*

**Restore live (replaces the event data; only for a real disaster or a bad deploy):**

```bash
server# /srv/devfest/bin/restore.sh /srv/devfest/backups/devfest-20261112T101500Z.dump --live
```
*Asks you to type `RESTORE LIVE`. It restores into a fresh database first; only if that worked does it stop the backend,
rename the old database to `devfest_before_restore_<time>`, put the restored one in place as `devfest`, and start the
backend. The old data is not deleted.* Drop it later with `sudo -u postgres dropdb devfest_before_restore_<time>`.

*Restore went wrong:* the script says so and leaves the live database untouched, or (if only the final start failed) keeps the
old one under `devfest_before_restore_<time>`. To switch back by hand:
`systemctl stop devfest-backend`, then in `sudo -u postgres psql` run `ALTER DATABASE devfest RENAME TO devfest_broken;` and
`ALTER DATABASE devfest_before_restore_<time> RENAME TO devfest;`, then `systemctl start devfest-backend`.

**To restore from a dump that is on your laptop:** `scp C:\devfest-backups\file.dump root@SERVER_IP:/srv/devfest/backups/`, then use `restore.sh` on it.

---

## 7. Reading logs when something fails

```bash
server# journalctl -u devfest-backend -n 100 --no-pager
server# journalctl -u devfest-backend -f
server# journalctl -u devfest-backend --since "10 minutes ago" --no-pager
```
*The backend's log: last 100 lines / follow live (Ctrl+C stops) / the last 10 minutes.*

```bash
server# systemctl status devfest-backend
server# tail -n 50 /var/log/nginx/error.log
server# tail -n 50 /var/log/nginx/access.log
server# nginx -t
server# journalctl -u devfest-backup -n 20 --no-pager
server# journalctl -u postgresql@18-main -n 50 --no-pager
```
*Service state; nginx errors; nginx requests; checks the nginx config for mistakes; the last backup runs; PostgreSQL's log.*

| Symptom | Likely cause and fix |
|---|---|
| Browser shows **502 Bad Gateway** | Backend is down or still starting. `systemctl status devfest-backend`, then `journalctl -u devfest-backend -n 100`. |
| Backend log: `Set a unique DEVFEST_JWT_SECRET` / `bootstrap administrator password` | `.env` still has a `REPLACE_` value. `nano /srv/devfest/backend/.env`, then `systemctl restart devfest-backend`. |
| Backend log: `password authentication failed` | The password in `.env` differs from the database role's. Re-run `setup-server.sh` after deleting `.env` (it sets a new password on the role and writes a new `.env`). |
| Backend log: `refuses a database other than devfest` | `DEVFEST_DB_URL` must end in `/devfest`. |
| **413 Request Entity Too Large** on an upload | File is bigger than the limit (ZIP 25 MB, terminal file 2 MB, content JSON 2 MB). |
| Everybody gets **429 Too many requests** | The venue shares one IP; nginx allows 200 requests/second per IP with a burst of 400, and login is limited per IP and per account in the backend. |
| `certbot` fails | DNS does not point at the server yet, or port 80 is blocked (`ufw status`). |
| The live board never updates | Check `/var/log/nginx/devfest-stream.log` and that the site is on HTTPS (HTTP/2). |
| Disk full | `df -h`; old releases are pruned by `deploy.sh`, backups by `backup.sh`; `journalctl --vacuum-size=200M`. |

---

## 8. First-run checklist (do this a few days before the event)

- [ ] **Health is UP:** `curl -s https://YOUR_DOMAIN/api/v1/health` returns `{"status":"UP"}`.
- [ ] **Admin sign-in:** open `https://YOUR_DOMAIN` (the page asks for *Email* and *Password*, button **Sign in**) with the admin email and password you gave `setup-server.sh`. You land on **Event control**.
- [ ] **Change the admin password.** The app has no screen for the admin's own password (the *Reset password* button on **Teams & accounts** is only for manager and team accounts), so use the API from PowerShell on the laptop:

      ```powershell
      PS> $b="https://YOUR_DOMAIN/api/v1"; $email="ADMIN_EMAIL"
      PS> $cur=Read-Host "Current password"; $new=Read-Host "New password (12-72 characters)"
      PS> $t=Invoke-RestMethod -Method Post "$b/auth/login" -ContentType application/json -Body (@{email=$email;password=$cur}|ConvertTo-Json)
      PS> Invoke-RestMethod -Method Post "$b/auth/password" -Headers @{Authorization="Bearer $($t.accessToken)"} -ContentType application/json -Body (@{currentPassword=$cur;newPassword=$new}|ConvertTo-Json)
      ```
      *Logs in, then changes the password (no error = done; sign in again with the new one).* Then remove the bootstrap lines from `.env`
      (they are only used while no admin exists): `sed -i '/^DEVFEST_ADMIN_/d' /srv/devfest/backend/.env && systemctl restart devfest-backend`.
- [ ] **Switch the room activities on** (admin, sidebar **Content**, section **Room clues**, one block per room). The imports below stay disabled until these are ticked:
      **AI** room: tick **AI mission activity**. **Linux** room: tick **Terminal enabled** and **Linux terminal activity**.
      **Problem solving** room: tick **Contest results activity** if that room uses the Codeforces contest.
- [ ] **Import the Crypto (final) content:** admin, **Content**, section **Final content file**: choose `docs/final/crypto-content.json`, then **Import this file**. It says *Not imported* until this is done, and the Final cannot open without it.
- [ ] **Import the Sentinel (AI room) content:** sidebar **AI room** (the room board), link **SENTINEL page**, section **Mission file**, **Import a JSON file**: choose `docs/ai-room/sentinel-content.json`. Optionally set **Pass score** and **Save pass score**. (As the AI room's manager or as admin.)
- [ ] **Upload the Linux server package ZIP:** sidebar **Linux room**, section **Terminal files**, part **Server package**, **Upload package (.zip)**. Rules in `docs/LINUX-ROOM.md`. This is the 25 MB upload nginx now allows. (The *Terminal files* section only appears once **Terminal enabled** is ticked, see above.)
- [ ] **Generate the teams:** admin, **Teams & accounts**. In **Groups** set *Planned teams* and press **Save**; then in **Teams** use **Bulk generation** (*Number of teams*, *Name prefix*) and press **Generate teams**, then **Confirm**. The sign-ins are shown to copy or **Download CSV**.
- [ ] **One team plays one room** end to end on a phone/laptop that is not yours (this tests the live updates over HTTPS too).
- [ ] **Run a backup, then a restore check** (section 6): `backup.sh`, then `restore.sh /srv/devfest/backups/latest.dump` -> `RESTORE CHECK OK`. Copy a backup to your laptop with `scp`.
- [ ] **Take a Droplet snapshot:** DigitalOcean -> your Droplet -> Snapshots -> Take snapshot (run `backup.sh` first; the snapshot is a copy of the whole disk, so you can rebuild the server fast).
- [ ] **Reset test data** before the event: admin, **Overrides**, **Danger zone**, **Reset whole event…** (type `RESET` and your admin password), or restore a clean backup, so rehearsal results do not leak into the real event.

---

## 9. The day before the event

Do this the day before, not the morning of: a reboot or update that goes wrong needs time to fix.

```bash
server# apt-get update && apt-get -y upgrade
```
*Installs every pending update (the server also installs security updates by itself, but never reboots on its own).*

```bash
server# ls /var/run/reboot-required
server# reboot
```
*Shows whether a reboot is needed (a "No such file" answer means not needed; reboot once anyway). `reboot` restarts the server
and drops your SSH window. Wait about two minutes, then log in again with `ssh root@SERVER_IP`.*

```bash
server# systemctl is-active nginx postgresql devfest-backend fail2ban
server# curl -s https://YOUR_DOMAIN/api/v1/health
server# systemctl list-timers devfest-backup.timer
server# ls -lt /srv/devfest/backups | head -n 3
server# ufw status | head -n 6 ; free -h
```
*Every service should say `active`; health must be `{"status":"UP"}`; the backup timer must show a next run and the newest
backup must be recent; the firewall shows 22, 80, 443; `free -h` shows the 2 GB swap.*

If a service is not `active` after the reboot, read its log (section 7) and fix it now, while there is time. Finish by taking
a fresh Droplet snapshot (section 8) and copying the newest backup to the laptop (section 6).
