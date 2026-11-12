#!/usr/bin/env bash
# Dumps the devfest database (pg_dump -Fc) into /srv/devfest/backups and keeps the newest files per label.
#   backup.sh            label "devfest"   (the timer uses this; keeps 144 = 24 h at 10 minutes)
#   backup.sh predeploy  label "predeploy" (deploy.sh uses this; keeps 20)
# Prints the path of the new file as its last line (everything else goes to stderr). Run as root.
set -euo pipefail
. "$(dirname "$(readlink -f "$0")")/common.sh"
exec 3>&1 1>&2   # log lines to stderr; fd 3 is the real stdout

need_root
LABEL="${1:-devfest}"
case "$LABEL" in *[!a-z0-9-]*|"") die "Label may only contain a-z, 0-9 and '-'." ;; esac
KEEP=20
[ "$LABEL" = "devfest" ] && KEEP=144

# One backup at a time. The timer gives up quickly if a deploy backup is running; a deploy waits its turn.
exec 9>/run/lock/devfest-backup.lock
WAIT=0; [ "$LABEL" = "devfest" ] || WAIT=300
flock -w "$WAIT" 9 || { warn "Another backup is running; skipping this one."; exit 0; }

[ -d "$BACKUPS" ] || die "$BACKUPS does not exist: run setup-server.sh."
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
FINAL="$BACKUPS/$LABEL-$STAMP.dump"
TMP="$BACKUPS/.$LABEL-$STAMP.tmp"

umask 027
trap 'rm -f "$TMP"' EXIT
as_postgres pg_dump --format=custom --dbname="$DB_NAME" --file="$TMP"
# A dump that cannot be listed is worthless: check before it replaces anything.
as_postgres pg_restore --list "$TMP" > /dev/null || die "The dump written to $TMP is unreadable; keeping older backups."
[ -s "$TMP" ] || die "The dump is empty."
mv "$TMP" "$FINAL"
trap - EXIT
[ "$LABEL" = "devfest" ] && ln -sfn "$FINAL" "$BACKUPS/latest.dump"

# Keep the newest $KEEP of this label (names sort by time because of the UTC stamp).
ls -1 "$BACKUPS"/"$LABEL"-2*.dump 2>/dev/null | sort -r | tail -n +"$((KEEP + 1))" | xargs -r rm -f --

log "Backup written: $FINAL ($(du -h "$FINAL" | cut -f1)); keeping the newest $KEEP '$LABEL' files."
echo "$FINAL" >&3
