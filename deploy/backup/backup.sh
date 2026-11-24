#!/bin/sh
# Dump the database, encrypt it with age, upload it with rclone and delete old copies.
# Keeps 7 daily, 4 weekly (Sundays) and 6 monthly (1st of the month) backups.
set -eu

: "${AGE_RECIPIENT:?AGE_RECIPIENT is not set}"
: "${BACKUP_REMOTE:?BACKUP_REMOTE is not set}"
case "$BACKUP_REMOTE" in *:) echo "BACKUP_REMOTE has no bucket (set B2_BUCKET)" >&2; exit 1;; esac

ping() { [ -n "${HEALTHCHECK_URL:-}" ] && curl -fsS -m 10 --retry 3 "$HEALTHCHECK_URL$1" > /dev/null || true; }

name="ass-$(date +%Y-%m-%d-%H%M).dump.age"
tmp="/tmp/$name"
trap 'rm -f "$tmp"' EXIT
trap 'echo "Backup FAILED" >&2; ping /fail' INT TERM

ping /start
fail() { echo "Backup FAILED: $1" >&2; ping /fail; exit 1; }

# pg_dump -Fc is compressed; pipefail isn't available in sh, so check the dump separately
pg_dump -Fc > "$tmp.plain" || { rm -f "$tmp.plain"; fail "pg_dump"; }
age -r "$AGE_RECIPIENT" -o "$tmp" "$tmp.plain" || { rm -f "$tmp.plain"; fail "age"; }
rm -f "$tmp.plain"
size=$(du -h "$tmp" | cut -f1)

rclone copyto "$tmp" "$BACKUP_REMOTE/daily/$name" || fail "upload"
[ "$(date +%u)" = 7 ] && { rclone copyto "$tmp" "$BACKUP_REMOTE/weekly/$name" || fail "upload weekly"; }
[ "$(date +%d)" = 01 ] && { rclone copyto "$tmp" "$BACKUP_REMOTE/monthly/$name" || fail "upload monthly"; }

# Retention. Failures here don't fail the backup itself.
rclone delete --min-age 7d "$BACKUP_REMOTE/daily" || echo "Retention failed (daily)" >&2
rclone delete --min-age 28d "$BACKUP_REMOTE/weekly" 2> /dev/null || true
rclone delete --min-age 183d "$BACKUP_REMOTE/monthly" 2> /dev/null || true

echo "Backup OK: $name ($size)"
# Read by the monitoring report
echo "$(date +%s) $name $size" > /local/last-backup 2> /dev/null || true
ping ""
