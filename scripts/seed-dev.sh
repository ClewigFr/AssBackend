#!/bin/sh
# Seed a local dev backend (in-memory H2) with a test account, an application, pages and views.
# Usage: scripts/seed-dev.sh [base_url]   (default http://localhost:8099)
set -e
B=${1:-http://localhost:8099}
EMAIL=dev@ass.test
PASSWORD=devpass123
J=$(mktemp)
curl -s -c "$J" -d "email=$EMAIL&password=$PASSWORD&displayName=Dev" "$B/register" >/dev/null
curl -s -c "$J" -d "userId=$EMAIL&password=$PASSWORD" "$B/login" >/dev/null
S1=e6c8a676-b9b2-4108-b9b2-7b7871951ef6; S2=1f0e2d3c-4b5a-4968-8776-5a4b3c2d1e0f; S3=9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d
DAY=86400000
APP=$(curl -s -b "$J" -H 'Content-Type: application/json' -d '{"name":"Demo app"}' "$B/apps" | sed 's/.*"id":"\([^"]*\)".*/\1/')
for NAME in home settings profile; do
  ID=$(curl -s -b "$J" -H 'Content-Type: application/json' -d "{\"name\":\"$NAME\"}" "$B/apps/$APP/pages" | sed 's/.*"id":"\([^"]*\)".*/\1/')
  eval "P_$NAME=$ID"
done
post() { curl -s -o /dev/null -H 'Content-Type: application/json' -d "{\"app\":\"$APP\",\"pageViews\":[$1]}" "$B/views"; }
v() { echo "{\"page\":\"$1\",\"sessionId\":\"$2\",\"timestamp\":$3}"; }
post "$(v $P_home $S1 $((2*DAY))),$(v $P_settings $S1 $((2*DAY))),$(v $P_home $S2 $((1*DAY))),$(v $P_profile $S2 $((1*DAY))),$(v $P_home $S3 1000),$(v $P_home $S3 2000),$(v $P_settings $S3 3000)"
rm -f "$J"
echo "Seeded $B (login: $EMAIL, password in this script; app \"Demo app\" = $APP)"
