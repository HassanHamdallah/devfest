#!/usr/bin/env bash
# First-time (and repeatable) setup of the DevFest server on a fresh Ubuntu LTS machine (amd64 or arm64, for example a
# DigitalOcean Droplet or an Oracle Cloud Always Free Ampere A1 instance). Run with root rights, from the folder that holds
# the other deploy files:
#     cd ~/devfest-deploy && sudo bash setup-server.sh [--host SITE_ADDRESS] [--firewall auto|oracle|ufw|none]
#
#   --host      the public IPv4 address of the server (plain HTTP site, no certificate) or a domain name (HTTPS once a
#               certificate exists, plain HTTP until then). Also read from DEVFEST_HOST (DEVFEST_DOMAIN still works).
#               Asked for when missing.
#   --firewall  auto (default): Oracle Cloud is detected and gets iptables rules, every other host gets ufw.
#               oracle: iptables-persistent only (never ufw). ufw: ufw only. none: leave the firewall alone.
#
# Safe to run again: every step checks what already exists. It never prints or stores a password except into
# /srv/devfest/backend/.env (chmod 600).
set -euo pipefail

HERE="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
ROOT=/srv/devfest
export DEBIAN_FRONTEND=noninteractive

say()  { printf '\n==> %s\n' "$*"; }
warn() { printf 'WARNING: %s\n' "$*" >&2; }
die()  { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

HOST="${DEVFEST_HOST:-${DEVFEST_DOMAIN:-}}"
FW_MODE="${DEVFEST_FIREWALL:-auto}"
while [ $# -gt 0 ]; do
  case "$1" in
    --host)       [ $# -ge 2 ] || die "--host needs a value."; HOST="$2"; shift 2 ;;
    --host=*)     HOST="${1#*=}"; shift ;;
    --firewall)   [ $# -ge 2 ] || die "--firewall needs a value."; FW_MODE="$2"; shift 2 ;;
    --firewall=*) FW_MODE="${1#*=}"; shift ;;
    -h|--help)    sed -n '2,15p' "$0"; exit 0 ;;
    *)            die "Unknown option '$1'. Use --host and --firewall (see the top of this file)." ;;
  esac
done
case "$FW_MODE" in auto|oracle|ufw|none) ;; *) die "--firewall must be auto, oracle, ufw or none (got '$FW_MODE')." ;; esac

[ "$(id -u)" -eq 0 ] || die "Run with root rights: put 'sudo' in front (sudo bash setup-server.sh)."
for f in common.sh deploy.sh rollback.sh backup.sh restore.sh env.production.example devfest-backend.service \
         devfest-backup.service devfest-backup.timer nginx-site.conf nginx-site-http-only.conf nginx-app.conf; do
  [ -f "$HERE/$f" ] || die "Missing $HERE/$f. Upload the whole infrastructure/deploy folder."
done

# --- machine check: apt-based Ubuntu, 64-bit x86 or ARM ---------------------------------------------------------------
command -v apt-get > /dev/null || die "This script needs an apt-based system (Ubuntu)."
if ! grep -qi '^ID=ubuntu' /etc/os-release; then warn "This script was written for Ubuntu; this is not Ubuntu. Continuing."; fi
CODENAME="$(. /etc/os-release && echo "${VERSION_CODENAME:-}")"
UBUNTU_VERSION="$(. /etc/os-release && echo "${VERSION_ID:-?}")"
[ -n "$CODENAME" ] || die "Cannot read the Ubuntu release name from /etc/os-release."
ARCH="$(dpkg --print-architecture)"
case "$ARCH" in
  amd64|arm64) ;;
  *) die "Unsupported CPU architecture '$ARCH' (dpkg). This script supports amd64 (x86-64) and arm64 (aarch64)." ;;
esac
echo "Machine: Ubuntu $UBUNTU_VERSION ($CODENAME), $ARCH, $(awk '/MemTotal/{printf "%.1f GB RAM", $2/1048576}' /proc/meminfo)."

# True when a package has an installable version in the apt sources configured right now.
pkg_available() { apt-cache policy "$1" 2> /dev/null | grep -q 'Candidate: [0-9]'; }

is_oracle() {
  grep -qi 'OracleCloud' /sys/class/dmi/id/chassis_asset_tag 2> /dev/null && return 0
  grep -qi '^oracle' /sys/class/dmi/id/sys_vendor 2> /dev/null && return 0
  curl -fsS -m 2 -H 'Authorization: Bearer Oracle' http://169.254.169.254/opc/v2/instance/ > /dev/null 2>&1
}
if [ "$FW_MODE" = auto ]; then
  if is_oracle; then FW_MODE=oracle; else FW_MODE=ufw; fi
fi
echo "Firewall mode: $FW_MODE"

# Reads one line from the keyboard even when the script is piped. $1 = prompt, $2 = "secret" to hide typing.
ask() {
  local reply
  { : < /dev/tty; } 2> /dev/null || die "No terminal to ask '$1' on. Log in with a normal interactive SSH session, or pass the value as an option/environment variable."
  if [ "${2:-}" = secret ]; then read -r -s -p "$1" reply < /dev/tty; echo > /dev/tty; else read -r -p "$1" reply < /dev/tty; fi
  printf '%s' "$reply"
}

# ---------------------------------------------------------------------------------------------------------------
say "0. Site address (public IP for plain HTTP, or a domain name)"
if [ -z "$HOST" ] && [ -f "$ROOT/bin/deploy.conf" ]; then
  # An earlier run remembered the address; deploy.conf may be an old one that only has DOMAIN=.
  HOST="$( (SITE_HOST=""; DOMAIN=""; . "$ROOT/bin/deploy.conf"; echo "${SITE_HOST:-$DOMAIN}") )"
fi
if [ -z "$HOST" ]; then HOST="$(ask 'Public IPv4 address of this server (or a domain name, for example devfest.example.com): ')"; fi
HOST="$(printf '%s' "$HOST" | tr 'A-Z' 'a-z' | tr -d '[:space:]')"
IS_IP=no
if printf '%s' "$HOST" | grep -Eq '^([0-9]{1,3}\.){3}[0-9]{1,3}$'; then
  for octet in ${HOST//./ }; do [ "$octet" -le 255 ] || die "'$HOST' is not a valid IPv4 address."; done
  IS_IP=yes
  case "$HOST" in
    10.*|192.168.*|127.*|169.254.*|172.1[6-9].*|172.2[0-9].*|172.3[0-1].*)
      die "'$HOST' is a private address. Give the PUBLIC IP (on Oracle Cloud: Instance details -> Public IP address, not the private IP)." ;;
  esac
elif printf '%s' "$HOST" | grep -Eq '^([a-z0-9]([a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,}$'; then
  [ "$HOST" != "replace_domain" ] || die "Type your real domain name."
else
  die "'$HOST' is neither an IPv4 address nor a domain name."
fi
# HTTPS only with a domain AND a certificate for it; everything else is the plain HTTP site.
if [ "$IS_IP" = no ] && [ -f "/etc/letsencrypt/live/$HOST/fullchain.pem" ]; then SCHEME=https; else SCHEME=http; fi
ORIGIN="$SCHEME://$HOST"
echo "Site address: $ORIGIN"
if [ "$SCHEME" = http ]; then
  echo "  (plain HTTP: no certificate. See docs/DEPLOYMENT.md, \"Switching to HTTPS\", for the move to a domain.)"
fi
SSH_PORT="$(sshd -T 2> /dev/null | awk '/^port /{print $2; exit}')"
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
say "2. Base packages (nginx, certbot, fail2ban, unattended-upgrades, firewall tools for mode '$FW_MODE')"
apt-get update -q
apt-get install -y -q ca-certificates curl gnupg unzip openssl nginx certbot fail2ban unattended-upgrades postgresql-common
case "$FW_MODE" in
  ufw)    apt-get install -y -q ufw ;;
  oracle)
    # Do not let the package ask whether to save the current rules (answers are not asked in non-interactive mode).
    echo 'iptables-persistent iptables-persistent/autosave_v4 boolean false' | debconf-set-selections
    echo 'iptables-persistent iptables-persistent/autosave_v6 boolean false' | debconf-set-selections
    apt-get install -y -q iptables iptables-persistent netfilter-persistent ;;
esac

# ---------------------------------------------------------------------------------------------------------------
say "3. PostgreSQL 18"
# Ubuntu's own package first (26.04 ships postgresql-18 for amd64 and arm64); the official PGDG repository only when
# Ubuntu's archive has no postgresql-18.
if pkg_available postgresql-18; then
  echo "Using Ubuntu's own postgresql-18 package."
else
  echo "Ubuntu '$CODENAME' has no postgresql-18: adding the official PostgreSQL (PGDG) repository."
  /usr/share/postgresql-common/pgdg/apt.postgresql.org.sh -y
  pkg_available postgresql-18 || die "PostgreSQL 18 is not available for Ubuntu '$CODENAME' ($ARCH), neither from Ubuntu nor from apt.postgresql.org. Install PostgreSQL 18 by hand, then run this script again."
fi
apt-get install -y -q postgresql-18
PGCONF=/etc/postgresql/18/main/conf.d/devfest.conf
mkdir -p "$(dirname "$PGCONF")"
PGWANT='# Managed by setup-server.sh. PostgreSQL listens on this machine only.
listen_addresses = '"'127.0.0.1'"'
max_connections = 60
shared_buffers = 256MB
effective_cache_size = 768MB
work_mem = 8MB'
if [ "$(cat "$PGCONF" 2> /dev/null)" != "$PGWANT" ]; then
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
  if pkg_available openjdk-25-jre-headless; then
    echo "Installing Ubuntu's openjdk-25-jre-headless ($ARCH)"
    apt-get install -y -q openjdk-25-jre-headless
    # Another Java may still be the default 'java'; point it at 25.
    JAVA25="/usr/lib/jvm/java-25-openjdk-$ARCH/bin/java"
    if ! java_ok && [ -x "$JAVA25" ]; then update-alternatives --set java "$JAVA25" || true; fi
  else
    echo "Ubuntu '$CODENAME' has no openjdk-25 package: trying Eclipse Temurin 25 from packages.adoptium.net"
    install -d -m 0755 /etc/apt/keyrings
    curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor --yes -o /etc/apt/keyrings/adoptium.gpg
    echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $CODENAME main" > /etc/apt/sources.list.d/adoptium.list
    if ! apt-get update -q; then
      rm -f /etc/apt/sources.list.d/adoptium.list
      die "The Adoptium repository does not serve Ubuntu '$CODENAME'. Install a Java 25 runtime for $ARCH by hand (for example the aarch64/x64 JRE tarball from adoptium.net), then run this script again."
    fi
    pkg_available temurin-25-jre || die "temurin-25-jre is not offered for Ubuntu '$CODENAME' ($ARCH). Install a Java 25 runtime by hand, then run this script again."
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
# The person who ran sudo may copy backups to a laptop with scp without sudo: dumps are group-readable by 'postgres'.
BACKUP_READER="${SUDO_USER:-}"
if [ -n "$BACKUP_READER" ] && [ "$BACKUP_READER" != root ] && id "$BACKUP_READER" > /dev/null 2>&1; then
  usermod -aG postgres "$BACKUP_READER"
  echo "User '$BACKUP_READER' can read $ROOT/backups (group postgres; applies to NEW SSH sessions)."
fi

# ---------------------------------------------------------------------------------------------------------------
say "6. Database role, database and $ROOT/backend/.env"
ENVFILE="$ROOT/backend/.env"
set_var() {   # set_var FILE KEY VALUE : replaces the line KEY=... or appends it (value used verbatim, no shell or sed escaping)
  local tmp; tmp="$(mktemp)"
  V="$3" awk -v k="$2" 'BEGIN{FS=OFS="="; f=0} $1==k {print k "=" ENVIRON["V"]; f=1; next} {print} END{if(!f) print k "=" ENVIRON["V"]}' "$1" > "$tmp"
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
  set_var "$ENVFILE" DEVFEST_ORIGINS "$ORIGIN"
  set_var "$ENVFILE" DEVFEST_ADMIN_EMAIL "$ADMIN_EMAIL"
  set_var "$ENVFILE" DEVFEST_ADMIN_PASSWORD "$ADMIN_PASSWORD"
  echo "Wrote $ENVFILE"
fi
# The browser origin must match the address people type (CORS). Keep it in step when the address or scheme changes
# (for example http://IP -> https://domain). The backend is restarted below when this changes.
ORIGINS_CHANGED=no
if ! printf ',%s,' "$(get_var "$ENVFILE" DEVFEST_ORIGINS)" | grep -qF ",$ORIGIN,"; then
  set_var "$ENVFILE" DEVFEST_ORIGINS "$ORIGIN"
  ORIGINS_CHANGED=yes
  echo "DEVFEST_ORIGINS in $ENVFILE is now $ORIGIN"
fi
chown devfest:devfest "$ENVFILE"
chmod 600 "$ENVFILE"
grep -v '^#' "$ENVFILE" | grep -q 'REPLACE_' && warn "$ENVFILE still contains a REPLACE_ placeholder: edit it (sudo nano $ENVFILE) before the first deploy."
grep -Eq '^DEVFEST_(SEED|DEVELOPMENT)=true' "$ENVFILE" && warn "$ENVFILE has DEVFEST_SEED or DEVFEST_DEVELOPMENT set to true. Production must have both false."

# ---------------------------------------------------------------------------------------------------------------
say "7. Scripts, systemd units, nginx site"
install -m 0755 -o root -g root "$HERE/common.sh" "$HERE/deploy.sh" "$HERE/rollback.sh" "$HERE/backup.sh" "$HERE/restore.sh" "$ROOT/bin/"
printf 'SITE_HOST=%s\nSITE_SCHEME=%s\n' "$HOST" "$SCHEME" > "$ROOT/bin/deploy.conf"
install -m 0644 "$HERE/devfest-backend.service" "$HERE/devfest-backup.service" "$HERE/devfest-backup.timer" /etc/systemd/system/
systemctl daemon-reload
systemctl enable devfest-backend > /dev/null 2>&1          # starts at boot; the first deploy starts it now
systemctl enable --now devfest-backup.timer > /dev/null 2>&1

install -d /var/log/nginx /etc/nginx/snippets
install -m 0644 "$HERE/nginx-app.conf" /etc/nginx/snippets/devfest-app.conf
rm -f /etc/nginx/sites-enabled/default
if [ "$SCHEME" = https ]; then
  SITE="$HERE/nginx-site.conf"; echo "Domain with certificate: installing the full HTTPS site."
else
  SITE="$HERE/nginx-site-http-only.conf"; echo "Installing the plain HTTP site (no HSTS, no redirect, no port 443)."
fi
sed "s/REPLACE_HOST/$HOST/g" "$SITE" > /etc/nginx/sites-available/devfest
ln -sfn /etc/nginx/sites-available/devfest /etc/nginx/sites-enabled/devfest
nginx -t
systemctl enable nginx > /dev/null 2>&1
systemctl reload nginx || systemctl restart nginx
# After every certificate renewal nginx must reload to use the new files.
install -d /etc/letsencrypt/renewal-hooks/deploy
printf '#!/bin/sh\nsystemctl reload nginx\n' > /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh
chmod 755 /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh
# New origin in .env: the running backend only reads it at start.
if [ "$ORIGINS_CHANGED" = yes ] && systemctl is-active --quiet devfest-backend; then
  systemctl restart devfest-backend
  echo "Restarted devfest-backend to pick up the new DEVFEST_ORIGINS."
fi

# ---------------------------------------------------------------------------------------------------------------
say "8. Firewall (mode: $FW_MODE): only SSH ($SSH_PORT), 80 and 443"
# Opens one TCP port in the INPUT chain of iptables/ip6tables, just before the REJECT rule that Oracle's images end
# the chain with (a rule appended after REJECT would never be reached). Does nothing when the port is already open.
iptables_open() {   # iptables_open iptables|ip6tables PORT
  local ipt="$1" port="$2" pos
  command -v "$ipt" > /dev/null || return 0
  if "$ipt" -C INPUT -p tcp -m state --state NEW -m tcp --dport "$port" -j ACCEPT 2> /dev/null; then return 0; fi
  pos="$("$ipt" -L INPUT -n --line-numbers 2> /dev/null | awk '$2=="REJECT"{print $1; exit}')"
  if [ -n "$pos" ]; then
    "$ipt" -I INPUT "$pos" -p tcp -m state --state NEW -m tcp --dport "$port" -j ACCEPT
    echo "   $ipt: opened tcp/$port (before the REJECT rule, position $pos)"
  elif "$ipt" -S INPUT 2> /dev/null | head -n1 | grep -q -- '-P INPUT DROP'; then
    "$ipt" -A INPUT -p tcp -m state --state NEW -m tcp --dport "$port" -j ACCEPT
    echo "   $ipt: opened tcp/$port (appended; the chain policy is DROP)"
  fi
}
case "$FW_MODE" in
  ufw)
    ufw default deny incoming > /dev/null
    ufw default allow outgoing > /dev/null
    ufw allow "$SSH_PORT/tcp" > /dev/null
    ufw allow 80/tcp > /dev/null
    ufw allow 443/tcp > /dev/null
    ufw --force enable > /dev/null
    ufw status | sed 's/^/   /' ;;
  oracle)
    if command -v ufw > /dev/null && ufw status 2> /dev/null | grep -qi '^Status: active'; then
      warn "ufw is active on this Oracle machine. It conflicts with the iptables rules: run 'sudo ufw disable' and this script again."
    fi
    for p in "$SSH_PORT" 80 443; do
      iptables_open iptables "$p"
      iptables_open ip6tables "$p"
    done
    # fail2ban adds its own chains to the live rules; they must not be written into the saved file, so stop it for the
    # save (step 9 starts it again).
    systemctl stop fail2ban > /dev/null 2>&1 || true
    netfilter-persistent save > /dev/null
    grep -Eq -- '--dport 80 .*-j ACCEPT' /etc/iptables/rules.v4 || warn "Port 80 is not in /etc/iptables/rules.v4 after saving: check 'sudo iptables -S INPUT'."
    grep -Eq -- '--dport 443 .*-j ACCEPT' /etc/iptables/rules.v4 || warn "Port 443 is not in /etc/iptables/rules.v4 after saving: check 'sudo iptables -S INPUT'."
    systemctl enable netfilter-persistent > /dev/null 2>&1 || true
    iptables -S INPUT | sed 's/^/   /' ;;
  none)
    echo "Firewall left alone (--firewall none). Ports $SSH_PORT, 80 and 443 must be open by other means." ;;
esac
if [ "$FW_MODE" = oracle ]; then
  echo "   Also needed in the Oracle console: security list / network security group ingress for tcp 80 and 443."
fi

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
    systemctl reload ssh 2> /dev/null || systemctl reload sshd 2> /dev/null || systemctl restart ssh 2> /dev/null || warn "Could not reload the SSH service; the new settings apply after the next reboot."
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
Site address    : $ORIGIN
Backend config  : $ENVFILE   (chmod 600, owner devfest)
Scripts         : $ROOT/bin/   (deploy.sh, rollback.sh, backup.sh, restore.sh)
Backups         : $ROOT/backups   (timer: every 10 minutes)
Next            : upload a release zip to $ROOT/releases/ and run  sudo $ROOT/bin/deploy.sh <zip>   (guide section 3)
EOF
