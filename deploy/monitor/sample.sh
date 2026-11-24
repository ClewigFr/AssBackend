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

# Every 10 minutes: views dropped by the backend's flood limits, and unusual spikes per application
if [ $(( now / 60 % 10 )) -eq 0 ]; then
  hour_ago=$(( (now - 3600) * 1000 ))
  dropped=$(psql -tAF ' : ' -c "select a.name, sum(d.views) from viewsdropped d join apps a on a.id = d.app
    where d.hour >= $hour_ago - 3600000 group by a.name having sum(d.views) >= ${ALERT_DROPPED_VIEWS:-100}" 2>/dev/null)
  [ -n "$dropped" ] && alert flood "vues ignorées (flood ?)" "Le backend a ignoré des vues qui dépassaient ses limites anti-flood (trop de vues par IP ou par session) depuis une heure ou deux :

$dropped

Elles ne comptent pas dans les stats. Si ça continue, regardez la période dans le dashboard et supprimez-la au besoin (onglet Configuration > Supprimer des vues)."
  signups=$(psql -tAc "select count(*) from users where created_at >= $hour_ago" 2>/dev/null)
  [ "${signups:-0}" -ge "${ALERT_SIGNUPS:-20}" ] && alert signups "$signups inscriptions en une heure" "$signups comptes ont été créés depuis une heure. C'est peut-être un succès, ou des bots qui passent la protection de l'inscription.

Les comptes jamais vérifiés sont supprimés au bout d'un jour. Pour les voir sur le Pi :
docker compose exec postgres psql -U ass ass -c \"select email, email_verified, to_timestamp(created_at/1000) from users order by created_at desc limit 50\"

Pour fermer l'inscription le temps de regarder : REGISTRATION_OPEN=false dans .env, puis docker compose up -d backend."
  spikes=$(psql -tAF ' : ' -c "select a.name, count(*) filter (where v.\"timestamp\" >= $hour_ago) as last_hour,
      round(count(*) filter (where v.\"timestamp\" < $hour_ago) / 168.0) as usual
    from pagesviews v join apps a on a.id = v.app
    where v.\"timestamp\" >= $hour_ago - 7 * 86400000
    group by a.name
    having count(*) filter (where v.\"timestamp\" >= $hour_ago) >= greatest(${ALERT_SPIKE_MIN:-500}, 10 * count(*) filter (where v.\"timestamp\" < $hour_ago) / 168.0)" 2>/dev/null)
  [ -n "$spikes" ] && alert spike "pic de vues inhabituel" "Une application reçoit beaucoup plus de vues que d'habitude (application : vues sur la dernière heure : moyenne par heure sur 7 jours) :

$spikes

Ça peut être un vrai succès, ou un envoi de fausses vues. Les vues d'une période se suppriment dans le dashboard (onglet Configuration > Supprimer des vues)."
fi
exit 0
