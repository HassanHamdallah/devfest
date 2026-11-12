#!/usr/bin/env bash
# Restores a pg_dump -Fc backup into a FRESH database. Asks for confirmation first. Run as root.
#
#   restore.sh DUMPFILE            practice run: restores into a scratch database "devfest_restore_check",
#                                  prints table row counts, drops it. The live site is not touched.
#   restore.sh DUMPFILE --live     REPLACES the live data: restores into a fresh database next to the live one;
#                                  only if that worked, stops the backend, renames live -> devfest_before_restore_<time>,
#                                  renames the fresh one to "devfest", starts the backend. The old data stays (renamed).
set -euo pipefail
. "$(dirname "$(readlink -f "$0")")/common.sh"

need_root
DUMP="${1:-}"
MODE="${2:-check}"
[ -n "$DUMP" ] && [ -f "$DUMP" ] || die "Usage: restore.sh /srv/devfest/backups/<file>.dump [--live]"
case "$MODE" in check|--live) ;; *) die "Unknown option '$MODE'. Use --live or nothing." ;; esac
DUMP="$(readlink -f "$DUMP")"
as_postgres pg_restore --list "$DUMP" > /dev/null || die "$DUMP is not a readable pg_dump custom-format file."

STAMP="$(date -u +%Y%m%d%H%M%S)"
if [ "$MODE" = "--live" ]; then FRESH="devfest_restoring_$STAMP"; else FRESH="devfest_restore_check"; fi

echo "Dump file   : $DUMP ($(du -h "$DUMP" | cut -f1))"
if [ "$MODE" = "--live" ]; then
  echo "This will REPLACE the live database '$DB_NAME' with that dump. The site is down for about a minute."
  echo "The current live data is kept under the name devfest_before_restore_$STAMP."
  printf "Type RESTORE LIVE to continue: "
  read -r answer < /dev/tty
  [ "$answer" = "RESTORE LIVE" ] || die "Cancelled."
else
  echo "This restores into a scratch database '$FRESH' (deleted afterwards). The live site is not touched."
  printf "Type yes to continue: "
  read -r answer < /dev/tty
  [ "$answer" = "yes" ] || die "Cancelled."
fi

db_exists() { [ "$(as_postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='$1'")" = "1" ]; }

if db_exists "$FRESH"; then
  log "Removing the old scratch database $FRESH"
  as_postgres dropdb "$FRESH"
fi
log "Creating fresh database $FRESH (owner $DB_ROLE)"
as_postgres createdb --owner="$DB_ROLE" --encoding=UTF8 "$FRESH"

log "Restoring (this can take a minute) ..."
# --role makes the restored tables belong to $DB_ROLE, which is what the application and Flyway expect.
if ! as_postgres pg_restore --no-owner --no-privileges --role="$DB_ROLE" --dbname="$FRESH" "$DUMP"; then
  as_postgres dropdb --if-exists "$FRESH" || true
  die "pg_restore reported errors (above). The live database was NOT changed and $FRESH was removed."
fi

as_postgres psql -d "$FRESH" -tAc "ANALYZE" >/dev/null 2>&1 || true
log "Row counts in the restored copy (largest 15 tables):"
as_postgres psql -d "$FRESH" -c "SELECT relname AS table, n_live_tup AS approx_rows FROM pg_stat_user_tables ORDER BY n_live_tup DESC LIMIT 15" || true
as_postgres psql -d "$FRESH" -c "SELECT (SELECT count(*) FROM app_user) AS accounts, (SELECT max(version) FROM flyway_schema_history WHERE success) AS last_migration" || warn "Could not read app_user/flyway_schema_history."

if [ "$MODE" != "--live" ]; then
  as_postgres dropdb "$FRESH"
  echo
  echo "RESTORE CHECK OK: the dump restores cleanly. Scratch database removed."
  exit 0
fi

OLD="devfest_before_restore_$STAMP"
log "Stopping the backend"
systemctl stop "$SERVICE"
as_postgres psql -tAc "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname IN ('$DB_NAME','$FRESH') AND pid <> pg_backend_pid()" > /dev/null
log "Renaming $DB_NAME -> $OLD and $FRESH -> $DB_NAME"
as_postgres psql -v ON_ERROR_STOP=1 -c "ALTER DATABASE \"$DB_NAME\" RENAME TO \"$OLD\"" -c "ALTER DATABASE \"$FRESH\" RENAME TO \"$DB_NAME\""
log "Starting the backend"
if restart_and_check; then
  check_public || true
  echo
  echo "RESTORE LIVE OK. Health: $HEALTH_BODY"
  echo "The data from before the restore is kept as database $OLD."
  echo "When you are sure you do not need it:  sudo -u postgres dropdb $OLD"
else
  die "The backend did not start on the restored data. The previous data is still there as $OLD: see docs/DEPLOYMENT.md section 6 ('Restore went wrong')."
fi
