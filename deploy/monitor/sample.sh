#!/bin/sh
# One sample a minute of the host and of each container, plus immediate alerts.
set -u
. /usr/local/bin/lib.sh
now=$(date +%s)

# CPU, from the difference with the previous sample
set -- $(sed -n 's/^cpu  *//p' /proc/stat)
total=0; for v in "$@"; do total=$((total + v)); done
idle=$(($4 + $5))
cpu=-
if [ -f "$STATE/prev.cpu" ]; then
  read -r ptotal pidle < "$STATE/prev.cpu"
  dt=$((total - ptotal)); [ "$dt" -gt 0 ] && cpu=$(( (100 * (dt - (idle - pidle)) + dt / 2) / dt ))
fi
echo "$total $idle" > "$STATE/prev.cpu"

load=$(cut -d' ' -f1 /proc/loadavg)
eval "$(awk '/^MemTotal:/{t=$2} /^MemAvailable:/{a=$2} /^SwapTotal:/{st=$2} /^SwapFree:/{sf=$2}
  END{printf "mem=%d memmb=%d swapmb=%d\n", (t-a)*100/t+0.5, (t-a)/1024, (st-sf)/1024}' /proc/meminfo)"
temp=-
[ -r /sys/class/thermal/thermal_zone0/temp ] && temp=$(( ($(cat /sys/class/thermal/thermal_zone0/temp) + 500) / 1000 ))
disk=$(df -P /data | awk 'NR==2{sub("%","",$5); print $5}')
dev=$(disk_device); written=-
[ -n "$dev" ] && written=$(( $(awk '{print $7}' "/sys/block/$dev/stat") * 512 ))
thr=$(throttled)
echo "$now $cpu $load $mem $memmb $swapmb $temp $disk $written $thr" >> "$SAMPLES"

# Containers: cgroup v2 counters, names and restart counts from Docker's own files
for cfg in /containers/*/config.v2.json; do
  [ -f "$cfg" ] || continue
  id=$(basename "$(dirname "$cfg")")
  json=$(cat "$cfg")
  name=$(echo "$json" | sed -n 's/.*"Name":"\/\([^"]*\)".*/\1/p')
  restarts=$(echo "$json" | sed -n 's/.*"RestartCount":\([0-9]*\).*/\1/p')
  running=$(echo "$json" | grep -q '"Running":true' && echo 1 || echo 0)
  # Only the stack's services (restart: unless-stopped), not one-off "docker compose run" containers
  grep -q '"RestartPolicy":{"Name":"\(unless-stopped\|always\)"' "$(dirname "$cfg")/hostconfig.json" 2>/dev/null || continue
  cg=
  for d in /cgroup/system.slice/docker-$id.scope /cgroup/docker/$id; do [ -d "$d" ] && { cg=$d; break; }; done
  ccpu=-; cmem=-
  if [ -n "$cg" ]; then
    usage=$(awk '/^usage_usec/{print $2}' "$cg/cpu.stat")
    cmem=$(( $(cat "$cg/memory.current") / 1048576 ))
    if [ -f "$STATE/prev.$id" ]; then
      read -r pts pusage < "$STATE/prev.$id"
      el=$(( (now - pts) * 1000000 )); [ "$el" -gt 0 ] && ccpu=$(( (100 * (usage - pusage) + el / 2) / el ))
    fi
    echo "$now $usage" > "$STATE/prev.$id"
  fi
  echo "$now ${name:-$id} $ccpu $cmem ${restarts:-0} $running" >> "$CONTAINERS"
done
# Forget containers that no longer exist
for p in "$STATE"/prev.*; do
  id=${p##*/prev.}; [ "$id" = cpu ] || [ -d "/containers/$id" ] || rm -f "$p"
done

# Alerts, at most one per kind every ALERT_COOLDOWN_HOURS
alert() { # key subject body
  f="$STATE/alert.$1"
  if [ -f "$f" ] && [ $((now - $(cat "$f"))) -lt $(( ${ALERT_COOLDOWN_HOURS:-6} * 3600 )) ]; then return; fi
  echo "$now" > "$f"
  echo "ALERT: $2"
  printf '%s\n\nÉtat au %s. Le rapport quotidien donne le détail.\n' "$3" "$(date '+%d/%m %H:%M')" | send_mail "[ASS Pi] Alerte : $2" || true
}
[ "$disk" -ge "${ALERT_DISK_PERCENT:-85}" ] && alert disk "disque plein à $disk %" \
  "Le disque des données est plein à $disk %. Libérez de la place (docker image prune -f, anciennes sauvegardes locales) avant qu'il soit plein : PostgreSQL s'arrête alors."
if [ "$temp" != - ] && [ "$temp" -ge "${ALERT_TEMP:-80}" ]; then
  alert temp "température $temp °C" "Le processeur est à $temp °C. À partir de 80-85 °C il ralentit. Vérifiez le ventilateur, le boîtier et l'aération."
fi
case "$thr" in -|0x0) ;; *)
  bits=$((thr & 0xF))
  if [ "$bits" -ne 0 ]; then
    why=""; [ $((bits & 1)) -ne 0 ] && why="$why tension trop faible (alimentation) ;"
    [ $((bits & 6)) -ne 0 ] && why="$why fréquence réduite ;"; [ $((bits & 8)) -ne 0 ] && why="$why limite de température ;"
    alert throttle "Pi bridé ($thr)" "Le Pi signale en ce moment :$why
Une tension trop faible vient presque toujours de l'alimentation : utilisez l'alimentation officielle 27 W du Pi 5."
  fi;;
esac
errors=$(storage_errors | wc -l)
known=$(cat "$STATE/errors.count" 2>/dev/null || echo 0)
if [ "$errors" -gt "$known" ]; then
  alert storage "erreurs du stockage" "Le noyau signale des erreurs du disque, souvent le signe d'une carte SD qui s'use :

$(storage_errors | tail -n 10)

Vérifiez la dernière sauvegarde et préparez une carte de rechange."
fi
[ "$errors" -lt "$known" ] && known=0  # after a reboot dmesg starts over
[ "$errors" -gt "$known" ] && known=$errors
echo "$known" > "$STATE/errors.count"
awk -v now="$now" '$1==now && $6==0 {print $2}' "$CONTAINERS" | while read -r name; do
  alert "down.$name" "$name est arrêté" "Le conteneur $name ne tourne plus. Sur le Pi : docker compose ps, puis docker compose logs $name."
done
exit 0
