#!/usr/bin/env bash

# Self-contained Linux CI runner for the complete Gradle `test` task. Some
# historical tests still use the fixed skills-c2 PostgreSQL/Redis ports, while
# newer smoke tests use Testcontainers. Provision both paths and always clean
# the dedicated Compose project before returning.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE_PROJECT="aura-ci-oss-backend"
ARTIFACTS="${AURA_REGRESSION_ARTIFACTS:-$PROJECT_ROOT/.workspace/oss-backend-unit-ci}"
COMPOSE_ARGS=(
  -f "$PROJECT_ROOT/docker-compose.yml"
  -f "$PROJECT_ROOT/docker-compose.skills-c2.override.yml"
  -f "$PROJECT_ROOT/docker-compose.oss-backend-ci.override.yml"
  -p "$COMPOSE_PROJECT"
  --profile skills-c2-stack
)
FLYWAY_IMAGE='flyway/flyway:12.8.1@sha256:b8a2d72926b98234c1fb8f45659fd23d8a001af9ee7f450326aa46af14d447bb'

mkdir -p "$ARTIFACTS"

environment_invalid() {
  printf '[oss-backend-unit-ci] environment-invalid: %s\n' "$*" >&2
  exit 2
}

cleanup() {
  status=$?
  docker compose "${COMPOSE_ARGS[@]}" ps --all > "$ARTIFACTS/compose-ps.txt" 2>&1 || true
  docker compose "${COMPOSE_ARGS[@]}" logs --no-color > "$ARTIFACTS/compose.log" 2>&1 || true
  docker compose "${COMPOSE_ARGS[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
  exit "$status"
}
trap cleanup EXIT HUP INT TERM

command -v docker >/dev/null 2>&1 || environment_invalid 'docker is unavailable'
command -v timeout >/dev/null 2>&1 || environment_invalid 'timeout is unavailable'
docker compose version >/dev/null 2>&1 || environment_invalid 'docker compose v2 is unavailable'
docker info >/dev/null 2>&1 || environment_invalid 'Docker daemon is unavailable to the CI account'

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
if ! PLAYWRIGHT_DOWNLOAD_CONNECTION_TIMEOUT=120000 \
    timeout 10m "$PLAYWRIGHT_CLI" install chromium \
    > "$ARTIFACTS/playwright-install.log" 2>&1; then
  environment_invalid 'cannot install lockfile-pinned Playwright Chromium within 10 minutes'
fi

if ! docker compose "${COMPOSE_ARGS[@]}" up -d --wait postgres redis; then
  environment_invalid 'skills-c2 PostgreSQL/Redis stack did not become healthy'
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
  environment_invalid 'skills-c2 PostgreSQL did not finish schema initialization within 5 minutes'
fi

FLYWAY_ARGS=(
  -url=jdbc:postgresql://127.0.0.1:25442/aura_boot
  -user=auraboot
  -password=auraboot_dev
  -locations=filesystem:/flyway/sql
  -table=ab_flyway_schema_history
  -baselineOnMigrate=false
  -validateMigrationNaming=true
  -cleanDisabled=true
)
run_flyway() {
  docker run --rm --network host \
    -v "$PROJECT_ROOT/platform/src/main/resources/db/migration/core:/flyway/sql:ro" \
    "$FLYWAY_IMAGE" "${FLYWAY_ARGS[@]}" "$1"
}

if ! run_flyway migrate > "$ARTIFACTS/flyway-migrate.log" 2>&1; then
  printf '[oss-backend-unit-ci] product-failure: Flyway migrate failed\n' >&2
  exit 1
fi
if ! run_flyway validate > "$ARTIFACTS/flyway-validate.log" 2>&1; then
  printf '[oss-backend-unit-ci] product-failure: Flyway validate failed\n' >&2
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
TEST_DATABASE_URL='jdbc:postgresql://127.0.0.1:25442/aura_boot?charSet=UTF8' \
TEST_DATABASE_USERNAME='auraboot' \
TEST_DATABASE_PASSWORD='auraboot_dev' \
DATABASE_URL='jdbc:postgresql://127.0.0.1:25442/aura_boot?charSet=UTF8' \
DATABASE_USERNAME='auraboot' \
DATABASE_PASSWORD='auraboot_dev' \
SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:25442/aura_boot?charSet=UTF8' \
SPRING_DATASOURCE_USERNAME='auraboot' \
SPRING_DATASOURCE_PASSWORD='auraboot_dev' \
SPRING_DATA_REDIS_HOST='127.0.0.1' \
SPRING_DATA_REDIS_PORT='26389' \
SPRING_DATA_REDIS_URL='redis://127.0.0.1:26389' \
platform/gradlew -p platform test
