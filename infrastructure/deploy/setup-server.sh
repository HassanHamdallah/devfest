#!/usr/bin/env bash
# First-time (and repeatable) setup of the DevFest server on a fresh Ubuntu LTS VPS. Run as root, from the folder
# that holds the other deploy files:   cd /root/devfest-deploy && bash setup-server.sh
# Safe to run again: every step checks what already exists. It never prints or stores a password except into
# /srv/devfest/backend/.env (chmod 600).
set -euo pipefail

HERE="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
ROOT=/srv/devfest
export DEBIAN_FRONTEND=noninteractive

say()  { printf '\n==> %s\n' "$*"; }
warn() { printf 'WARNING: %s\n' "$*" >&2; }
die()  { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "Run as root (log in as root, or put 'sudo' in front)."
for f in common.sh deploy.sh rollback.sh backup.sh restore.sh env.production.example devfest-backend.service \
         devfest-backup.service devfest-backup.timer nginx-site.conf nginx-site-http-only.conf; do
  [ -f "$HERE/$f" ] || die "Missing $HERE/$f. Upload the whole infrastructure/deploy folder."
done
if ! grep -qi '^ID=ubuntu' /etc/os-release; then warn "This script was written for Ubuntu; this is not Ubuntu. Continuing."; fi
CODENAME="$(. /etc/os-release && echo "${VERSION_CODENAME:-}")"
[ -n "$CODENAME" ] || die "Cannot read the Ubuntu release name from /etc/os-release."

# Reads one line from the keyboard even when the script is piped. $1 = prompt, $2 = "secret" to hide typing.
ask() {
  local reply
  if [ "${2:-}" = secret ]; then read -r -s -p "$1" reply < /dev/tty; echo > /dev/tty; else read -r -p "$1" reply < /dev/tty; fi
  printf '%s' "$reply"
}

# ---------------------------------------------------------------------------------------------------------------
say "0. Domain name"
DOMAIN="${DEVFEST_DOMAIN:-}"
if [ -z "$DOMAIN" ] && [ -f "$ROOT/bin/deploy.conf" ]; then
  # shellcheck disable=SC1091
  . "$ROOT/bin/deploy.conf"
fi
if [ -z "$DOMAIN" ]; then DOMAIN="$(ask 'Domain of the event site (for example devfest.example.com): ')"; fi
DOMAIN="$(printf '%s' "$DOMAIN" | tr 'A-Z' 'a-z' | tr -d '[:space:]')"
printf '%s' "$DOMAIN" | grep -Eq '^([a-z0-9]([a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,}$' || die "'$DOMAIN' is not a valid domain name."
[ "$DOMAIN" != "replace_domain" ] || die "Type your real domain name."
echo "Using domain: $DOMAIN"
SSH_PORT="$(sshd -T 2>/dev/null | awk '/^port /{print $2; exit}')"
SSH_PORT="${SSH_PORT:-22}"

# ---------------------------------------------------------------------------------------------------------------
say "1. Swap file (2 GB)"
if swapon --show=NAME --noheadings | grep -q .; then
  echo "Swap already active: $(swapon --show=NAME,SIZE --noheadings | tr '\n' ' ')"
else
  if [ ! -f /swapfile ]; then
    fallocate -l 2G /swapfile || dd if=/dev/zero of=/swapfile bs=1M count=2048 status=none
    chmod 600 /swapfile
    mkswap /swapfile > /dev/null
  fi
  swapon /swapfile
  echo "Created and enabled /swapfile (2 GB)."
fi
grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
# Use swap only as a safety net, not as extra speed.
echo 'vm.swappiness=10' > /etc/sysctl.d/99-devfest.conf
sysctl -q -p /etc/sysctl.d/99-devfest.conf || true

# ---------------------------------------------------------------------------------------------------------------
say "2. Base packages (nginx, certbot, ufw, fail2ban, unattended-upgrades)"
apt-get update -q
apt-get install -y -q ca-certificates curl gnupg unzip openssl ufw nginx certbot fail2ban unattended-upgrades postgresql-common

# ---------------------------------------------------------------------------------------------------------------
say "3. PostgreSQL 18 (official PGDG repository)"
# This script ships with postgresql-common and adds the official apt.postgresql.org repository for this release
# (it does nothing if the repository is already there).
/usr/share/postgresql-common/pgdg/apt.postgresql.org.sh -y
apt-cache policy postgresql-18 | grep -q 'Candidate: [0-9]' || die "PostgreSQL 18 is not available for Ubuntu '$CODENAME' on apt.postgresql.org."
apt-get install -y -q postgresql-18
PGCONF=/etc/postgresql/18/main/conf.d/devfest.conf
mkdir -p "$(dirname "$PGCONF")"
PGWANT='# Managed by setup-server.sh. PostgreSQL listens on this machine only.
listen_addresses = '"'127.0.0.1'"'
max_connections = 60
shared_buffers = 256MB
effective_cache_size = 768MB
work_mem = 8MB'
if [ "$(cat "$PGCONF" 2>/dev/null)" != "$PGWANT" ]; then
  printf '%s\n' "$PGWANT" > "$PGCONF"
  systemctl restart postgresql
fi
systemctl enable --now postgresql > /dev/null
for _ in $(seq 1 30); do runuser -u postgres -- psql -tAc 'SELECT 1' > /dev/null 2>&1 && break; sleep 1; done
runuser -u postgres -- psql -tAc 'SELECT 1' > /dev/null || die "PostgreSQL does not answer."
if ss -ltnH | awk '{print $4}' | grep -E ':5432$' | grep -vqE '^(127\.0\.0\.1|\[::1\]):5432$'; then
  die "PostgreSQL listens on a public address. Check $PGCONF and /etc/postgresql/18/main/postgresql.conf."
fi

# ---------------------------------------------------------------------------------------------------------------
say "4. Java 25 runtime"
java_ok() { command -v java > /dev/null && java -version 2>&1 | head -n1 | grep -Eq '"25(\.|")'; }
if java_ok; then
  echo "Java 25 is already installed."
else
  if apt-cache policy openjdk-25-jre-headless | grep -q 'Candidate: [0-9]'; then
    echo "Installing Ubuntu's openjdk-25-jre-headless"
    apt-get install -y -q openjdk-25-jre-headless
  else
    echo "Ubuntu '$CODENAME' has no openjdk-25 package: using Eclipse Temurin 25 from packages.adoptium.net"
    install -d -m 0755 /etc/apt/keyrings
    curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor --yes -o /etc/apt/keyrings/adoptium.gpg
    echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $CODENAME main" > /etc/apt/sources.list.d/adoptium.list
    apt-get update -q
    apt-cache policy temurin-25-jre | grep -q 'Candidate: [0-9]' || die "temurin-25-jre is not offered for '$CODENAME'. Install a Java 25 runtime by hand, then run this script again."
    apt-get install -y -q temurin-25-jre
  fi
fi
java_ok || die "Java 25 is not the default 'java' after installing. Check: java -version ; update-alternatives --config java"
[ -x /usr/bin/java ] || warn "/usr/bin/java is missing: edit ExecStart in /etc/systemd/system/devfest-backend.service to the real path ($(command -v java))."

# ---------------------------------------------------------------------------------------------------------------
say "5. User, folders"
id devfest > /dev/null 2>&1 || useradd --system --home-dir "$ROOT" --no-create-home --shell /usr/sbin/nologin devfest
install -d -m 0755 -o root -g root "$ROOT" "$ROOT/frontend" "$ROOT/releases" "$ROOT/bin"
install -d -m 0750 -o root -g devfest "$ROOT/backend"          # devfest can read here, not create files
install -d -m 0750 -o postgres -g postgres "$ROOT/backups"
install -d -m 0755 /var/www/certbot

# ---------------------------------------------------------------------------------------------------------------
say "6. Database role, database and $ROOT/backend/.env"
ENVFILE="$ROOT/backend/.env"
set_var() {   # set_var FILE KEY VALUE : replaces the line KEY=... (value used verbatim, no shell or sed escaping)
  local tmp; tmp="$(mktemp)"
  V="$3" awk -v k="$2" 'BEGIN{FS=OFS="="} $1==k {print k "=" ENVIRON["V"]; next} {print}' "$1" > "$tmp"
  cat "$tmp" > "$1"; rm -f "$tmp"
}
get_var() { sed -n "s/^$2=//p" "$1" | head -n1; }

DB_PASSWORD=""
if [ -f "$ENVFILE" ]; then
  echo "$ENVFILE exists: keeping it (delete it and run again to regenerate)."
  DB_PASSWORD="$(get_var "$ENVFILE" DEVFEST_DB_PASSWORD)"
  { [ -n "$DB_PASSWORD" ] && [[ "$DB_PASSWORD" != REPLACE_* ]]; } || die "DEVFEST_DB_PASSWORD in $ENVFILE is empty or still a placeholder."
else
  DB_PASSWORD="${DEVFEST_DB_PASSWORD:-}"
  if [ -z "$DB_PASSWORD" ]; then
    DB_PASSWORD="$(ask 'Database password (letters and digits only, 16+ characters; press Enter to generate one): ' secret)"
  fi
  if [ -z "$DB_PASSWORD" ]; then
    DB_PASSWORD="$(openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c 32)"
    echo "A random 32-character database password was generated and will be stored only in $ENVFILE."
  fi
  printf '%s' "$DB_PASSWORD" | grep -Eq '^[A-Za-z0-9]{16,}$' || die "The database password must be 16 or more letters and digits only."
fi

# Role: create it with the password, or (re)set the password when we have just chosen one.
ROLE_EXISTS="$(runuser -u postgres -- psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='devfest'")"
if [ "$ROLE_EXISTS" != "1" ]; then
  printf "CREATE ROLE devfest LOGIN PASSWORD :'pw';\n" | runuser -u postgres -- psql -q -v ON_ERROR_STOP=1 -v pw="$DB_PASSWORD"
  echo "Created database role devfest."
elif [ ! -f "$ENVFILE" ]; then
  printf "ALTER ROLE devfest LOGIN PASSWORD :'pw';\n" | runuser -u postgres -- psql -q -v ON_ERROR_STOP=1 -v pw="$DB_PASSWORD"
  echo "Set a new password on the existing role devfest."
else
  echo "Database role devfest exists."
fi
if [ "$(runuser -u postgres -- psql -tAc "SELECT 1 FROM pg_database WHERE datname='devfest'")" != "1" ]; then
  runuser -u postgres -- createdb --owner=devfest --encoding=UTF8 devfest
  echo "Created database devfest."
else
  echo "Database devfest exists."
fi
runuser -u postgres -- psql -q -c 'REVOKE ALL ON DATABASE devfest FROM PUBLIC' > /dev/null

if [ ! -f "$ENVFILE" ]; then
  ADMIN_EMAIL="${DEVFEST_ADMIN_EMAIL:-}"
  [ -n "$ADMIN_EMAIL" ] || ADMIN_EMAIL="$(ask 'Email for the first admin account: ')"
  printf '%s' "$ADMIN_EMAIL" | grep -Eq '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$' || die "'$ADMIN_EMAIL' does not look like an email address."
  ADMIN_PASSWORD="${DEVFEST_ADMIN_PASSWORD:-}"
  if [ -z "$ADMIN_PASSWORD" ]; then
    ADMIN_PASSWORD="$(ask 'First admin password (12 to 72 characters, no backslash; you change it after the first sign-in): ' secret)"
  fi
  [ "${#ADMIN_PASSWORD}" -ge 12 ] && [ "${#ADMIN_PASSWORD}" -le 72 ] || die "The admin password must be 12 to 72 characters."
  case "$ADMIN_PASSWORD" in *\\*|*[[:space:]]*) die "The admin password must not contain a backslash or spaces." ;; esac

  ( umask 077; cp "$HERE/env.production.example" "$ENVFILE" )
  set_var "$ENVFILE" DEVFEST_DB_PASSWORD "$DB_PASSWORD"
  set_var "$ENVFILE" DEVFEST_JWT_SECRET "$(openssl rand -base64 64 | tr -dc 'A-Za-z0-9' | head -c 64)"
  set_var "$ENVFILE" DEVFEST_ORIGINS "https://$DOMAIN"
  set_var "$ENVFILE" DEVFEST_ADMIN_EMAIL "$ADMIN_EMAIL"
  set_var "$ENVFILE" DEVFEST_ADMIN_PASSWORD "$ADMIN_PASSWORD"
  echo "Wrote $ENVFILE"
fi
chown devfest:devfest "$ENVFILE"
chmod 600 "$ENVFILE"
grep -v '^#' "$ENVFILE" | grep -q 'REPLACE_' && warn "$ENVFILE still contains a REPLACE_ placeholder: edit it (nano $ENVFILE) before the first deploy."
grep -Eq '^DEVFEST_(SEED|DEVELOPMENT)=true' "$ENVFILE" && warn "$ENVFILE has DEVFEST_SEED or DEVFEST_DEVELOPMENT set to true. Production must have both false."

# ---------------------------------------------------------------------------------------------------------------
say "7. Scripts, systemd units, nginx site"
install -m 0755 -o root -g root "$HERE/common.sh" "$HERE/deploy.sh" "$HERE/rollback.sh" "$HERE/backup.sh" "$HERE/restore.sh" "$ROOT/bin/"
printf 'DOMAIN=%s\n' "$DOMAIN" > "$ROOT/bin/deploy.conf"
install -m 0644 "$HERE/devfest-backend.service" "$HERE/devfest-backup.service" "$HERE/devfest-backup.timer" /etc/systemd/system/
systemctl daemon-reload
systemctl enable devfest-backend > /dev/null 2>&1          # starts at boot; the first deploy starts it now
systemctl enable --now devfest-backup.timer > /dev/null 2>&1

install -d /var/log/nginx
rm -f /etc/nginx/sites-enabled/default
if [ -f "/etc/letsencrypt/live/$DOMAIN/fullchain.pem" ]; then
  SITE="$HERE/nginx-site.conf"; echo "Certificate found: installing the full HTTPS site."
else
  SITE="$HERE/nginx-site-http-only.conf"; echo "No certificate yet: installing the HTTP-only site (run certbot next, then this script again)."
fi
sed "s/REPLACE_DOMAIN/$DOMAIN/g" "$SITE" > /etc/nginx/sites-available/devfest
ln -sfn /etc/nginx/sites-available/devfest /etc/nginx/sites-enabled/devfest
nginx -t
systemctl enable nginx > /dev/null 2>&1
systemctl reload nginx || systemctl restart nginx
# After every certificate renewal nginx must reload to use the new files.
install -d /etc/letsencrypt/renewal-hooks/deploy
printf '#!/bin/sh\nsystemctl reload nginx\n' > /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh
chmod 755 /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh

# ---------------------------------------------------------------------------------------------------------------
say "8. Firewall (ufw): only SSH ($SSH_PORT), 80 and 443"
ufw default deny incoming > /dev/null
ufw default allow outgoing > /dev/null
ufw allow "$SSH_PORT/tcp" > /dev/null
ufw allow 80/tcp > /dev/null
ufw allow 443/tcp > /dev/null
ufw --force enable > /dev/null
ufw status | sed 's/^/   /'

# ---------------------------------------------------------------------------------------------------------------
say "9. Automatic security updates and fail2ban"
cat > /etc/apt/apt.conf.d/20auto-upgrades <<'EOF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
EOF
# No automatic reboots: a reboot in the middle of the event is worse than a pending kernel update.
cat > /etc/apt/apt.conf.d/52devfest-unattended <<'EOF'
Unattended-Upgrade::Automatic-Reboot "false";
EOF
cat > /etc/fail2ban/jail.d/devfest-sshd.local <<EOF
[sshd]
enabled = true
port = $SSH_PORT
maxretry = 5
findtime = 10m
bantime = 1h
EOF
systemctl enable fail2ban > /dev/null 2>&1
systemctl restart fail2ban

# ---------------------------------------------------------------------------------------------------------------
say "10. SSH: turn off password login (only if a key is installed)"
has_key=no
for f in /root/.ssh/authorized_keys /home/*/.ssh/authorized_keys; do
  [ -f "$f" ] && grep -Eq '^(ssh-(rsa|ed25519)|ecdsa-sha2-|sk-)' "$f" && has_key=yes
done
if [ "$has_key" = yes ]; then
  cat > /etc/ssh/sshd_config.d/00-devfest-hardening.conf <<'EOF'
# Managed by setup-server.sh. Sorted first so it wins over cloud-init's 50-cloud-init.conf.
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin prohibit-password
EOF
  if sshd -t; then
    systemctl reload ssh 2>/dev/null || systemctl reload sshd
    echo "Password login is now off (keys only). KEEP THIS WINDOW OPEN and test a second SSH window before closing it."
  else
    rm -f /etc/ssh/sshd_config.d/00-devfest-hardening.conf
    warn "sshd rejected the new settings; password login was left as it was."
  fi
else
  warn "No SSH public key found in any authorized_keys file: password login was LEFT ON. Add your key (guide section 1), then run this script again."
fi

# ---------------------------------------------------------------------------------------------------------------
say "Done"
cat <<EOF
Domain          : $DOMAIN
Backend config  : $ENVFILE   (chmod 600, owner devfest)
Scripts         : $ROOT/bin/   (deploy.sh, rollback.sh, backup.sh, restore.sh)
Backups         : $ROOT/backups   (timer: every 10 minutes)
Next            : upload a release zip to $ROOT/releases/ and run  $ROOT/bin/deploy.sh <zip>   (guide section 3)
EOF
