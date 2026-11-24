# Shared by the monitoring scripts.
STATE=/state                  # samples and alert state, on disk (a few hundred KB)
SAMPLES=$STATE/samples        # ts cpu% load1 mem% memMB swapMB temp°C disk% writtenBytes throttled
CONTAINERS=$STATE/containers  # ts name cpu% memMB restarts running
mkdir -p "$STATE"

write_msmtp_config() {
  [ -n "${SMTP_HOST:-}" ] || return 0
  tls=on; starttls=off
  case "${SMTP_TLS:-ssl}" in starttls) starttls=on;; off) tls=off;; esac
  cat > /etc/msmtprc <<CONF
defaults
logfile -
account default
host $SMTP_HOST
port ${SMTP_PORT:-465}
tls $tls
tls_starttls $starttls
tls_trust_file /etc/ssl/certs/ca-certificates.crt
from ${MAIL_FROM:-$SMTP_USER}
CONF
  if [ -n "${SMTP_USER:-}" ]; then
    printf 'auth on\nuser %s\npassword %s\n' "$SMTP_USER" "$SMTP_PASSWORD" >> /etc/msmtprc
  fi
  chmod 600 /etc/msmtprc
}

# send_mail SUBJECT < body
send_mail() {
  if [ -z "${SMTP_HOST:-}" ] || [ -z "${MAIL_TO:-}" ]; then
    echo "Mail not sent (SMTP_HOST or MAIL_TO not set): $1" >&2; cat > /dev/null; return 1
  fi
  {
    printf 'To: %s\nFrom: %s\nSubject: =?UTF-8?B?%s?=\nMIME-Version: 1.0\nContent-Type: text/plain; charset=UTF-8\nContent-Transfer-Encoding: 8bit\nDate: %s\n\n' \
      "$MAIL_TO" "${MAIL_FROM:-$SMTP_USER}" "$(printf '%s' "$1" | base64 | tr -d '\n')" "$(date -R)"
    cat
  } | msmtp -t
}

# The disk the data lives on: DISK_DEVICE, else the first of the usual ones
disk_device() {
  if [ -n "${DISK_DEVICE:-}" ]; then echo "$DISK_DEVICE"; return; fi
  for d in mmcblk0 nvme0n1 sda vda; do [ -e "/sys/block/$d/stat" ] && { echo "$d"; return; }; done
}

# Raspberry Pi firmware throttling flags (hex), or "-" off a Pi
throttled() {
  f=/sys/devices/platform/soc/soc:firmware/get_throttled
  [ -r "$f" ] && printf '0x%x\n' "0x$(cat "$f")" 2>/dev/null || echo -
}

# Kernel messages about the storage: I/O errors, filesystem errors, read-only remounts
storage_errors() {
  dmesg 2>/dev/null | grep -Ei 'mmc[0-9].*(error|timeout|fail)|I/O error|EXT4-fs (error|warning)|remounting filesystem read-only|nvme.*(error|timeout)' || true
}

hhmm() { date -d "@$1" '+%d/%m %H:%M'; }
