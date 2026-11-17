# Deploying DevFest to one server (beginner guide for Windows)

You will put the whole event on **one Ubuntu server**. This guide is written for an **Oracle Cloud Always Free** Ampere A1 machine
(Ubuntu 26.04 LTS, arm64, 2 OCPU, 12 GB RAM, login user `ubuntu`); the same steps work on any Ubuntu LTS server, amd64 or arm64
(on another provider, log in as the user that provider gives you and ignore the Oracle parts). You work from your
Windows laptop in **PowerShell**; every server command is typed into an **SSH** window (a remote terminal on the server).
No Docker.

Words used below:

- **Laptop** = your Windows PC. **Server** = the Oracle instance. A command marked `PS>` runs in PowerShell on the laptop, a
  command marked `server$` runs on the server over SSH.
- `SERVER_IP` = the instance's **public** IP address. `KEY` = the path of the private SSH key file Oracle gave you
  (for example `C:\Users\you\.ssh\oracle.key`). `REPLACE_DOMAIN` = the site name once you have one (for example
  `devfest.example.com`); until then the site has no name and is reached by IP.
- The login user is `ubuntu`, not root. Server commands that change the system start with `sudo`.

What ends up on the server:

| Piece | Where | Notes |
|---|---|---|
| nginx | ports 80 (and 443 after HTTPS) | serves the website, proxies `/api/` to the backend |
| Backend (Spring Boot, Java 25) | `127.0.0.1:8081`, service `devfest-backend`, user `devfest` | not reachable from outside; heap `-Xmx2g`, limit `MemoryMax=3G` (see "Memory size" below) |
| PostgreSQL 18 | `127.0.0.1:5432` | not reachable from outside |
| Files | `/srv/devfest/` | `backend/` (`.env`, `devfest.jar` link), `frontend/` (`current` link), `releases/`, `backups/`, `bin/` (scripts) |
| Firewall | Oracle: iptables rules (22, 80, 443) saved with `netfilter-persistent`; other hosts: ufw | see "Two firewalls" below |

## Read this first (Oracle Always Free)

- **Never terminate the instance** (console: Instances -> your instance -> More actions -> Terminate). Termination deletes the
  disk and the public IP for good, and a new Always Free A1 machine is often not available again. Reboot and Stop are safe.
- **Use only Always Free resources.** Everything created for this event must carry the "Always Free" label. Do not click
  "Upgrade" and do not add paid shapes, load balancers or volumes beyond the free limits.
- Oracle can **reclaim an Always Free instance that stays almost idle for a week**. Keep using it, and check Oracle's current
  rule in the console documentation.
- **Copy a backup to the laptop every week** (section 6) and before and after the event. A backup that lives only on the server
  dies with the server.

### What was already done in the Oracle console

1. The **VCN wizard** ("Create VCN with Internet Connectivity") created the network and a public subnet.
2. The subnet's **security list** allows incoming TCP **22, 80 and 443** from `0.0.0.0/0`.
3. The **A1 instance** exists: shape `VM.Standard.A1.Flex`, Ubuntu 26.04, 2 OCPU, 12 GB RAM, public IP, your SSH key.

### Two firewalls (the usual Oracle trap)

Opening a port in the security list is **not enough**. Oracle's Ubuntu images also ship **iptables rules inside the machine**
(`/etc/iptables/rules.v4`) that reject everything except SSH. `setup-server.sh` adds ACCEPT rules for 80 and 443 in front of
that REJECT rule and saves them with `netfilter-persistent`, so they survive a reboot (running it again adds nothing twice).
It never enables `ufw` on Oracle (the two conflict). It detects Oracle itself; the flag `--firewall oracle|ufw|none|auto`
overrides the choice (`auto` is the default; on any other host it installs and enables `ufw` with 22, 80, 443).

### Memory size

The service file `infrastructure/deploy/devfest-backend.service` is set for this 12 GB server: `-Xmx2g` (Java heap) and
`MemoryMax=3G`. On a small 2 GB host use `-Xms256m -Xmx768m` and `MemoryMax=1200M` instead (the file has a comment saying so).
To change it later, edit the file on the laptop, upload the deploy folder again (step 2a) and run the setup again.

## Where the site address goes

Without a domain the site is reached at `http://SERVER_IP`. `setup-server.sh` takes that address **once** (`--host SERVER_IP`
or it asks) and fills it in for you:

1. `nginx-site-http-only.conf` (or `nginx-site.conf` once HTTPS exists), installed as `/etc/nginx/sites-available/devfest`:
   the placeholder `REPLACE_HOST` in these files becomes the IP or domain. The parts both share live in `nginx-app.conf`
   (installed as `/etc/nginx/snippets/devfest-app.conf`).
2. `/srv/devfest/backend/.env`: `DEVFEST_ORIGINS=http://SERVER_IP` (later `https://REPLACE_DOMAIN`). Running the setup again with a
   different address corrects it and restarts the backend.
3. `/srv/devfest/bin/deploy.conf` (`SITE_HOST`, `SITE_SCHEME`): remembered by the deploy scripts for their health check.

### What plain HTTP means (read once)

- The first deploy has **no encryption**: sign-in passwords and tokens cross the network readable. That is acceptable for a
  rehearsal; for the real event on shared Wi-Fi switch to HTTPS (section 4) or accept the risk knowingly.
- Nothing in the app depends on HTTPS: login tokens are kept in the browser's session storage and sent in an `Authorization`
  header (there are **no cookies**, so no `Secure` flag can fail). HSTS is **off** in the HTTP site on purpose: a browser that has
  seen HSTS on an address refuses plain HTTP afterwards. The request-key generator (admin timer buttons), the copy buttons and the
  terminal's saved state have fallbacks for the browser features that exist only on HTTPS.
- Browsers show "Not secure" in the address bar; the **Copy** button in the credentials dialog and the terminal's "Copy" are
  blocked by the browser (use **Download CSV**, or select and copy by hand).
- Over HTTP a browser keeps at most **6 connections per address**, and the live board stream holds one: use **one tab** per
  person (HTTPS uses HTTP/2, which has no such limit).

---

## 1. Log in to the server

You already have the instance and its key. From the laptop:

```powershell
PS> ssh -i KEY ubuntu@SERVER_IP
```
*Opens a terminal on the server. Answer `yes` to the fingerprint question the first time. `exit` leaves.* If Windows complains
that the key file permissions are too open, remove other users from the file's Security tab (or run
`icacls KEY /inheritance:r /grant:r "$($env:USERNAME):R"`).

---

## 2. First-time server setup

**2a. Upload the deploy folder** (from the repository folder on the laptop):

```powershell
PS> cd C:\path\to\devfest
PS> scp -i KEY -r infrastructure\deploy ubuntu@SERVER_IP:/home/ubuntu/devfest-deploy
```
*Copies the folder `infrastructure\deploy` to `/home/ubuntu/devfest-deploy` on the server (including `nginx-app.conf`).*

**2b. Check the files kept Unix line endings** (the #1 beginner trap: Windows line endings break shell scripts):

```bash
server$ cd ~/devfest-deploy
server$ grep -lU $'\r' *.sh *.service *.timer *.conf
```
*Lists files that contain Windows line breaks. It must print nothing.* If it lists files, fix them with
`sed -i 's/\r$//' *.sh *.service *.timer *.conf` (*removes the extra character at each line end*). The repository's
`.gitattributes` forces LF for `*.sh`, `*.service`, `*.timer` and `*.conf`; on the laptop you can check with
`git ls-files --eol infrastructure/deploy` (every line should say `w/lf`).

**2c. Run the setup** (keep this SSH window open until 2d is done):

```bash
server$ sudo bash setup-server.sh --host SERVER_IP
```
*Installs and configures everything. It takes 5-10 minutes and asks you:*

1. *A database password* (press Enter to let it generate one; it is stored only in `/srv/devfest/backend/.env`).
2. *The first admin's email and password* (12+ characters). You change this password after the first sign-in.

The two flags: `--host` is the public IP (Oracle console: Instance details -> Public IP address; the script refuses private
addresses such as `10.x.x.x`) or, later, the domain; without it the script asks. `--firewall auto|oracle|ufw|none` is optional
(default `auto`, which detects Oracle). The script stops with a clear message if the CPU is not amd64/arm64 or a package is
missing.

What it does, in order: checks Ubuntu version and CPU; 2 GB swap; nginx, certbot, fail2ban, unattended-upgrades, plus
`iptables-persistent` (Oracle) or `ufw` (other hosts); PostgreSQL 18 (Ubuntu's own package, the official PostgreSQL repository
only if Ubuntu has none); Java 25 (Ubuntu's `openjdk-25-jre-headless`, otherwise Eclipse Temurin); the `devfest` user and folders;
the database role and database; `.env` (`chmod 600`, owner `devfest`); the systemd services; the nginx site (plain HTTP); the
firewall rules (22, 80, 443) and the backup timer. It also lets the user you logged in as read the backup files (for `scp`).
**Last**, it turns off SSH password login, but only if it finds a key in `authorized_keys` (Oracle installed yours); otherwise it
warns and leaves passwords on.

It is safe to run again; it skips what exists and never asks for the passwords twice.

**2d. Prove SSH still works before you close anything:** open a **second** PowerShell window and run
`ssh -i KEY ubuntu@SERVER_IP`. If it logs in, all is well. Only then close the first window. (Log in again anyway after the setup:
the backup-read permission of 2c only applies to new sessions.)

**2e. Quick look:**

```bash
server$ systemctl is-active nginx postgresql fail2ban
server$ sudo iptables -S INPUT
server$ java -version
server$ ls -l /srv/devfest/backend/.env
```
*Each service should say `active`; the iptables list shows ACCEPT rules for `--dport 22`, `80` and `443` **before** the final
`-j REJECT` line; Java says 25; `.env` shows `-rw------- devfest devfest`.* On a non-Oracle host use `sudo ufw status` instead
of `iptables`.

---

## 3. First deploy

The exact order, from a fresh server to a running site:

1. Section 1 (log in) and section 2 (setup), as above.
2. Build the release on the laptop (3a), upload it (3b), install it (3c).
3. Check (3d) and open `http://SERVER_IP` in a browser.

**3a. Build the release on the laptop** (builds the frontend and the backend, then makes one zip). The jar is plain Java and the
frontend is plain JavaScript/WebAssembly, so building on Windows x86 and running on the arm64 server needs nothing special:

```powershell
PS> cd C:\path\to\devfest
PS> powershell -ExecutionPolicy Bypass -File infrastructure\deploy\build-release.ps1
```
*Runs `ng build` for production, `mvnw package` (with the backend tests) and zips the result into
`release\devfest-<date>-<git hash>.zip`.* If the tests need a database you do not have running, add `-SkipTests`
(only when you already ran the tests).

**3b. Upload the zip.** The `ubuntu` user cannot write into `/srv/devfest/releases`, so it goes to the home folder first:

```powershell
PS> scp -i KEY release\devfest-20261112-ab12cd3.zip ubuntu@SERVER_IP:/home/ubuntu/
```
*Copies the file to the server's home folder (use your zip's real name).*

**3c. Install it:**

```bash
server$ sudo mv ~/devfest-20261112-ab12cd3.zip /srv/devfest/releases/
server$ sudo /srv/devfest/bin/deploy.sh /srv/devfest/releases/devfest-20261112-ab12cd3.zip
```
*Moves the zip into place, unpacks it, backs up the database, switches the jar and the website files, restarts the backend and
waits until `/api/v1/health` says `UP`. It ends with `DEPLOY OK` and prints the health answer.*

**3d. Check by hand:**

```bash
server$ curl -s http://127.0.0.1:8081/api/v1/health
server$ curl -s http://127.0.0.1/api/v1/health
```
*The first asks the backend directly, the second goes through nginx. Expected both: `{"status":"UP"}`.* Then, **from the
laptop** (this goes through Oracle's real network path, both firewalls included):

```powershell
PS> Invoke-WebRequest -UseBasicParsing http://SERVER_IP/api/v1/health | Select-Object -ExpandProperty Content
```
*Must print `{"status":"UP"}`. A timeout here while the server's own `curl` works means a firewall still blocks port 80 (see the
troubleshooting table).* Open `http://SERVER_IP` in a browser and sign in with the admin account.

---

## 4. Switching to HTTPS (when a domain exists)

You need a domain you control (written `REPLACE_DOMAIN` below: type your real one, for example `devfest.example.com`). The
server keeps working over HTTP until the very last step.

1. **Point the domain at the server.** In your domain provider's DNS page add an **A record**: name = your (sub)domain, value =
   `SERVER_IP`. Wait until this prints `SERVER_IP` on the laptop:

   ```powershell
   PS> nslookup REPLACE_DOMAIN
   ```

2. **Get the certificate** (port 80 must be open and answering; it is). The certbot challenge is answered by the HTTP site:

   ```bash
   server$ sudo certbot certonly --webroot -w /var/www/certbot -d REPLACE_DOMAIN --agree-tos -m YOUR_EMAIL --no-eff-email
   ```
   *Asks Let's Encrypt for a certificate; replace `YOUR_EMAIL` with a real address (expiry warnings go there).*

3. **Run the setup again with the domain:**

   ```bash
   server$ cd ~/devfest-deploy
   server$ sudo bash setup-server.sh --host REPLACE_DOMAIN
   ```
   *It finds the certificate and switches nginx from the HTTP site to the full HTTPS site (HTTP redirects to HTTPS, HSTS on, IP
   scans dropped). It rewrites `DEVFEST_ORIGINS` in `.env` to `https://REPLACE_DOMAIN`, restarts the backend, and updates
   `deploy.conf`. Without a certificate it would stay on HTTP.*

4. **Check:**

   ```bash
   server$ curl -sI https://REPLACE_DOMAIN | head -n 5
   server$ sudo certbot renew --dry-run
   ```
   *The first shows `HTTP/2 200` with the security headers; the second rehearses the automatic renewal (a timer renews by itself;
   nginx reloads after each renewal).*

Everybody must sign in again (the browser stores the login per address) and `http://SERVER_IP` stops working. To go back to
HTTP for any reason, run `sudo bash setup-server.sh --host SERVER_IP`.

---

## 5. Updating the site (build, upload, deploy, rollback)

```powershell
PS> powershell -ExecutionPolicy Bypass -File infrastructure\deploy\build-release.ps1
PS> scp -i KEY release\devfest-NEWNAME.zip ubuntu@SERVER_IP:/home/ubuntu/
PS> ssh -i KEY ubuntu@SERVER_IP "sudo mv /home/ubuntu/devfest-NEWNAME.zip /srv/devfest/releases/ && sudo /srv/devfest/bin/deploy.sh /srv/devfest/releases/devfest-NEWNAME.zip"
```
*Build, copy, and run the install on the server in one go. A database backup (`predeploy-...dump`) is taken first. The
previous release stays in `/srv/devfest/releases/`.*

**Something is wrong after a deploy -> go back:**

```bash
server$ sudo /srv/devfest/bin/rollback.sh
```
*Puts the previous jar and website files back and restarts. Running it again returns to the newer release.*

> **WARNING: `rollback.sh` restores the CODE only, never the database.**
> If the release you are leaving added a database migration (a new file in `backend/src/main/resources/db/migration/`),
> the old jar can fail to start or misread the newer tables. In that case do **both, in this order**:
>
> 1. `server$ sudo /srv/devfest/bin/rollback.sh`  *(old jar and website back, restarted)*
> 2. `server$ sudo /srv/devfest/bin/restore.sh $(cat /srv/devfest/releases/NEWRELEASE/PREDEPLOY_BACKUP) --live`
>    *(the database as it was just before that deploy; `NEWRELEASE` is the name of the release you rolled back from,
>    for example `devfest-20261112-ab12cd3`. `rollback.sh` also prints this path.)*
>
> Step 2 throws away everything entered after that deploy (scores, submissions), so it is for emergencies only.
> Not sure whether the release had a migration? Ask whoever built it, or look in `journalctl -u devfest-backend` for
> Flyway's "Migrating schema" lines from that start.

The scripts themselves: after changing anything in `infrastructure/deploy/`, upload the folder again (step 2a) and run
`sudo bash ~/devfest-deploy/setup-server.sh` (it remembers the address from last time).

---

## 6. Backups and restore

A systemd timer runs `backup.sh` **every 10 minutes** (`pg_dump -Fc`, newest 144 kept = 24 hours; also one `latest.dump`
link). Deploys make their own `predeploy-*` backups (newest 20 kept). Files are in `/srv/devfest/backups/`.

```bash
server$ sudo ls -lh /srv/devfest/backups | tail
server$ systemctl list-timers devfest-backup.timer
server$ sudo /srv/devfest/bin/backup.sh
```
*Lists backups; shows when the timer runs next; makes one backup right now.*

**Switch to daily outside the event** (03:00 every day):

```bash
server$ sudo mkdir -p /etc/systemd/system/devfest-backup.timer.d
server$ printf '[Timer]\nOnCalendar=\nOnCalendar=*-*-* 03:00:00\n' | sudo tee /etc/systemd/system/devfest-backup.timer.d/schedule.conf
server$ sudo systemctl daemon-reload && sudo systemctl restart devfest-backup.timer
```
*Overrides the schedule (first empty line clears the 10-minute one).* **Back to every 10 minutes for the event:**
`sudo rm -r /etc/systemd/system/devfest-backup.timer.d && sudo systemctl daemon-reload && sudo systemctl restart devfest-backup.timer`.

**Copy backups to your laptop - at least once a week, and before and after the event** (a backup that lives only on the
server dies with the server). The setup let the `ubuntu` user read the backup folder, so `scp` needs no `sudo` (log in again
once after the setup if it says "Permission denied"):

```powershell
PS> mkdir C:\devfest-backups
PS> scp -i KEY ubuntu@SERVER_IP:/srv/devfest/backups/latest.dump C:\devfest-backups\devfest-latest.dump
PS> scp -i KEY "ubuntu@SERVER_IP:/srv/devfest/backups/devfest-2*.dump" C:\devfest-backups\
```
*The first copies the newest backup; the second copies all of them (up to 24 hours).* A weekly reminder in your calendar is the
safest way to remember it.

**During the event: copy the newest backups to the laptop every hour.** Keep one PowerShell window for this and run the
same command at the start of each hour (the dump names carry UTC time):

```powershell
PS> $h=(Get-Date).ToUniversalTime().AddHours(-1).ToString("yyyyMMddTHH"); scp -i KEY "ubuntu@SERVER_IP:/srv/devfest/backups/devfest-$h*.dump" "ubuntu@SERVER_IP:/srv/devfest/backups/latest.dump" C:\devfest-backups
```
*Copies the six backups of the previous hour, plus `latest.dump` (the newest one, overwritten each time on the laptop).
If the server dies you lose at most about 10 minutes of data and still have every hour on the laptop.*

**Practice restore (safe, the live site is not touched):**

```bash
server$ sudo /srv/devfest/bin/restore.sh /srv/devfest/backups/latest.dump
```
*Asks you to type `yes`, restores into a scratch database `devfest_restore_check`, prints table row counts, and deletes it.
`RESTORE CHECK OK` means the backup is usable.*

**Restore live (replaces the event data; only for a real disaster or a bad deploy):**

```bash
server$ sudo /srv/devfest/bin/restore.sh /srv/devfest/backups/devfest-20261112T101500Z.dump --live
```
*Asks you to type `RESTORE LIVE`. It restores into a fresh database first; only if that worked does it stop the backend,
rename the old database to `devfest_before_restore_<time>`, put the restored one in place as `devfest`, and start the
backend. The old data is not deleted.* Drop it later with `sudo -u postgres dropdb devfest_before_restore_<time>`.

*Restore went wrong:* the script says so and leaves the live database untouched, or (if only the final start failed) keeps the
old one under `devfest_before_restore_<time>`. To switch back by hand:
`sudo systemctl stop devfest-backend`, then in `sudo -u postgres psql` run `ALTER DATABASE devfest RENAME TO devfest_broken;` and
`ALTER DATABASE devfest_before_restore_<time> RENAME TO devfest;`, then `sudo systemctl start devfest-backend`.

**To restore from a dump that is on your laptop:** `scp -i KEY C:\devfest-backups\file.dump ubuntu@SERVER_IP:/home/ubuntu/`, then
`sudo mv ~/file.dump /srv/devfest/backups/` and use `restore.sh` on it.

---

## 7. Reading logs when something fails

```bash
server$ sudo journalctl -u devfest-backend -n 100 --no-pager
server$ sudo journalctl -u devfest-backend -f
server$ sudo journalctl -u devfest-backend --since "10 minutes ago" --no-pager
```
*The backend's log: last 100 lines / follow live (Ctrl+C stops) / the last 10 minutes.*

```bash
server$ sudo systemctl status devfest-backend
server$ sudo tail -n 50 /var/log/nginx/error.log
server$ sudo tail -n 50 /var/log/nginx/access.log
server$ sudo nginx -t
server$ sudo journalctl -u devfest-backup -n 20 --no-pager
server$ sudo journalctl -u postgresql@18-main -n 50 --no-pager
```
*Service state; nginx errors; nginx requests; checks the nginx config for mistakes; the last backup runs; PostgreSQL's log.*

| Symptom | Likely cause and fix |
|---|---|
| Site opens on the server (`curl http://127.0.0.1`) but **times out from the laptop** | One of the two firewalls. Check `sudo iptables -S INPUT` shows ACCEPT for `--dport 80` **before** the `REJECT` line (if not: `sudo bash ~/devfest-deploy/setup-server.sh --host SERVER_IP`), then the Oracle console security list (tcp 80 / 443 ingress from `0.0.0.0/0`) of the subnet the instance uses. |
| After a reboot the site is unreachable | The iptables rules were not saved: re-run the setup (it saves them with `netfilter-persistent`), and check `sudo systemctl is-enabled netfilter-persistent`. |
| Browser shows **502 Bad Gateway** | Backend is down or still starting. `sudo systemctl status devfest-backend`, then `sudo journalctl -u devfest-backend -n 100`. |
| Backend log: `Set a unique DEVFEST_JWT_SECRET` / `bootstrap administrator password` | `.env` still has a `REPLACE_` value. `sudo nano /srv/devfest/backend/.env`, then `sudo systemctl restart devfest-backend`. |
| Backend log: `password authentication failed` | The password in `.env` differs from the database role's. Re-run `setup-server.sh` after deleting `.env` (it sets a new password on the role and writes a new `.env`). |
| Backend log: `refuses a database other than devfest` | `DEVFEST_DB_URL` must end in `/devfest`. |
| Backend log: `OutOfMemoryError`, service restarts | The heap is full. Raise `-Xmx` (and `MemoryMax`) in the service file as in "Memory size", upload the deploy folder, run the setup again. |
| Browser: `Invalid CORS request` / login fails after changing the address | `DEVFEST_ORIGINS` in `.env` must be exactly the address in the browser (`http://SERVER_IP` or `https://REPLACE_DOMAIN`). Run `setup-server.sh --host ...` again; it fixes it. |
| **413 Request Entity Too Large** on an upload | File is bigger than the limit (ZIP 25 MB, terminal file 2 MB, content JSON 2 MB). |
| Everybody gets **429 Too many requests** | The venue shares one IP; nginx allows 200 requests/second per IP with a burst of 400, and login is limited per IP and per account in the backend. |
| `certbot` fails | DNS does not point at the server yet, or port 80 is blocked (see the first row). |
| The live board never updates or pages hang | Check `/var/log/nginx/devfest-stream.log`. Over plain HTTP a browser allows 6 connections per address: close extra tabs of the site. HTTPS (HTTP/2) removes the limit. |
| Disk full | `df -h`; old releases are pruned by `deploy.sh`, backups by `backup.sh`; `sudo journalctl --vacuum-size=200M`. |

---

## 8. First-run checklist (do this a few days before the event)

- [ ] **Health is UP:** `curl -s http://SERVER_IP/api/v1/health` (or `https://REPLACE_DOMAIN/api/v1/health`) returns `{"status":"UP"}`.
- [ ] **Admin sign-in:** open the site (the page asks for *Email* and *Password*, button **Sign in**) with the admin email and password you gave `setup-server.sh`. You land on **Event control**.
- [ ] **Change the admin password.** The app has no screen for the admin's own password (the *Reset password* button on **Teams & accounts** is only for manager and team accounts), so use the API from PowerShell on the laptop (`SITE` is `http://SERVER_IP` or `https://REPLACE_DOMAIN`):

      ```powershell
      PS> $b="SITE/api/v1"; $email="ADMIN_EMAIL"
      PS> $cur=Read-Host "Current password"; $new=Read-Host "New password (12-72 characters)"
      PS> $t=Invoke-RestMethod -Method Post "$b/auth/login" -ContentType application/json -Body (@{email=$email;password=$cur}|ConvertTo-Json)
      PS> Invoke-RestMethod -Method Post "$b/auth/password" -Headers @{Authorization="Bearer $($t.accessToken)"} -ContentType application/json -Body (@{currentPassword=$cur;newPassword=$new}|ConvertTo-Json)
      ```
      *Logs in, then changes the password (no error = done; sign in again with the new one).* Then remove the bootstrap lines from `.env`
      (they are only used while no admin exists): `sudo sed -i '/^DEVFEST_ADMIN_/d' /srv/devfest/backend/.env && sudo systemctl restart devfest-backend`.
- [ ] **Switch the room activities on** (admin, sidebar **Content**, section **Room clues**, one block per room). The imports below stay disabled until these are ticked:
      **AI** room: tick **AI mission activity**. **Linux** room: tick **Terminal enabled** and **Linux terminal activity**.
      **Problem solving** room: tick **Contest results activity** if that room uses the Codeforces contest.
- [ ] **Import the final content:** admin, **Content**, section **Final content file**: choose `docs/final/last-shipment-content.json`, then **Import this file**. It says *Not imported* until this is done, and the Final cannot open without it.
- [ ] **Import the Sentinel (AI room) content:** sidebar **AI room** (the room board), link **SENTINEL page**, section **Mission file**, **Import a JSON file**: choose `docs/ai-room/sentinel-content.json`. Optionally set **Pass score** and **Save pass score**. (As the AI room's manager or as admin.)
- [ ] **Upload the Linux server package ZIP:** sidebar **Linux room**, section **Terminal files**, part **Server package**, **Upload package (.zip)**. Rules in `docs/LINUX-ROOM.md`. This is the 25 MB upload nginx now allows. (The *Terminal files* section only appears once **Terminal enabled** is ticked, see above.)
- [ ] **Generate the teams:** admin, **Teams & accounts**. In **Groups** set *Planned teams* and press **Save**; then in **Teams** use **Bulk generation** (*Number of teams*, *Name prefix*) and press **Generate teams**, then **Confirm**. The sign-ins are shown to copy or **Download CSV** (over plain HTTP the browser blocks **Copy**: use the CSV).
- [ ] **One team plays one room** end to end on a phone/laptop that is not yours (this tests the live updates too, and the Wi-Fi path to Oracle).
- [ ] **Run a backup, then a restore check** (section 6): `backup.sh`, then `restore.sh /srv/devfest/backups/latest.dump` -> `RESTORE CHECK OK`. Copy a backup to your laptop with `scp`.
- [ ] **Take a boot volume backup:** Oracle console -> Storage -> Boot volumes -> your volume -> Boot volume backups -> Create (run `backup.sh` first; Always Free includes a few backups, check the limit shown in the console and delete the oldest before making another). It is a copy of the whole disk, so you can rebuild the server fast.
- [ ] **Reset test data** before the event: admin, **Overrides**, **Danger zone**, **Reset whole event…** (type `RESET` and your admin password), or restore a clean backup, so rehearsal results do not leak into the real event.

---

## 9. The day before the event

Do this the day before, not the morning of: a reboot or update that goes wrong needs time to fix.

```bash
server$ sudo apt-get update && sudo apt-get -y upgrade
```
*Installs every pending update (the server also installs security updates by itself, but never reboots on its own).*

```bash
server$ ls /var/run/reboot-required
server$ sudo reboot
```
*Shows whether a reboot is needed (a "No such file" answer means not needed; reboot once anyway). `reboot` restarts the server
and drops your SSH window. Wait about two minutes, then log in again with `ssh -i KEY ubuntu@SERVER_IP`. Never use
"Terminate" in the Oracle console.*

```bash
server$ systemctl is-active nginx postgresql devfest-backend fail2ban
server$ curl -s http://127.0.0.1/api/v1/health
server$ systemctl list-timers devfest-backup.timer
server$ sudo ls -lt /srv/devfest/backups | head -n 3
server$ sudo iptables -S INPUT ; free -h
```
*Every service should say `active`; health must be `{"status":"UP"}` (use `https://REPLACE_DOMAIN/api/v1/health` once HTTPS
exists); the backup timer must show a next run and the newest backup must be recent; the iptables list still shows 22, 80, 443
ACCEPT rules before the REJECT line (this proves they survived the reboot); `free -h` shows the 2 GB swap.*

If a service is not `active` after the reboot, read its log (section 7) and fix it now, while there is time. Finish by taking
a fresh boot volume backup (section 8) and copying the newest backup to the laptop (section 6).
