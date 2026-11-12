#!/usr/bin/env bash
# Installs a release zip made by build-release.ps1.
#   usage: deploy.sh /srv/devfest/releases/devfest-20261112-ab12cd3.zip
# Steps: unpack -> back up the database -> keep the old release as "previous" -> swap in the new jar and
# frontend -> restart -> wait for /api/v1/health = UP. Run as root.
set -euo pipefail
. "$(dirname "$(readlink -f "$0")")/common.sh"

need_root
ZIP="${1:-}"
[ -n "$ZIP" ] && [ -f "$ZIP" ] || die "Usage: deploy.sh /srv/devfest/releases/<release>.zip"
ID="$(basename "$ZIP" .zip)"
case "$ID" in *[!A-Za-z0-9._-]*|.*) die "Odd release name '$ID'. Use the file name that build-release.ps1 made." ;; esac
[ -d "$DEVFEST_ROOT/backend" ] && [ -f "$DEVFEST_ROOT/backend/.env" ] || die "Server not set up: run setup-server.sh first (no $DEVFEST_ROOT/backend/.env)."

OLD="$(current_release)"
if [ "$OLD" = "$ID" ]; then
  die "Release $ID is already the running one. To redeploy, build a new zip (or run rollback.sh)."
fi

log "1/6 Unpacking $ZIP"
STAGE="$RELEASES/.staging-$ID"
rm -rf "$STAGE"
mkdir -p "$STAGE"
command -v unzip >/dev/null || die "unzip is missing: apt-get install -y unzip"
unzip -q "$ZIP" -d "$STAGE"
[ -f "$STAGE/devfest-backend.jar" ] || die "The zip has no devfest-backend.jar at its top level."
[ -f "$STAGE/frontend/index.html" ] || die "The zip has no frontend/index.html."
unzip -tq "$STAGE/devfest-backend.jar" >/dev/null || die "devfest-backend.jar is damaged (upload it again)."

log "2/6 Backing up the database (label: predeploy)"
BACKUP_FILE="$("$DEVFEST_ROOT/bin/backup.sh" predeploy | tail -n 1)"
log "    saved: $BACKUP_FILE"

log "3/6 Installing release $ID"
rm -rf "${RELEASES:?}/$ID"
mv "$STAGE" "$RELEASES/$ID"
chown -R root:root "$RELEASES/$ID"
chmod -R u=rwX,go=rX "$RELEASES/$ID"
printf '%s\n' "$BACKUP_FILE" > "$RELEASES/$ID/PREDEPLOY_BACKUP"
# The zip stays next to the folder so it can be re-extracted; it is pruned with its folder.
printf '%s\n' "$ID" > "$RELEASES/CURRENT.new"
if [ -n "$OLD" ] && [ -d "$RELEASES/$OLD" ]; then
  printf '%s\n' "$OLD" > "$RELEASES/PREVIOUS"
fi
mv -f "$RELEASES/CURRENT.new" "$RELEASES/CURRENT"

log "4/6 Switching the jar and the frontend (previous release: ${OLD:-none})"
activate_release "$ID"

log "5/6 Restarting and waiting for health"
if ! restart_and_check; then
  warn "Release $ID did NOT start. The previous release is still on disk."
  [ -n "$OLD" ] && warn "Go back with:  sudo $DEVFEST_ROOT/bin/rollback.sh   (see docs/DEPLOYMENT.md section 5)"
  exit 1
fi

log "6/6 Checking the site through nginx and tidying old releases"
check_public || true
# Keep the current release, the previous one and the two newest others.
keep="$ID $OLD"
for d in $(ls -1dt "$RELEASES"/*/ 2>/dev/null | xargs -r -n1 basename | head -n 4); do keep="$keep $d"; done
for d in "$RELEASES"/*/; do
  name="$(basename "$d")"
  case " $keep " in *" $name "*) ;; *) log "    removing old release $name"; rm -rf "${RELEASES:?}/$name"; rm -f "$RELEASES/$name.zip" ;; esac
done

echo
echo "DEPLOY OK"
echo "  release  : $ID"
echo "  previous : ${OLD:-none}"
echo "  health   : $HEALTH_BODY"
echo "  db backup: $BACKUP_FILE"
