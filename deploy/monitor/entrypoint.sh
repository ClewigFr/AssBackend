#!/bin/sh
# cron (default): sample.sh every minute, report.sh on REPORT_SCHEDULE.
# Otherwise run the given command, e.g. "report.sh" (send a report now) or "test-mail.sh".
set -eu
. /usr/local/bin/lib.sh
write_msmtp_config
if [ "${1:-cron}" = cron ]; then
  {
    echo "* * * * * /usr/local/bin/sample.sh > /proc/1/fd/1 2>&1"
    echo "${REPORT_SCHEDULE:-0 8 * * *} /usr/local/bin/report.sh > /proc/1/fd/1 2>&1"
  } > /etc/crontabs/root
  echo "Monitoring: samples every minute, report ${REPORT_SCHEDULE:-0 8 * * *} ($TZ) to ${MAIL_TO:-nobody (MAIL_TO not set)}"
  exec crond -f -l 8
fi
exec "$@"
