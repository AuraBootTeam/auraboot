#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PLATFORM_DIR="$ROOT_DIR/platform"
XXL_COMPOSE_DIR="$ROOT_DIR/docker/xxl-job-smoke"
RUN_DIR="$ROOT_DIR/.aura/xxl-job-smoke"

mkdir -p "$RUN_DIR"

XXL_PROJECT="${XXL_PROJECT:-auraboot-xxl-smoke}"
AURA_PROJECT="${AURA_PROJECT:-auraboot-xxl-aura}"
XXL_JOB_ADMIN_PORT="${XXL_JOB_ADMIN_PORT:-18080}"
XXL_JOB_MYSQL_PORT="${XXL_JOB_MYSQL_PORT:-33306}"
XXL_JOB_MYSQL_ROOT_PASSWORD="${XXL_JOB_MYSQL_ROOT_PASSWORD:-xxl_job_root}"
XXL_JOB_EXECUTOR_PORT="${XXL_JOB_EXECUTOR_PORT:-19999}"
XXL_JOB_ACCESS_TOKEN="${XXL_JOB_ACCESS_TOKEN:-default_token}"
AURA_PG_PORT="${AURA_PG_PORT:-55432}"
AURA_REDIS_PORT="${AURA_REDIS_PORT:-56379}"
AURA_BE_PORT="${AURA_BE_PORT:-16543}"
AURA_BOOTSTRAP_EMAIL="${AURA_BOOTSTRAP_EMAIL:-admin@auraboot.com}"
AURA_BOOTSTRAP_PASSWORD="${AURA_BOOTSTRAP_PASSWORD:-Test2026x}"
CLEANUP="${CLEANUP:-1}"

BACKEND_URL="http://127.0.0.1:$AURA_BE_PORT"
XXL_ADMIN_URL="http://127.0.0.1:$XXL_JOB_ADMIN_PORT/xxl-job-admin"
BACKEND_LOG="$RUN_DIR/backend.log"

BACKEND_PID=""

log() {
  printf '[xxl-smoke] %s\n' "$*"
}

cleanup() {
  local exit_code=$?
  if [ -n "$BACKEND_PID" ] && kill -0 "$BACKEND_PID" 2>/dev/null; then
    kill "$BACKEND_PID" 2>/dev/null || true
    wait "$BACKEND_PID" 2>/dev/null || true
  fi

  if [ "$CLEANUP" = "1" ] && [ "$exit_code" = "0" ]; then
    log "cleaning smoke stacks"
    (
      cd "$XXL_COMPOSE_DIR"
      COMPOSE_PROJECT_NAME="$XXL_PROJECT" \
      XXL_JOB_ADMIN_PORT="$XXL_JOB_ADMIN_PORT" \
      XXL_JOB_MYSQL_PORT="$XXL_JOB_MYSQL_PORT" \
      XXL_JOB_MYSQL_ROOT_PASSWORD="$XXL_JOB_MYSQL_ROOT_PASSWORD" \
      XXL_JOB_ACCESS_TOKEN="$XXL_JOB_ACCESS_TOKEN" \
        docker compose down -v --remove-orphans >/dev/null 2>&1 || true
    )
    (
      cd "$ROOT_DIR"
      COMPOSE_PROJECT_NAME="$AURA_PROJECT" \
      PG_PORT="$AURA_PG_PORT" \
      REDIS_PORT="$AURA_REDIS_PORT" \
        docker compose -p "$AURA_PROJECT" -f docker-compose.yml -f docker-compose.isolated.yml --profile cache down -v --remove-orphans >/dev/null 2>&1 || true
    )
  else
    log "leaving stacks/logs for inspection: CLEANUP=$CLEANUP exit=$exit_code runDir=$RUN_DIR"
  fi
}
trap cleanup EXIT

json_get() {
  python3 -c '
import json
import sys

path = sys.argv[1].split(".")
data = json.load(sys.stdin)
for part in path:
    if isinstance(data, dict):
        data = data.get(part)
    elif isinstance(data, list) and part.isdigit():
        data = data[int(part)]
    else:
        data = None
        break
print("" if data is None else data)
' "$1"
}

json_has_success_log() {
  python3 -c '
import json
import sys

try:
    body = json.load(sys.stdin)
except Exception:
    sys.exit(1)
logs = body.get("data") or []
for row in logs:
    if row.get("status") == "success":
        sys.exit(0)
sys.exit(1)
'
}

wait_http() {
  local name="$1"
  local url="$2"
  local timeout="${3:-120}"
  local deadline=$((SECONDS + timeout))
  while [ "$SECONDS" -lt "$deadline" ]; do
    local code
    code="$(NO_PROXY=localhost,127.0.0.1 curl -sS -o /dev/null -w '%{http_code}' "$url" 2>/dev/null || true)"
    if [ "$code" != "000" ] && [ "$code" -lt 500 ]; then
      log "$name is reachable ($code)"
      return 0
    fi
    if [ -n "$BACKEND_PID" ] && ! kill -0 "$BACKEND_PID" 2>/dev/null; then
      log "backend exited while waiting for $name; tailing $BACKEND_LOG"
      tail -n 80 "$BACKEND_LOG" || true
      return 1
    fi
    sleep 3
  done
  log "timeout waiting for $name at $url"
  return 1
}

mysql_scalar() {
  (
    cd "$XXL_COMPOSE_DIR"
    COMPOSE_PROJECT_NAME="$XXL_PROJECT" \
    XXL_JOB_ADMIN_PORT="$XXL_JOB_ADMIN_PORT" \
    XXL_JOB_MYSQL_PORT="$XXL_JOB_MYSQL_PORT" \
    XXL_JOB_MYSQL_ROOT_PASSWORD="$XXL_JOB_MYSQL_ROOT_PASSWORD" \
    XXL_JOB_ACCESS_TOKEN="$XXL_JOB_ACCESS_TOKEN" \
      docker compose exec -T xxl-job-mysql mysql -uroot -p"$XXL_JOB_MYSQL_ROOT_PASSWORD" -N -s xxl_job -e "$1"
  )
}

wait_xxl_registry() {
  local deadline=$((SECONDS + 120))
  while [ "$SECONDS" -lt "$deadline" ]; do
    local count
    count="$(mysql_scalar "SELECT COUNT(*) FROM xxl_job_registry WHERE registry_key='auraboot-platform';" 2>/dev/null || echo 0)"
    if [ "${count:-0}" -gt 0 ]; then
      log "XXL executor registry is ready"
      return 0
    fi
    sleep 5
  done
  log "timeout waiting for XXL executor registry"
  mysql_scalar "SELECT registry_group, registry_key, registry_value, update_time FROM xxl_job_registry;" || true
  return 1
}

wait_task_success() {
  local task_pid="$1"
  local label="$2"
  local deadline=$((SECONDS + 150))
  while [ "$SECONDS" -lt "$deadline" ]; do
    local body
    body="$(NO_PROXY=localhost,127.0.0.1 curl -sS "$BACKEND_URL/api/scheduled-tasks/$task_pid/logs?limit=10" \
      -H "Authorization: Bearer $JWT")"
    if printf '%s' "$body" | json_has_success_log; then
      log "$label task succeeded: pid=$task_pid"
      return 0
    fi
    sleep 5
  done
  log "timeout waiting for $label task success: pid=$task_pid"
  NO_PROXY=localhost,127.0.0.1 curl -sS "$BACKEND_URL/api/scheduled-tasks/$task_pid/logs?limit=10" \
    -H "Authorization: Bearer $JWT" || true
  return 1
}

task_success_count() {
  local task_pid="$1"
  local body
  body="$(NO_PROXY=localhost,127.0.0.1 curl -sS "$BACKEND_URL/api/scheduled-tasks/$task_pid/logs?limit=50" \
    -H "Authorization: Bearer $JWT")"
  printf '%s' "$body" | python3 -c '
import json
import sys

try:
    body = json.load(sys.stdin)
except Exception:
    print(0)
    sys.exit(0)
logs = body.get("data") or []
print(sum(1 for row in logs if row.get("status") == "success"))
'
}

wait_task_success_count() {
  local task_pid="$1"
  local label="$2"
  local min_count="$3"
  local deadline=$((SECONDS + 150))
  while [ "$SECONDS" -lt "$deadline" ]; do
    local count
    count="$(task_success_count "$task_pid")"
    if [ "${count:-0}" -ge "$min_count" ]; then
      log "$label task success count reached $count: pid=$task_pid"
      return 0
    fi
    sleep 5
  done
  log "timeout waiting for $label task success count >= $min_count: pid=$task_pid"
  NO_PROXY=localhost,127.0.0.1 curl -sS "$BACKEND_URL/api/scheduled-tasks/$task_pid/logs?limit=50" \
    -H "Authorization: Bearer $JWT" || true
  return 1
}

create_task() {
  local payload="$1"
  local response
  response="$(NO_PROXY=localhost,127.0.0.1 curl -sS -X POST "$BACKEND_URL/api/scheduled-tasks" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $JWT" \
    -d "$payload")"
  local code
  code="$(printf '%s' "$response" | json_get code)"
  if [ "$code" != "0" ]; then
    log "create task failed: $response"
    return 1
  fi
  printf '%s' "$response" | json_get data.pid
}

log "starting XXL-JOB MySQL/Admin stack"
(
  cd "$XXL_COMPOSE_DIR"
  COMPOSE_PROJECT_NAME="$XXL_PROJECT" \
  XXL_JOB_ADMIN_PORT="$XXL_JOB_ADMIN_PORT" \
  XXL_JOB_MYSQL_PORT="$XXL_JOB_MYSQL_PORT" \
  XXL_JOB_MYSQL_ROOT_PASSWORD="$XXL_JOB_MYSQL_ROOT_PASSWORD" \
  XXL_JOB_ACCESS_TOKEN="$XXL_JOB_ACCESS_TOKEN" \
    docker compose up -d
)

wait_http "XXL-JOB Admin" "$XXL_ADMIN_URL/toLogin" 180

log "starting AuraBoot Postgres/Redis infra"
(
  cd "$ROOT_DIR"
  COMPOSE_PROJECT_NAME="$AURA_PROJECT" \
  PG_PORT="$AURA_PG_PORT" \
  REDIS_PORT="$AURA_REDIS_PORT" \
    docker compose -p "$AURA_PROJECT" -f docker-compose.yml -f docker-compose.isolated.yml --profile cache up -d postgres redis
)

log "starting AuraBoot backend on $BACKEND_URL"
(
  cd "$PLATFORM_DIR"
  SPRING_PROFILES_ACTIVE=dev \
  DATABASE_URL="jdbc:postgresql://127.0.0.1:$AURA_PG_PORT/aura_boot?charSet=UTF8" \
  DATABASE_USERNAME=auraboot \
  DATABASE_PASSWORD=auraboot_dev \
  SPRING_DATA_REDIS_HOST=127.0.0.1 \
  SPRING_DATA_REDIS_PORT="$AURA_REDIS_PORT" \
  AURA_SCHEDULER_ENGINE=xxl \
  XXL_JOB_ADMIN_ADDRESSES="$XXL_ADMIN_URL" \
  XXL_JOB_ADMIN_USERNAME=admin \
  XXL_JOB_ADMIN_PASSWORD=123456 \
  XXL_JOB_ACCESS_TOKEN="$XXL_JOB_ACCESS_TOKEN" \
  XXL_JOB_EXECUTOR_APP_NAME=auraboot-platform \
  XXL_JOB_EXECUTOR_ADDRESS="http://host.docker.internal:$XXL_JOB_EXECUTOR_PORT" \
  XXL_JOB_EXECUTOR_PORT="$XXL_JOB_EXECUTOR_PORT" \
  MANAGEMENT_HEALTH_DISKSPACE_ENABLED=false \
  ./gradlew :bootRun --no-daemon --args="--server.port=$AURA_BE_PORT --auraboot.bootstrap.enabled=false"
) >"$BACKEND_LOG" 2>&1 &
BACKEND_PID=$!

wait_http "AuraBoot backend" "$BACKEND_URL/actuator/health" 240

log "running bootstrap setup"
BOOTSTRAP_RESPONSE="$(NO_PROXY=localhost,127.0.0.1 curl -sS -X POST "$BACKEND_URL/api/bootstrap/setup" \
  -H "Content-Type: application/json" \
  -d "{\"companyName\":\"AuraBoot XXL Smoke\",\"adminEmail\":\"$AURA_BOOTSTRAP_EMAIL\",\"adminPassword\":\"$AURA_BOOTSTRAP_PASSWORD\",\"adminDisplayName\":\"Admin User\",\"systemMode\":\"single\"}")"
BOOTSTRAP_CODE="$(printf '%s' "$BOOTSTRAP_RESPONSE" | json_get code)"
if [ "$BOOTSTRAP_CODE" != "0" ]; then
  log "bootstrap failed: $BOOTSTRAP_RESPONSE"
  exit 1
fi

log "logging in"
LOGIN_RESPONSE="$(NO_PROXY=localhost,127.0.0.1 curl -sS -X POST "$BACKEND_URL/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"$AURA_BOOTSTRAP_EMAIL\",\"password\":\"$AURA_BOOTSTRAP_PASSWORD\"}")"
JWT="$(printf '%s' "$LOGIN_RESPONSE" | json_get data.jwt)"
if [ -z "$JWT" ]; then
  log "login failed: $LOGIN_RESPONSE"
  exit 1
fi

wait_xxl_registry

log "creating cron task"
CRON_PID="$(create_task '{
  "name": "XXL smoke cron",
  "taskType": "cron",
  "cronExpression": "*/5 * * * * *",
  "timezone": "UTC",
  "handlerBean": "scheduledTaskSmokeHandler",
  "handlerMethod": "execute",
  "params": "{\"source\":\"xxl-smoke-cron\"}",
  "maxRetries": 0,
  "timeoutMs": 30000,
  "enabled": true
}')"

ONE_TIME_AT="$(python3 - <<'PY'
from datetime import datetime, timezone, timedelta
print((datetime.now(timezone.utc) + timedelta(seconds=35)).isoformat().replace("+00:00", "Z"))
PY
)"
log "creating one-time task for $ONE_TIME_AT"
ONE_TIME_PAYLOAD="$(python3 - "$ONE_TIME_AT" <<'PY'
import json
import sys

print(json.dumps({
    "name": "XXL smoke one-time",
    "taskType": "one_time",
    "timezone": "UTC",
    "nextRunAt": sys.argv[1],
    "handlerBean": "scheduledTaskSmokeHandler",
    "handlerMethod": "execute",
    "params": json.dumps({"source": "xxl-smoke-one-time"}),
    "maxRetries": 0,
    "timeoutMs": 30000,
    "enabled": True,
}, separators=(",", ":")))
PY
)"
ONE_TIME_PID="$(create_task "$ONE_TIME_PAYLOAD")"

log "verifying external XXL jobs"
mysql_scalar "SELECT id, job_desc, schedule_type, schedule_conf, trigger_status FROM xxl_job_info WHERE job_desc LIKE 'AuraBoot:%';"

wait_task_success "$CRON_PID" "cron"
wait_task_success "$ONE_TIME_PID" "one-time"

log "manual trigger against cron task"
CRON_SUCCESS_BEFORE="$(task_success_count "$CRON_PID")"
TRIGGER_RESPONSE="$(NO_PROXY=localhost,127.0.0.1 curl -sS -X POST "$BACKEND_URL/api/scheduled-tasks/$CRON_PID/trigger" \
  -H "Authorization: Bearer $JWT")"
TRIGGER_CODE="$(printf '%s' "$TRIGGER_RESPONSE" | json_get code)"
if [ "$TRIGGER_CODE" != "0" ]; then
  log "manual trigger failed: $TRIGGER_RESPONSE"
  exit 1
fi
wait_task_success_count "$CRON_PID" "manual-trigger" "$((CRON_SUCCESS_BEFORE + 1))"

log "XXL-JOB true-stack smoke passed"
log "cronPid=$CRON_PID oneTimePid=$ONE_TIME_PID backendLog=$BACKEND_LOG"
