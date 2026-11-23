#!/bin/sh
# cron (default): run backup.sh on BACKUP_SCHEDULE. Otherwise run the given command,
# e.g. "backup.sh" (backup now), "restore.sh" or "age-keygen".
set -eu
if [ "${1:-cron}" = cron ]; then
  echo "${BACKUP_SCHEDULE:-0 3 * * *} /usr/local/bin/backup.sh > /proc/1/fd/1 2>&1" > /etc/crontabs/root
  echo "Backups scheduled: ${BACKUP_SCHEDULE:-0 3 * * *} ($TZ) to ${BACKUP_REMOTE:-?}"
  exec crond -f -l 8
fi
exec "$@"
