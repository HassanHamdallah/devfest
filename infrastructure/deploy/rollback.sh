#!/usr/bin/env bash
# Puts the previous release (jar + frontend) back and restarts. Run it again to go forward again.
# It does NOT touch the database. If the release you are leaving ran new database migrations, the older jar may not
# understand the newer tables: then also restore the pre-deploy backup (restore.sh --live, see docs/DEPLOYMENT.md).
set -euo pipefail
. "$(dirname "$(readlink -f "$0")")/common.sh"

need_root
CUR="$(current_release)"
PREV=""
[ -f "$RELEASES/PREVIOUS" ] && PREV="$(tr -d '[:space:]' < "$RELEASES/PREVIOUS")"
[ -n "$PREV" ] && [ -d "$RELEASES/$PREV" ] || die "There is no previous release to go back to (releases/PREVIOUS is missing or its folder was removed)."
[ "$PREV" != "$CUR" ] || die "The previous release is the running one; nothing to do."

log "Rolling back: $CUR  ->  $PREV"
activate_release "$PREV"
printf '%s\n' "$PREV" > "$RELEASES/CURRENT.new"; mv -f "$RELEASES/CURRENT.new" "$RELEASES/CURRENT"
printf '%s\n' "$CUR"  > "$RELEASES/PREVIOUS.new"; mv -f "$RELEASES/PREVIOUS.new" "$RELEASES/PREVIOUS"

if ! restart_and_check; then
  warn "The backend did not come up on $PREV either. Read the logs (journalctl -u $SERVICE -n 100) before trying again."
  exit 1
fi
check_public || true

echo
echo "ROLLBACK OK"
echo "  running  : $PREV"
echo "  left     : $CUR  (run rollback.sh again to return to it)"
echo "  health   : $HEALTH_BODY"
if [ -f "$RELEASES/$CUR/PREDEPLOY_BACKUP" ]; then
  echo "  database : unchanged. The backup taken just before $CUR was deployed is:"
  echo "             $(cat "$RELEASES/$CUR/PREDEPLOY_BACKUP")"
fi
