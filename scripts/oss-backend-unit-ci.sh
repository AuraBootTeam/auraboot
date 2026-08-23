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
  -p "$COMPOSE_PROJECT"
  --profile skills-c2-stack
)

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
  tdengine/tdengine:3.3.4.3 \
  testcontainers/ryuk:0.12.0; do
  if ! docker image inspect "$image" >/dev/null 2>&1; then
    timeout 10m docker pull "$image" || environment_invalid "cannot pull $image within 10 minutes"
  fi
done

if ! docker compose "${COMPOSE_ARGS[@]}" up -d --wait postgres redis; then
  environment_invalid 'skills-c2 PostgreSQL/Redis stack did not become healthy'
fi

cd "$PROJECT_ROOT" || environment_invalid 'cannot enter repository root'
TEST_DATABASE_URL='jdbc:postgresql://127.0.0.1:25442/aura_boot?charSet=UTF8' \
TEST_DATABASE_USERNAME='auraboot' \
TEST_DATABASE_PASSWORD='auraboot_dev' \
DATABASE_URL='jdbc:postgresql://127.0.0.1:25442/aura_boot?charSet=UTF8' \
DATABASE_USERNAME='auraboot' \
DATABASE_PASSWORD='auraboot_dev' \
SPRING_DATA_REDIS_HOST='127.0.0.1' \
SPRING_DATA_REDIS_PORT='26389' \
platform/gradlew -p platform test
