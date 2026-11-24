#!/bin/sh
# Daily report of the last 24 hours: averages and peaks, storage wear, the stack's state.
set -u
. /usr/local/bin/lib.sh
now=$(date +%s)
from=$((now - 86400))
host=${REPORT_HOST_NAME:-$(cat /etc/hostname)}
attention=""
warn() { attention="$attention
  - $1"; }

[ -s "$SAMPLES" ] || { echo "No samples yet" >&2; exit 1; }

# avg/peak NAME FIELD UNIT: "moyenne X, pic Y à HH:MM"
stats() {
  awk -v from="$from" -v f="$2" '$1>=from && $f!="-" {n++; s+=$f; if (n==1 || $f>m) {m=$f; t=$1}}
    END{ if (n) printf "%s %s %d\n", (s/n), m, t; else print "- - 0" }' "$SAMPLES"
}
line() { # label field unit decimals
  set -- "$1" "$2" "$3" "$4" $(stats "$1" "$2")
  if [ "$5" = - ]; then printf '  %s : pas de mesure\n' "$1"; return; fi
  printf "  %s : moyenne %.${4}f%s, pic %.${4}f%s le %s\n" "$1" "$5" "$3" "$6" "$3" "$(hhmm "$7")"
}

samples=$(awk -v from="$from" '$1>=from' "$SAMPLES" | wc -l)
read -r _ cpu load mem memmb swapmb temp disk written thr <<LAST
$(tail -n 1 "$SAMPLES")
LAST
memtotal=$(awk '/^MemTotal:/{printf "%d", $2/1024}' /proc/meminfo)
up=$(awk '{d=int($1/86400); h=int($1%86400/3600); printf "%d j %d h", d, h}' /proc/uptime)

# Storage wear: bytes written over the window (the counter restarts at each boot)
first_written=$(awk -v from="$from" '$1>=from && $9!="-" {print $9; exit}' "$SAMPLES")
dev=$(disk_device)
if [ "$written" != - ] && [ -n "$first_written" ] && [ "$written" -ge "$first_written" ]; then
  wear=$(awk -v w=$((written - first_written)) -v s="$samples" 'BEGIN{
    day = s >= 60 ? w * 1440 / s : w
    printf "%.2f Go écrits sur 24 h, soit environ %.0f Go par an", w/1e9, day*365/1e9 }')
else
  wear="pas de comparaison possible (redémarrage pendant la période)"
fi
errors=$(storage_errors)
[ "$disk" -ge "${ALERT_DISK_PERCENT:-85}" ] && warn "disque plein à $disk %"
[ -n "$errors" ] && warn "le noyau signale des erreurs du stockage (voir plus bas)"

# Throttling since boot: the sticky bits (16-19)
throttle="non mesuré (pas un Raspberry Pi ?)"
if [ "$thr" != - ]; then
  sticky=$(( (thr >> 16) & 0xF ))
  if [ "$sticky" -eq 0 ]; then throttle="aucun depuis le démarrage"; else
    throttle="depuis le démarrage :"
    [ $((sticky & 1)) -ne 0 ] && throttle="$throttle tension trop faible ;"
    [ $((sticky & 6)) -ne 0 ] && throttle="$throttle fréquence réduite ;"
    [ $((sticky & 8)) -ne 0 ] && throttle="$throttle limite de température ;"
    warn "Pi bridé $throttle"
  fi
fi

# Containers: avg/peak CPU and memory, restarts over the window
container_stats() {
  # Parentheses around comparisons: in printf, a bare > is a redirection
  awk -v from="$from" '$1>=from {
    n[$2]++; if ($3!="-") {c[$2]+=$3; cn[$2]++; if ($3>cm[$2]) cm[$2]=$3}
    if ($4!="-") {m[$2]+=$4; mn[$2]++; if ($4>mm[$2]) mm[$2]=$4}
    if (!($2 in r0)) r0[$2]=$5; r1[$2]=$5; run[$2]=$6 }
  END { for (k in n) printf "  %-22s CPU %4.1f %% (pic %3d %%)  RAM %4d Mo (pic %4d Mo)%s%s\n", k,
      cn[k] ? c[k]/cn[k] : 0, cm[k], mn[k] ? m[k]/mn[k] : 0, mm[k],
      (r1[k] > r0[k] ? sprintf("  %d redémarrage(s)", r1[k]-r0[k]) : ""), (run[k] ? "" : "  ARRÊTÉ") }' "$CONTAINERS"
}
containers=$(container_stats | sort)
echo "$containers" | grep -q "ARRÊTÉ" && warn "un conteneur est arrêté"
echo "$containers" | grep -q "redémarrage" && warn "un conteneur a redémarré"

# The service itself
db_size=$(psql -tAc "select pg_size_pretty(pg_database_size(current_database()))" 2>/dev/null || echo "?")
views=$(psql -tAc "select count(*) from pagesviews where \"timestamp\" >= $((from * 1000))" 2>/dev/null || echo "?")
backup="aucune information (le service backup écrit backups/last-backup après chaque sauvegarde réussie)"
if [ -r /backups/last-backup ]; then
  read -r bts bname bsize < /backups/last-backup
  backup="$bname ($bsize), le $(hhmm "$bts")"
  [ $((now - bts)) -gt $((36 * 3600)) ] && warn "pas de sauvegarde réussie depuis plus de 36 h"
fi

status="tout va bien"; [ -n "$attention" ] && status="$(echo "$attention" | grep -c .) point(s) d'attention"
{
  echo "Rapport de $host, du $(hhmm "$from") au $(hhmm "$now") ($samples mesures)."
  [ -n "$attention" ] && printf '\nÀ regarder :%s\n' "$attention"
  echo
  echo "MACHINE (allumée depuis $up)"
  line "CPU" 2 " %" 0
  line "Charge" 3 "" 2
  line "RAM" 4 " %" 0
  line "Swap" 6 " Mo" 0
  line "Température" 7 " °C" 0
  echo "  Maintenant : CPU $cpu %, RAM $memmb / $memtotal Mo, swap $swapmb Mo, $temp °C"
  echo "  Bridage : $throttle"
  echo
  echo "STOCKAGE ($dev)"
  echo "  Espace : $disk % utilisé ($(df -Ph /data | awk 'NR==2{print $3 " sur " $2}'))"
  echo "  Écritures : $wear"
  echo "  Une bonne carte SD de 32 Go supporte en général plusieurs To d'écritures : au-dessous de 100 Go par an, l'usure n'est pas un souci."
  if [ -n "$errors" ]; then echo "  Erreurs signalées par le noyau :"; echo "$errors" | tail -n 10 | sed 's/^/    /'; else echo "  Erreurs : aucune"; fi
  echo
  echo "CONTENEURS (moyenne et pic sur 24 h)"
  echo "${containers:-  aucune mesure}"
  echo
  echo "SERVICE"
  echo "  Vues reçues sur 24 h : $views"
  echo "  Taille de la base : $db_size"
  echo "  Dernière sauvegarde : $backup"
} > /tmp/report.txt

if send_mail "[ASS Pi] Rapport du $(date '+%d/%m') : $status" < /tmp/report.txt; then
  echo "Report sent: $status"
  [ -n "${MONITOR_HEALTHCHECK_URL:-}" ] && curl -fsS -m 10 --retry 3 "$MONITOR_HEALTHCHECK_URL" > /dev/null || true
else
  cat /tmp/report.txt
fi

# Keep two days of samples
for f in "$SAMPLES" "$CONTAINERS"; do
  awk -v keep=$((now - 2 * 86400)) '$1>=keep' "$f" > "$f.tmp" && mv "$f.tmp" "$f"
done
exit 0
