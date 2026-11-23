#!/bin/sh
# Restore a backup into the database. Stop the backend first:
#   docker compose stop backend
#   docker compose run --rm -v ./age-key.txt:/key.txt:ro backup restore.sh [name]
#   docker compose start backend
# Without a name, the newest daily backup is used. "restore.sh --list" lists them.
set -eu
: "${BACKUP_REMOTE:?BACKUP_REMOTE is not set}"
key=${AGE_KEY_FILE:-/key.txt}

if [ "${1:-}" = --list ]; then
  for d in daily weekly monthly; do rclone lsf "$BACKUP_REMOTE/$d" 2> /dev/null | sed "s|^|$d/|"; done
  exit 0
fi
[ -r "$key" ] || { echo "Private key not found at $key (mount it with -v ./age-key.txt:/key.txt:ro)" >&2; exit 1; }

if [ -n "${1:-}" ]; then
  case "$1" in */*) path=$1;; *) path=daily/$1;; esac
else
  latest=$(rclone lsf "$BACKUP_REMOTE/daily" | sort | tail -1)
  [ -n "$latest" ] || { echo "No backup found in $BACKUP_REMOTE/daily" >&2; exit 1; }
  path=daily/$latest
fi

tmp=/tmp/restore.dump
trap 'rm -f "$tmp" "$tmp.age"' EXIT
# On B2 a missing file looks like an empty folder, so rclone wouldn't fail: check first
if [ -z "$(rclone lsf "$BACKUP_REMOTE/$path" 2> /dev/null)" ]; then
  echo "Backup not found: $path. Available backups:" >&2
  "$0" --list >&2
  exit 1
fi
echo "Downloading $path"
rclone copyto "$BACKUP_REMOTE/$path" "$tmp.age"
[ -s "$tmp.age" ] || { echo "Download failed: $path" >&2; exit 1; }
age -d -i "$key" -o "$tmp" "$tmp.age"

if [ -n "$(psql -tAc "select 1 from pg_stat_activity where datname = current_database() and application_name like 'PostgreSQL JDBC%' limit 1")" ]; then
  echo "The backend is still connected: run 'docker compose stop backend' first" >&2
  exit 1
fi

echo "Restoring into database $PGDATABASE (existing tables are replaced)"
pg_restore --clean --if-exists --no-owner --single-transaction -d "$PGDATABASE" "$tmp"
echo "Restore OK. Start the backend again: docker compose start backend"
