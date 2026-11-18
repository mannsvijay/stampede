#!/usr/bin/env bash
# Runs a k6 load scenario in Docker against the local stack (M2.4a, D-024).
#
#   ./scripts/k6.sh <scenario> [--strategy PESSIMISTIC|OPTIMISTIC|REDIS_FIRST] [--vus N] [--hot-seats N]
#                   [--warmup SECONDS] [--lead SECONDS] [--target compose|host]
#
# Scenarios: scripts/k6/<scenario>.js (smoke, seat-collision). Needs the stack running and seeded
# (`docker compose up -d --build --wait`); with --target host, the infrastructure plus bootRun on :8080.
#
# What one run does:
#   1. ends earlier k6 events and creates this run's warm-up and benchmark events (scripts/k6/sql)
#   2. registers the load-test admin (scripts/k6/prepare-admin.js) and gives it the ADMIN role in SQL
#   3. runs the scenario in a pinned k6 image, on the compose network (no Docker Desktop port forwarding
#      between k6 and the backend); setup() switches the benchmark event to --strategy before its drop
#      and logs the test users in; teardown() gets the strict invariant verdict (GET /admin/invariants)
#   4. samples pg_stat_activity (the app's connections: active, waiting on a lock) and `docker stats`
#      while it runs
#   5. checks that the database sold exactly the seats k6 counted, ends the run's events, and writes
#      everything to benchmarks/runs/<UTC time>-<scenario>-<strategy>/ (gitignored)
# Exit status: 0 only if k6's thresholds passed and the database check agrees.
#
# Never reads .env: SQL runs inside the postgres container with that container's own credentials, and
# the load-test admin's password is generated here (benchmarks/runs/.k6-admin-password).
set -euo pipefail

K6_IMAGE="grafana/k6:1.8.2@sha256:00fdcebafbc86cb0137d429529b1caa401a02e09025f02fc74866fa6e2b004d8"
ADMIN_EMAIL="k6-admin@load.test"

usage() {
  sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'
  exit 2
}

[ $# -ge 1 ] || usage
SCENARIO=$1
shift
STRATEGY=PESSIMISTIC
VUS=500
HOT_SEATS=100
WARMUP=30
LEAD=180
TARGET=compose
while [ $# -gt 0 ]; do
  case "$1" in
    --strategy) STRATEGY=${2:?}; shift 2 ;;
    --vus) VUS=${2:?}; shift 2 ;;
    --hot-seats) HOT_SEATS=${2:?}; shift 2 ;;
    --warmup) WARMUP=${2:?}; shift 2 ;;
    --lead) LEAD=${2:?}; shift 2 ;;
    --target) TARGET=${2:?}; shift 2 ;;
    -h|--help) usage ;;
    *) echo "k6.sh: unknown option $1" >&2; usage ;;
  esac
done
case "$STRATEGY" in PESSIMISTIC|OPTIMISTIC|REDIS_FIRST) ;; *) echo "k6.sh: unknown strategy $STRATEGY" >&2; exit 2 ;; esac
case "$TARGET" in compose|host) ;; *) echo "k6.sh: --target must be compose or host" >&2; exit 2 ;; esac
for n in "$VUS" "$HOT_SEATS" "$WARMUP" "$LEAD"; do
  case "$n" in ''|*[!0-9]*) echo "k6.sh: numbers must be whole numbers, got '$n'" >&2; exit 2 ;; esac
done

REPO=$(cd "$(dirname "$0")/.." && pwd)
cd "$REPO"
[ -f "scripts/k6/$SCENARIO.js" ] || { echo "k6.sh: no scenario scripts/k6/$SCENARIO.js" >&2; exit 2; }
# Docker Desktop on Windows needs a Windows path for bind mounts; elsewhere pwd is already right.
REPO_HOST=$(pwd -W 2>/dev/null || pwd)

RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$SCENARIO-$STRATEGY"
OUT="benchmarks/runs/$RUN_ID"
mkdir -p "$OUT"
log() { echo "k6.sh: $*" | tee -a "$OUT/k6sh.log"; }

# psql in the postgres container, as that container's own user (its env has POSTGRES_USER/_DB).
psql_in_db() {
  docker compose exec -T postgres sh -c 'exec psql -X -q -At -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"' psql "$@"
}

# --- preflight ---------------------------------------------------------------------------------------
if [ "$TARGET" = compose ]; then
  BACKEND=$(docker compose ps -q backend)
  [ -n "$BACKEND" ] || { echo "k6.sh: the backend container isn't running: docker compose up -d --build --wait" >&2; exit 1; }
  HEALTH=$(docker inspect -f '{{.State.Health.Status}}' "$BACKEND")
  [ "$HEALTH" = healthy ] || { echo "k6.sh: the backend container is $HEALTH, not healthy" >&2; exit 1; }
  NETWORK=$(docker inspect -f '{{range $name, $_ := .NetworkSettings.Networks}}{{$name}} {{end}}' "$BACKEND" | awk '{print $1}')
  NETWORK_ARGS=(--network "$NETWORK")
  BASE_URL=http://backend:8080
else
  curl -fsS -o /dev/null http://localhost:8080/actuator/health || { echo "k6.sh: nothing healthy on localhost:8080" >&2; exit 1; }
  NETWORK_ARGS=()
  BASE_URL=http://host.docker.internal:8080
fi
DEMO_TITLE=$(sed -n 's/^ *"title": *"\(.*\)",$/\1/p' backend/src/main/resources/seed/events/demo-drop.json)
[ -n "$DEMO_TITLE" ] || { echo "k6.sh: can't read the demo event's title from its seed file" >&2; exit 1; }

PASSWORD_FILE=benchmarks/runs/.k6-admin-password
if [ ! -s "$PASSWORD_FILE" ]; then
  head -c 24 /dev/urandom | base64 | tr -d '/+=\n' > "$PASSWORD_FILE"
fi
ADMIN_PASSWORD=$(cat "$PASSWORD_FILE")

# --- events and the admin ----------------------------------------------------------------------------
psql_in_db < scripts/k6/sql/end-bench-events.sql
EVENTS=$(psql_in_db -v run="$RUN_ID" -v strategy="$STRATEGY" -v lead="$LEAD" -v demo_title="$DEMO_TITLE" \
  < scripts/k6/sql/bench-events.sql)
WARMUP_EVENT_ID=$(echo "$EVENTS" | sed -n 's/^warmup=//p' | tr -d '\r')
BENCH_EVENT_ID=$(echo "$EVENTS" | sed -n 's/^bench=//p' | tr -d '\r')
[ -n "$WARMUP_EVENT_ID" ] && [ -n "$BENCH_EVENT_ID" ] || { echo "k6.sh: creating the events failed: $EVENTS" >&2; exit 1; }
log "run $RUN_ID: warm-up event $WARMUP_EVENT_ID ($STRATEGY), benchmark event $BENCH_EVENT_ID (drop in ${LEAD}s)"

k6_run() {   # k6_run <container name suffix> <k6 args...>
  local name=$1
  shift
  MSYS_NO_PATHCONV=1 docker run --rm --name "k6-$name" "${NETWORK_ARGS[@]}" \
    -v "$REPO_HOST/scripts/k6:/scripts:ro" -v "$REPO_HOST/$OUT:/out" \
    -e BASE_URL="$BASE_URL" -e RUN_ID="$RUN_ID" -e STRATEGY="$STRATEGY" \
    -e BENCH_EVENT_ID="$BENCH_EVENT_ID" -e WARMUP_EVENT_ID="$WARMUP_EVENT_ID" \
    -e ADMIN_EMAIL="$ADMIN_EMAIL" -e ADMIN_PASSWORD="$ADMIN_PASSWORD" \
    -e VUS="$VUS" -e HOT_SEATS="$HOT_SEATS" -e WARMUP_SECONDS="$WARMUP" \
    "$K6_IMAGE" "$@"
}

if ! k6_run "prepare-$$" run --include-system-env-vars --quiet --no-summary /scripts/prepare-admin.js > "$OUT/prepare.log" 2>&1; then
  cat "$OUT/prepare.log" >&2
  echo "k6.sh: registering the load-test admin failed" >&2
  exit 1
fi
[ "$(psql_in_db -c "UPDATE users SET role = 'ADMIN' WHERE email = '$ADMIN_EMAIL' RETURNING 1" | tr -d '\r')" = 1 ] \
  || { echo "k6.sh: the load-test admin wasn't registered" >&2; exit 1; }
log "load-test admin $ADMIN_EMAIL ready"

# --- samplers ----------------------------------------------------------------------------------------
# pg_stat_activity once a second (scripts/k6/sql/pg-activity.sql), looped inside the postgres container
# so the whole run costs one docker exec. It stops when k6.sh touches /tmp/k6-sampler-stop, or after an
# hour if k6.sh was killed.
echo "time,connections,active,waiting_on_lock,idle_in_transaction,idle" > "$OUT/pg_activity.csv"
docker compose exec -T postgres sh -c 'rm -f /tmp/k6-sampler-stop; cat > /tmp/k6-pg-activity.sql' \
  < scripts/k6/sql/pg-activity.sql
docker compose exec -T postgres sh -c '
  end=$(( $(date +%s) + 3600 ))
  while [ ! -f /tmp/k6-sampler-stop ] && [ "$(date +%s)" -lt "$end" ]; do
    psql -X -q -At -F, -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f /tmp/k6-pg-activity.sql
    sleep 1
  done' >> "$OUT/pg_activity.csv" 2>>"$OUT/k6sh.log" &
PG_SAMPLER=$!

# docker stats for every container (k6's own CPU included: a saturated k6 measures itself).
# One --no-stream sample takes about 2 s on Docker Desktop, so this is roughly every 2 s.
echo "time,container,cpu_percent,memory" > "$OUT/docker_stats.csv"
(
  while [ ! -f "$OUT/.stop" ]; do
    now=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' 2>/dev/null | sed "s/^/$now,/" >> "$OUT/docker_stats.csv"
  done
) &
STATS_SAMPLER=$!

stop_samplers() {
  touch "$OUT/.stop"
  docker compose exec -T postgres sh -c 'touch /tmp/k6-sampler-stop' >/dev/null 2>&1 || true
  wait "$PG_SAMPLER" "$STATS_SAMPLER" 2>/dev/null || true
  rm -f "$OUT/.stop"
}
trap stop_samplers EXIT

# --- the run -----------------------------------------------------------------------------------------
STARTED=$(date -u +%Y-%m-%dT%H:%M:%SZ)
log "k6 $SCENARIO: $STRATEGY, $VUS VUs, $HOT_SEATS hot seats, warm-up ${WARMUP}s, target $TARGET"
set +e
k6_run "$RUN_ID" run --include-system-env-vars --out json=/out/raw.json.gz "/scripts/$SCENARIO.js" 2>&1 | tee "$OUT/k6.log"
K6_EXIT=${PIPESTATUS[0]}
set -e
FINISHED=$(date -u +%Y-%m-%dT%H:%M:%SZ)
stop_samplers
trap - EXIT

# --- database check, end the events, run.json --------------------------------------------------------
DB_CHECK="not run"
DB_OK=0
if [ -f "$OUT/result.env" ]; then
  RESULT=$(tr -d '\r' < "$OUT/result.env")
  K6_SEATS_SOLD=$(echo "$RESULT" | sed -n 's/^SEATS_SOLD=//p')
  CHECK_EVENT=$(echo "$RESULT" | sed -n 's/^DB_CHECK_EVENT=//p')
  [ "$CHECK_EVENT" = warmup ] && CHECK_ID=$WARMUP_EVENT_ID || CHECK_ID=$BENCH_EVENT_ID
  DB_SOLD=$(psql_in_db -c "SELECT count(*) FROM event_seats WHERE event_id = '$CHECK_ID' AND status = 'SOLD'" | tr -d '\r')
  if [ "$DB_SOLD" = "$K6_SEATS_SOLD" ]; then
    DB_OK=1
    DB_CHECK="ok: the database has $DB_SOLD SOLD seats in the $CHECK_EVENT event, as k6 counted"
  else
    DB_CHECK="FAILED: the database has $DB_SOLD SOLD seats in the $CHECK_EVENT event, k6 counted $K6_SEATS_SOLD"
  fi
fi
psql_in_db < scripts/k6/sql/end-bench-events.sql

if [ "$K6_EXIT" -eq 0 ] && [ "$DB_OK" -eq 1 ]; then VERDICT=PASS; else VERDICT=FAIL; fi
DIRTY=$(git status --porcelain | wc -l | tr -d ' ')
cat > "$OUT/run.json" <<EOF
{
  "runId": "$RUN_ID",
  "scenario": "$SCENARIO",
  "strategy": "$STRATEGY",
  "vus": $VUS,
  "hotSeats": $HOT_SEATS,
  "warmupSeconds": $WARMUP,
  "leadSeconds": $LEAD,
  "target": "$TARGET",
  "warmupEventId": "$WARMUP_EVENT_ID",
  "benchEventId": "$BENCH_EVENT_ID",
  "started": "$STARTED",
  "finished": "$FINISHED",
  "commit": "$(git rev-parse HEAD)",
  "uncommittedFiles": $DIRTY,
  "k6Image": "$K6_IMAGE",
  "backendImage": "$( [ "$TARGET" = compose ] && docker inspect -f '{{.Image}}' "$BACKEND" || echo bootRun)",
  "dockerCpus": $(docker info --format '{{.NCPU}}'),
  "dockerMemoryBytes": $(docker info --format '{{.MemTotal}}'),
  "k6Exit": $K6_EXIT,
  "databaseCheck": "$DB_CHECK",
  "verdict": "$VERDICT",
  "notes": "fill in by hand: on mains power? power plan? other apps running?"
}
EOF
log "database check: $DB_CHECK"
log "k6 exit $K6_EXIT; verdict $VERDICT; results in $OUT/"
[ "$VERDICT" = PASS ]
