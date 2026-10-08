#!/usr/bin/env bash

# Self-contained Linux CI runner for the complete Gradle `test` task. Some
# historical tests use a native PostgreSQL/Redis/Kafka stack, while
# newer smoke tests use Testcontainers. Provision both paths and retain the
# dedicated Compose project after returning for owner evidence inspection.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ARTIFACTS="${AURA_REGRESSION_ARTIFACTS:-$PROJECT_ROOT/.workspace/oss-backend-unit-ci}"
RUNTIME_TOKEN="$(printf '%s' "${AURA_REGRESSION_SLOT:-local}-$$" | tr -cd '[:alnum:]-')"
COMPOSE_PROJECT="aura-ci-oss-backend-$RUNTIME_TOKEN"
export AURA_CI_JOB_ID="${AURA_CI_JOB_ID:-oss-backend-$RUNTIME_TOKEN}"
export AURA_OSS_CI_NETWORK="${COMPOSE_PROJECT}_default"
NETWORK_CREATED=false

free_port() {
  local candidate="$1" limit="$2"
  while (( candidate <= limit )); do
    if ! ss -H -ltn "sport = :$candidate" | grep -q .; then
      printf '%s\n' "$candidate"
      return 0
    fi
    candidate=$((candidate + 1))
  done
  return 1
}

command -v ss >/dev/null 2>&1 || { printf '[oss-backend-unit-ci] environment-invalid: ss is unavailable\n' >&2; exit 2; }
if [[ -z "${AURA_OSS_CI_POSTGRES_PORT:-}" ]]; then
  AURA_OSS_CI_POSTGRES_PORT="$(free_port 25000 25999)" \
    || { printf '[oss-backend-unit-ci] environment-invalid: no free PostgreSQL CI port\n' >&2; exit 2; }
fi
if [[ -z "${AURA_OSS_CI_REDIS_PORT:-}" ]]; then
  AURA_OSS_CI_REDIS_PORT="$(free_port 26000 26999)" \
    || { printf '[oss-backend-unit-ci] environment-invalid: no free Redis CI port\n' >&2; exit 2; }
fi
if [[ -z "${AURA_OSS_CI_KAFKA_PORT:-}" ]]; then
  AURA_OSS_CI_KAFKA_PORT="$(free_port 27000 27999)" \
    || { printf '[oss-backend-unit-ci] environment-invalid: no free Kafka CI port\n' >&2; exit 2; }
fi
export AURA_OSS_CI_POSTGRES_PORT AURA_OSS_CI_REDIS_PORT AURA_OSS_CI_KAFKA_PORT
COMPOSE_ARGS=(
  -f "$PROJECT_ROOT/docker-compose.oss-backend-ci.override.yml"
  -p "$COMPOSE_PROJECT"
)
FLYWAY_IMAGE='flyway/flyway:12.8.1@sha256:b8a2d72926b98234c1fb8f45659fd23d8a001af9ee7f450326aa46af14d447bb'

mkdir -p "$ARTIFACTS"

environment_invalid() {
  printf '[oss-backend-unit-ci] environment-invalid: %s\n' "$*" >&2
  exit 2
}

create_isolated_network() {
  local subnet_index subnet
  # Avoid retained Docker networks and host routes; Docker arbitrates races.
  for subnet_index in $(seq 0 255); do
    subnet="$(node "$SCRIPT_DIR/lib/oss-ci-subnet.mjs")" \
      || environment_invalid 'no free isolated CI network without overlapping retained networks or host routes'
    [[ -n "$subnet" ]] || environment_invalid 'CI subnet allocator returned no subnet'
    if docker network create --subnet "$subnet" \
        --label "aura.ci.compose-project=$COMPOSE_PROJECT" "$AURA_OSS_CI_NETWORK" \
        > "$ARTIFACTS/network-create.log" 2>&1; then
      NETWORK_CREATED=true
      printf '%s\n' "$subnet" > "$ARTIFACTS/compose-subnet.txt"
      printf '[oss-backend-unit-ci] isolated network created: name=%s subnet=%s\n' \
        "$AURA_OSS_CI_NETWORK" "$subnet"
      return 0
    fi
    if ! grep -q 'Pool overlaps' "$ARTIFACTS/network-create.log"; then
      environment_invalid 'isolated network creation failed; inspect network-create.log'
    fi
  done
  environment_invalid 'no free isolated CI network after bounded allocation attempts'
}

cleanup() {
  local status=$? network_status=not-created stop_status=stopped
  trap - EXIT HUP INT TERM
  if ! python3 "$SCRIPT_DIR/cleanup-ci-gradle.py" --job-id "$AURA_CI_JOB_ID" \
      --source-root "$PROJECT_ROOT" --report "$ARTIFACTS/gradle-process-cleanup.json"; then
    printf '[oss-backend-unit-ci] environment-invalid: owned Gradle cleanup incomplete\n' >&2
    [[ "$status" -ne 0 ]] || status=2
  fi
  docker compose "${COMPOSE_ARGS[@]}" ps --all > "$ARTIFACTS/compose-ps.txt" 2>&1 || true
  docker compose "${COMPOSE_ARGS[@]}" logs --no-color > "$ARTIFACTS/compose.log" 2>&1 || true
  docker compose "${COMPOSE_ARGS[@]}" stop > "$ARTIFACTS/compose-stop.log" 2>&1 || stop_status=stop-failed
  # Retain containers and volumes for evidence, but release the finite Docker
  # address-pool allocation. Stopped containers can be reattached by Compose if
  # an owner later restarts this exact retained project.
  if [[ "$NETWORK_CREATED" == true ]]; then
    while IFS= read -r container_id; do
      [[ -n "$container_id" ]] || continue
      docker network disconnect -f "${COMPOSE_PROJECT}_default" "$container_id" >/dev/null 2>&1 || true
    done < <(docker compose "${COMPOSE_ARGS[@]}" ps -aq 2>/dev/null || true)
    if docker network rm "${COMPOSE_PROJECT}_default" > "$ARTIFACTS/network-release.log" 2>&1; then
      network_status=released
    else
      network_status=release-failed
    fi
  fi
  printf '[oss-backend-unit-ci] runtime retained: stop_status=%s network_status=%s compose_project=%s artifacts=%s\n' \
    "$stop_status" "$network_status" "$COMPOSE_PROJECT" "$ARTIFACTS"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

command -v python3 >/dev/null 2>&1 || environment_invalid 'Python 3 is unavailable'
command -v docker >/dev/null 2>&1 || environment_invalid 'docker is unavailable'
command -v timeout >/dev/null 2>&1 || environment_invalid 'timeout is unavailable'
docker compose version >/dev/null 2>&1 || environment_invalid 'docker compose v2 is unavailable'
docker info >/dev/null 2>&1 || environment_invalid 'Docker daemon is unavailable to the CI account'
AURA_OSS_CI_SUBNET="$(node "$SCRIPT_DIR/lib/oss-ci-subnet.mjs")" \
  || environment_invalid 'cannot allocate a private subnet without overlapping retained networks or host routes'
[[ -n "$AURA_OSS_CI_SUBNET" ]] || environment_invalid 'CI subnet allocator returned no subnet'
export AURA_OSS_CI_SUBNET
printf '%s\n' "$AURA_OSS_CI_SUBNET" > "$ARTIFACTS/compose-subnet.txt"
docker compose "${COMPOSE_ARGS[@]}" config --quiet \
  || environment_invalid 'submitted PostgreSQL/Redis/Kafka Compose inputs are invalid'

# Pre-pull every image referenced by this test denominator. A pull failure is a
# machine/network precondition failure, not a product regression.
for image in \
  pgvector/pgvector:pg16 \
  redis:7-alpine \
  postgres:16 \
  mysql:8.0.39 \
  mysql:8.0 \
  confluentinc/cp-kafka:7.5.0 \
  tdengine/tdengine:3.3.4.3 \
  testcontainers/ryuk:0.12.0 \
  "$FLYWAY_IMAGE"; do
  if ! docker image inspect "$image" >/dev/null 2>&1; then
    timeout 10m docker pull "$image" || environment_invalid "cannot pull $image within 10 minutes"
  fi
done

PLAYWRIGHT_CLI="$PROJECT_ROOT/web-admin/node_modules/.bin/playwright"
[[ -x "$PLAYWRIGHT_CLI" ]] \
  || environment_invalid 'web-admin lockfile dependencies do not provide Playwright'

# Playwright 1.60 predates Ubuntu 26.04 and otherwise refuses to resolve a browser archive for
# that host label. Ubuntu 26.04 is forward-compatible with the pinned Ubuntu 24.04 browser build;
# keep using Playwright's lockfile revision instead of drifting to an ambient system browser.
if [[ -r /etc/os-release ]]; then
  # shellcheck disable=SC1091
  source /etc/os-release
  if [[ "${ID:-}" == "ubuntu" && "${VERSION_ID:-}" == "26.04" ]]; then
    case "$(uname -m)" in
      x86_64) export PLAYWRIGHT_HOST_PLATFORM_OVERRIDE="ubuntu24.04-x64" ;;
      aarch64|arm64) export PLAYWRIGHT_HOST_PLATFORM_OVERRIDE="ubuntu24.04-arm64" ;;
      *) environment_invalid "unsupported Ubuntu 26.04 architecture for Playwright: $(uname -m)" ;;
    esac
  fi
fi

if ! PLAYWRIGHT_DOWNLOAD_CONNECTION_TIMEOUT=120000 \
    timeout 10m "$PLAYWRIGHT_CLI" install chromium \
    > "$ARTIFACTS/playwright-install.log" 2>&1; then
  environment_invalid 'cannot install lockfile-pinned Playwright Chromium within 10 minutes'
fi

create_isolated_network

if ! docker compose "${COMPOSE_ARGS[@]}" up -d --wait postgres redis kafka; then
  environment_invalid 'CI PostgreSQL/Redis/Kafka stack did not become healthy'
fi

# The PostgreSQL image reports healthy while its temporary init server may still
# be finishing. Wait for the entrypoint's final init marker and then prove the
# final server accepts connections before Flyway owns the blank-database setup.
postgres_init_deadline=$((SECONDS + 300))
postgres_initialized=false
while (( SECONDS < postgres_init_deadline )); do
  if docker compose "${COMPOSE_ARGS[@]}" logs --no-color postgres 2>&1 \
      | grep -q 'PostgreSQL init process complete; ready for start up.' \
    && docker compose "${COMPOSE_ARGS[@]}" exec -T postgres \
      pg_isready -U auraboot -d aura_boot >/dev/null 2>&1; then
    postgres_initialized=true
    break
  fi
  sleep 2
done
if [[ "$postgres_initialized" != true ]]; then
  environment_invalid 'CI PostgreSQL did not finish schema initialization within 5 minutes'
fi

FLYWAY_ARGS=(
  -user=auraboot
  -password=auraboot_dev
  -locations=filesystem:/flyway/sql
  -table=ab_flyway_schema_history
  -baselineOnMigrate=false
  -validateMigrationNaming=true
  -cleanDisabled=true
)
run_flyway() {
  local target_database="${2:-aura_boot}"
  docker run --rm --network host \
    -v "$PROJECT_ROOT/platform/src/main/resources/db/migration/core:/flyway/sql:ro" \
    "$FLYWAY_IMAGE" "-url=jdbc:postgresql://127.0.0.1:${AURA_OSS_CI_POSTGRES_PORT}/$target_database" "${FLYWAY_ARGS[@]}" "$1"
}

if ! run_flyway migrate > "$ARTIFACTS/flyway-migrate.log" 2>&1; then
  printf '[oss-backend-unit-ci] product-failure: Flyway migrate failed\n' >&2
  exit 1
fi
if ! run_flyway validate > "$ARTIFACTS/flyway-validate.log" 2>&1; then
  printf '[oss-backend-unit-ci] product-failure: Flyway validate failed\n' >&2
  exit 1
fi

# Bootstrap never resets shared-suite fixtures or bypasses immutable binding guards.
BOOTSTRAP_DATABASE="aura_boot_bootstrap_${RUNTIME_TOKEN//-/_}"
if ! docker compose "${COMPOSE_ARGS[@]}" exec -T postgres \
  createdb -U auraboot "$BOOTSTRAP_DATABASE" > "$ARTIFACTS/bootstrap-database-create.log" 2>&1; then
  environment_invalid 'cannot create isolated bootstrap database'
fi
if ! run_flyway migrate "$BOOTSTRAP_DATABASE" > "$ARTIFACTS/bootstrap-flyway-migrate.log" 2>&1 \
  || ! run_flyway validate "$BOOTSTRAP_DATABASE" > "$ARTIFACTS/bootstrap-flyway-validate.log" 2>&1; then
  printf '[oss-backend-unit-ci] product-failure: bootstrap database migration failed\n' >&2
  exit 1
fi

# Tests exercise migration-owned defaults; prove the denominator before Gradle
# so a missing seed is reported as database bootstrap drift rather than dozens
# of misleading service-level assertion failures.
seed_counts="$(docker compose "${COMPOSE_ARGS[@]}" exec -T postgres \
  psql -U auraboot -d aura_boot -Atc "
    SELECT count(*) FROM ab_object_alias WHERE tenant_id = -1
    UNION ALL SELECT count(*) FROM ab_agent_capability WHERE tenant_id = -1
    UNION ALL SELECT count(*) FROM ab_login_application WHERE status = 'active'
    UNION ALL SELECT count(*) FROM ab_login_channel WHERE status = 'active'
    UNION ALL SELECT count(*) FROM ab_login_channel_auth_method WHERE status = 'active'
    UNION ALL SELECT count(*) FROM ab_billing_resource_catalog WHERE status = 'ACTIVE';
  " 2> "$ARTIFACTS/platform-seed-verification.log")" || {
    printf '[oss-backend-unit-ci] product-failure: platform seed verification query failed\n' >&2
    exit 1
  }
printf '%s\n' "$seed_counts" > "$ARTIFACTS/platform-seed-verification.log"
if [[ "$(printf '%s\n' "$seed_counts" | awk '$1 > 0 { ok++ } END { print ok + 0 }')" -ne 6 ]]; then
  printf '[oss-backend-unit-ci] product-failure: platform seed verification failed\n' >&2
  exit 1
fi

cd "$PROJECT_ROOT" || environment_invalid 'cannot enter repository root'

# The backend unit/IT gate must stay deterministic and credential-independent. DashScope checks
# are live external-provider acceptance tests; they remain available through the dedicated golden
# suites or an explicit one-off opt-in, but an ambient host key must not silently widen this suite.
if [[ "${AURA_CI_INCLUDE_DASHSCOPE_LIVE:-0}" != "1" ]]; then
  unset DASHSCOPE_API_KEY
  printf '%s\n' \
    '[oss-backend-unit-ci] DashScope live checks disabled; set AURA_CI_INCLUDE_DASHSCOPE_LIVE=1 to opt in'
fi

run_backend_gradle() {
  local database="$1"
  shift
TEST_DATABASE_URL="jdbc:postgresql://127.0.0.1:${AURA_OSS_CI_POSTGRES_PORT}/${database}?charSet=UTF8" \
TEST_EXPECTED_DATABASE="$database" \
POSTGRES_DB="$database" \
AURA_TEST_POSTGRES_JDBC_URL="jdbc:postgresql://127.0.0.1:${AURA_OSS_CI_POSTGRES_PORT}/${database}?charSet=UTF8" \
POSTGRES_PASSWORD='auraboot_dev' \
BOOTSTRAP_TEST_DATABASE_URL="jdbc:postgresql://127.0.0.1:${AURA_OSS_CI_POSTGRES_PORT}/${BOOTSTRAP_DATABASE}?charSet=UTF8" \
TEST_DATABASE_USERNAME='auraboot' \
TEST_DATABASE_PASSWORD='auraboot_dev' \
DATABASE_URL="jdbc:postgresql://127.0.0.1:${AURA_OSS_CI_POSTGRES_PORT}/${database}?charSet=UTF8" \
DATABASE_USERNAME='auraboot' \
DATABASE_PASSWORD='auraboot_dev' \
SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:${AURA_OSS_CI_POSTGRES_PORT}/${database}?charSet=UTF8" \
SPRING_DATASOURCE_USERNAME='auraboot' \
SPRING_DATASOURCE_PASSWORD='auraboot_dev' \
SPRING_DATA_REDIS_HOST='127.0.0.1' \
SPRING_DATA_REDIS_PORT="$AURA_OSS_CI_REDIS_PORT" \
SPRING_DATA_REDIS_URL="redis://127.0.0.1:$AURA_OSS_CI_REDIS_PORT" \
SPRING_KAFKA_BOOTSTRAP_SERVERS="127.0.0.1:$AURA_OSS_CI_KAFKA_PORT" \
AURA_CI_REQUIRE_KAFKA='1' \
AURA_CI_KAFKA_BOOTSTRAP_SERVERS="127.0.0.1:$AURA_OSS_CI_KAFKA_PORT" \
MAVEN_REPO_LOCAL="$ARTIFACTS/m2" \
GRADLE_OPTS="-Dmaven.repo.local=$ARTIFACTS/m2 ${GRADLE_OPTS:-}" \
platform/gradlew --no-daemon -p platform "$@"
}

run_backend_gradle aura_boot --continue cleanTest test
root_test_status=$?
printf '%s\n' "$root_test_status" > "$ARTIFACTS/root-test-exit-code.txt"
printf '%s\n' "$BOOTSTRAP_DATABASE" > "$ARTIFACTS/bootstrap-database.txt"
AURA_BOOTSTRAP_ISOLATED_DATABASE=1 \
run_backend_gradle aura_boot --continue bootstrapBillingAccountTest
bootstrap_test_status=$?
printf '%s\n' "$bootstrap_test_status" > "$ARTIFACTS/bootstrap-test-exit-code.txt"
gradle_status=$root_test_status
if (( gradle_status == 0 )); then gradle_status=$bootstrap_test_status; fi

# Both task exit codes decide the gate; report copying cannot mask a failure.
if [[ -n "${AURA_ALLURE_RESULTS:-}" && -d "$PROJECT_ROOT/platform/build/allure-results" ]]; then
  mkdir -p "$AURA_ALLURE_RESULTS"
  cp -a "$PROJECT_ROOT/platform/build/allure-results/." "$AURA_ALLURE_RESULTS/" || \
    printf '[oss-backend-unit-ci] warning: unable to copy Allure results\n' >&2
fi
# Preserve the original test status while retaining both tasks' JUnit evidence.
mkdir -p "$ARTIFACTS/junit"
for task in test bootstrapBillingAccountTest; do
  if [[ -d "$PROJECT_ROOT/platform/build/test-results/$task" ]]; then
    cp -a "$PROJECT_ROOT/platform/build/test-results/$task" "$ARTIFACTS/junit/" || \
      printf '[oss-backend-unit-ci] warning: unable to copy %s JUnit results\n' "$task" >&2
  fi
done
exit "$gradle_status"
