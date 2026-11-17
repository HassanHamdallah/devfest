#!/usr/bin/env bash
# Shared helpers for deploy.sh, rollback.sh, backup.sh and restore.sh. Sourced, never run on its own.
# Installed by setup-server.sh into /srv/devfest/bin/.

DEVFEST_ROOT=/srv/devfest
RELEASES="$DEVFEST_ROOT/releases"
BACKUPS="$DEVFEST_ROOT/backups"
SERVICE=devfest-backend
HEALTH_URL=http://127.0.0.1:8081/api/v1/health
DB_NAME=devfest
DB_ROLE=devfest

log()  { printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$*"; }
warn() { printf '[%s] WARNING: %s\n' "$(date '+%H:%M:%S')" "$*" >&2; }
die()  { printf '[%s] ERROR: %s\n' "$(date '+%H:%M:%S')" "$*" >&2; exit 1; }

need_root() { [ "$(id -u)" -eq 0 ] || die "Run this as root: put 'sudo' in front (you are $(id -un))."; }

# Runs a command as the postgres OS user (peer authentication on the local socket, no password needed).
as_postgres() { runuser -u postgres -- "$@"; }

# Waits until the backend answers {"status":"UP"} on its local port. Returns 1 after $1 seconds (default 120).
wait_for_health() {
  local limit="${1:-120}" waited=0 body=""
  while [ "$waited" -lt "$limit" ]; do
    if body="$(curl -fsS --max-time 3 "$HEALTH_URL" 2>/dev/null)" && grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' <<< "$body"; then
      HEALTH_BODY="$body"
      return 0
    fi
    # A crash loop will not heal by waiting: stop early when systemd has given up or is in failed state.
    if systemctl is-failed --quiet "$SERVICE"; then
      return 1
    fi
    sleep 2
    waited=$((waited + 2))
  done
  return 1
}

# The release folder name that a symlink such as backend/devfest.jar currently points into ("" when none).
current_release() {
  local target
  target="$(readlink "$DEVFEST_ROOT/backend/devfest.jar" 2>/dev/null || true)"
  [ -n "$target" ] || return 0
  basename "$(dirname "$target")"
}

# Points the jar and the frontend at a release folder. Each swap is one atomic rename.
activate_release() {
  local id="$1" dir="$RELEASES/$1"
  [ -f "$dir/devfest-backend.jar" ] && [ -f "$dir/frontend/index.html" ] || die "Release $id is incomplete (needs devfest-backend.jar and frontend/index.html)."
  ln -sfn "$dir/devfest-backend.jar" "$DEVFEST_ROOT/backend/.devfest.jar.new"
  mv -Tf "$DEVFEST_ROOT/backend/.devfest.jar.new" "$DEVFEST_ROOT/backend/devfest.jar"
  ln -sfn "$dir/frontend" "$DEVFEST_ROOT/frontend/.current.new"
  mv -Tf "$DEVFEST_ROOT/frontend/.current.new" "$DEVFEST_ROOT/frontend/current"
}

# Restarts the service, waits for health, prints the verdict. Returns 1 when the backend did not come up.
restart_and_check() {
  log "Restarting $SERVICE ..."
  systemctl restart "$SERVICE"
  if wait_for_health 120; then
    log "Backend health: $HEALTH_BODY"
    return 0
  fi
  warn "The backend did not report UP. Last log lines:"
  journalctl -u "$SERVICE" -n 40 --no-pager >&2 || true
  return 1
}

# The public site address written by setup-server.sh into bin/deploy.conf:
#   SITE_HOST=<server IP or domain>   SITE_SCHEME=http|https
# (an older deploy.conf with only DOMAIN=<domain> counts as https).
load_site() {
  SITE_HOST=""; SITE_SCHEME=""; DOMAIN=""
  # shellcheck disable=SC1091
  [ -f "$DEVFEST_ROOT/bin/deploy.conf" ] && . "$DEVFEST_ROOT/bin/deploy.conf"
  [ -n "$SITE_HOST" ] || SITE_HOST="$DOMAIN"
  [ -n "$SITE_SCHEME" ] || SITE_SCHEME=https
  return 0
}

# Optional end-to-end check through nginx.
check_public() {
  load_site
  [ -n "$SITE_HOST" ] || { log "No site address recorded in bin/deploy.conf: skipping the check through nginx."; return 0; }
  local body url
  if [ "$SITE_SCHEME" = http ]; then
    url="http://$SITE_HOST/api/v1/health"
    body="$(curl -fsS --max-time 10 -H "Host: $SITE_HOST" http://127.0.0.1/api/v1/health 2>/dev/null)" || body=""
  else
    [ -f "/etc/letsencrypt/live/$SITE_HOST/fullchain.pem" ] || { log "HTTPS not set up yet: skipping the check through nginx."; return 0; }
    url="https://$SITE_HOST/api/v1/health"
    body="$(curl -fsS --max-time 10 --resolve "$SITE_HOST:443:127.0.0.1" "$url" 2>/dev/null)" || body=""
  fi
  if grep -q '"UP"' <<< "$body"; then
    log "Through nginx ($url): $body"
  else
    warn "$url did not answer UP through nginx. Check: nginx -t ; tail /var/log/nginx/error.log"
    return 1
  fi
}
