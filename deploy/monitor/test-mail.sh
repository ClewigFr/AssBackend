#!/bin/sh
# Sends a test mail, to check the SMTP settings.
. /usr/local/bin/lib.sh
echo "Les mails du monitoring de $(cat /etc/hostname) arrivent bien." | send_mail "[ASS Pi] Test" && echo "Test mail sent to $MAIL_TO"
