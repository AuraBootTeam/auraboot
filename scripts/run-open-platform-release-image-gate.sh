#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PLATFORM_DIR="$REPO_ROOT/platform"

fatal() { printf 'open-platform-release-image-gate: %s\n' "$*" >&2; exit 2; }
fail() { printf 'open-platform-release-image-gate: %s\n' "$*" >&2; exit 1; }
info() { printf '==> %s\n' "$*"; }
need() { command -v "$1" >/dev/null 2>&1 || fatal "missing dependency: $1"; }

[[ $# -eq 0 ]] || fatal "this gate has no publish mode; registry publication requires a separate owner-authorized job"
RELEASE_MUTATION="${AURA_OPEN_PLATFORM_RELEASE_MUTATION:-}"
[[ -z "$RELEASE_MUTATION" || "$RELEASE_MUTATION" == "webhook-backlog" ]] \
  || fatal "unsupported AURA_OPEN_PLATFORM_RELEASE_MUTATION: $RELEASE_MUTATION"
: "${AURA_CI_JOB_ID:?AURA_CI_JOB_ID is required}"
: "${AURA_CI_BUILDER_ID:?AURA_CI_BUILDER_ID is required}"
: "${AURA_REGRESSION_ARTIFACTS:?AURA_REGRESSION_ARTIFACTS is required}"
[[ "$(uname -s)" == "Linux" ]] || fatal "release images must be verified on the admitted Linux CI host"
[[ "$(uname -m)" == "x86_64" ]] || fatal "release image builder must be x86_64"
for command_name in docker git openssl python3 timeout; do need "$command_name"; done
[[ -z "$(git -C "$REPO_ROOT" status --porcelain --untracked-files=all)" ]] || fatal "CI checkout is dirty"
REF_SHA="$(git -C "$REPO_ROOT" rev-parse HEAD)"
EXPECTED_REF="${AURA_CI_EXPECTED_REF:-$REF_SHA}"
[[ "$REF_SHA" == "$EXPECTED_REF" && "$REF_SHA" =~ ^[0-9a-f]{40}$ ]] \
  || fatal "checkout HEAD is not the exact expected 40-character ref"

ARTIFACTS="$(cd "$AURA_REGRESSION_ARTIFACTS" && pwd)"
mkdir -p "$ARTIFACTS/logs"
WORK_PARENT="${AURA_CI_RELEASE_WORK_ROOT:-/opt/aura-ci/state/release-image-work}"
LOCK_PARENT="${AURA_CI_RELEASE_LOCK_ROOT:-/opt/aura-ci/state/locks}"
mkdir -p "$WORK_PARENT" "$LOCK_PARENT"
WORK_ROOT="$WORK_PARENT/$AURA_CI_JOB_ID"
LOCK_DIR="$LOCK_PARENT/open-platform-release-images.lock"
[[ ! -e "$WORK_ROOT" ]] || fatal "stale work root exists: $WORK_ROOT"
mkdir "$LOCK_DIR" 2>/dev/null || fatal "another Open Platform release-image build owns the lock"
LOCK_TOKEN="$(openssl rand -hex 16)"
printf '%s\n' "$LOCK_TOKEN" > "$LOCK_DIR/owner"
mkdir "$WORK_ROOT"

SAFE_JOB="${AURA_CI_JOB_ID//[^A-Za-z0-9_-]/-}"
NET="open-platform-$SAFE_JOB"
PG="open-platform-$SAFE_JOB-pg"
REDIS="open-platform-$SAFE_JOB-redis"
APP="open-platform-$SAFE_JOB-app"
IMAGE="auraboot-platform-open-api:ci-${REF_SHA:0:12}"
FLYWAY_IMAGE="${AURA_CI_RELEASE_FLYWAY_IMAGE:-flyway/flyway:12.8.1}"
K6_IMAGE="${AURA_CI_K6_IMAGE:-grafana/k6:1.3.0}"
PYTHON_IMAGE="${AURA_CI_PYTHON_IMAGE:-python:3.13-alpine}"
BUILD_JDK_IMAGE="eclipse-temurin:21-jdk"
RUNTIME_JRE_IMAGE="eclipse-temurin:21-jre-alpine"
PULL_TIMEOUT="${AURA_CI_RELEASE_PULL_TIMEOUT:-300}"
GRADLE_VERSION="8.14.5"
GRADLE_DISTRIBUTION_SHA256="6f74b601422d6d6fc4e1f9a1ab6522f642c2fdcbc15ae33ebd30ba3d7198e854"
GRADLE_DISTRIBUTION_HASH="690y85m0j9nfaub7xoiayko8a"
GRADLE_WRAPPER_HOME="${AURA_CI_GRADLE_WRAPPER_HOME:-${GRADLE_USER_HOME:-$HOME/.gradle}/wrapper}"
GRADLE_DISTRIBUTION_DIR="$GRADLE_WRAPPER_HOME/dists/gradle-${GRADLE_VERSION}-bin/$GRADLE_DISTRIBUTION_HASH"

cleanup() {
  local status=$?
  if docker inspect "$APP" >/dev/null 2>&1; then
    docker logs "$APP" > "$ARTIFACTS/logs/app.log" 2>&1 || true
  fi
  docker rm -f "$APP" "$REDIS" "$PG" >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
  if [[ -d "$LOCK_DIR" && "$(cat "$LOCK_DIR/owner" 2>/dev/null || true)" == "$LOCK_TOKEN" ]]; then
    rm -rf "$LOCK_DIR"
  fi
  [[ "$status" -ne 0 ]] || docker image rm "$IMAGE" >/dev/null 2>&1 || true
  exit "$status"
}
trap cleanup EXIT INT TERM

for base_image in pgvector/pgvector:pg16 redis:7.4-alpine "$FLYWAY_IMAGE" "$K6_IMAGE" "$PYTHON_IMAGE" \
  "$BUILD_JDK_IMAGE" "$RUNTIME_JRE_IMAGE"; do
  timeout "$PULL_TIMEOUT" docker pull "$base_image" >/dev/null 2>&1 \
    || fatal "bounded pull failed for $base_image; mirror it by immutable digest in the controlled registry"
done

# The admitted host installer prewarms this distribution from the configured transport mirror and
# verifies it against Gradle's fixed official SHA-256. Seed the BuildKit cache from that verified
# host copy so a release build never depends on a best-effort services.gradle.org download from
# inside the image build. The tracked wrapper URL and checksum policy remain unchanged.
GRADLE_ZIP="$GRADLE_DISTRIBUTION_DIR/gradle-${GRADLE_VERSION}-bin.zip"
GRADLE_MARKER="$GRADLE_DISTRIBUTION_DIR/.aura-distribution-sha256"
[[ -f "$GRADLE_ZIP" && ! -L "$GRADLE_ZIP" ]] \
  || fatal "verified host Gradle wrapper distribution is missing"
[[ -f "$GRADLE_MARKER" && "$(<"$GRADLE_MARKER")" == "$GRADLE_DISTRIBUTION_SHA256" ]] \
  || fatal "host Gradle wrapper checksum marker is missing or invalid"
printf '%s  %s\n' "$GRADLE_DISTRIBUTION_SHA256" "$GRADLE_ZIP" | sha256sum --check --status \
  || fatal "host Gradle wrapper distribution checksum mismatch"
info "seeding BuildKit Gradle wrapper cache from verified host distribution"
{
  printf '# syntax=docker/dockerfile:1\n'
  printf 'FROM %s\n' "$BUILD_JDK_IMAGE"
  printf 'COPY dists/gradle-%s-bin/%s /tmp/gradle-dist\n' \
    "$GRADLE_VERSION" "$GRADLE_DISTRIBUTION_HASH"
  printf 'RUN --mount=type=cache,target=/root/.gradle/wrapper mkdir -p /root/.gradle/wrapper/dists/gradle-%s-bin/%s && cp -a /tmp/gradle-dist/. /root/.gradle/wrapper/dists/gradle-%s-bin/%s/\n' \
    "$GRADLE_VERSION" "$GRADLE_DISTRIBUTION_HASH" "$GRADLE_VERSION" "$GRADLE_DISTRIBUTION_HASH"
} | docker build -f - "$GRADLE_WRAPPER_HOME" \
  > "$ARTIFACTS/logs/gradle-wrapper-cache-seed.log" 2>&1 \
  || fatal "failed to seed BuildKit Gradle wrapper cache"

STAGE="$WORK_ROOT/context"
mkdir -p "$STAGE"
git -C "$REPO_ROOT" archive --format=tar HEAD | tar -x -C "$STAGE"
info "building exact-ref image $IMAGE"
docker build -f "$STAGE/platform/Dockerfile" -t "$IMAGE" "$STAGE" \
  > "$ARTIFACTS/logs/docker-build.log" 2>&1 || fail "image build failed"
DIGEST="$(docker image inspect "$IMAGE" --format '{{.Id}}')"

docker network create "$NET" >/dev/null
docker run -d --name "$PG" --network "$NET" -e POSTGRES_USER=auraboot \
  -e POSTGRES_PASSWORD=open_platform_ci -e POSTGRES_DB=open_platform_ci pgvector/pgvector:pg16 >/dev/null
docker run -d --name "$REDIS" --network "$NET" redis:7.4-alpine >/dev/null
for attempt in $(seq 1 30); do
  docker exec "$PG" pg_isready -U auraboot -d open_platform_ci >/dev/null 2>&1 && break
  [[ "$attempt" != 30 ]] || fatal "PostgreSQL did not become ready"
  sleep 1
done
docker run --rm --network "$NET" -v "$STAGE/platform/src/main/resources/db/migration/core":/flyway/sql:ro \
  "$FLYWAY_IMAGE" -url="jdbc:postgresql://$PG:5432/open_platform_ci" -user=auraboot \
  -password=open_platform_ci -locations=filesystem:/flyway/sql -table=ab_flyway_schema_history \
  -baselineOnMigrate=false -validateMigrationNaming=true -cleanDisabled=true migrate \
  > "$ARTIFACTS/logs/flyway.log" 2>&1 || fail "Flyway migration failed"

PROTOCOL_KEY="$(openssl rand -base64 48 | tr -d '\n')"
docker run -d --name "$APP" --network "$NET" \
  -v "$STAGE/plugins":/plugins:ro \
  -e SERVER_PORT=6443 -e SPRING_PROFILES_ACTIVE=test \
  -e DATABASE_URL="jdbc:postgresql://$PG:5432/open_platform_ci" \
  -e SPRING_DATASOURCE_USERNAME=auraboot -e SPRING_DATASOURCE_PASSWORD=open_platform_ci \
  -e REDIS_HOST="$REDIS" -e REDIS_PORT=6379 \
  -e AURA_BUILTIN_PLUGINS_DIR=/app/plugins \
  -e OPEN_PLATFORM_PROTOCOL_SIGNING_KEY="$PROTOCOL_KEY" "$IMAGE" >/dev/null
for attempt in $(seq 1 90); do
  HEALTH="$(docker exec "$APP" wget -q -O - http://localhost:6443/actuator/health 2>/dev/null || true)"
  printf '%s' "$HEALTH" | grep -q '"status":"UP"' && break
  [[ "$attempt" != 90 ]] || { docker logs "$APP" > "$ARTIFACTS/logs/app.log" 2>&1; fail "app health did not become UP"; }
  sleep 2
done

info "running two-domain HTTP protocol probe"
docker run --rm --network "$NET" -v "$STAGE/scripts/ci":/probe:ro -v "$ARTIFACTS":/artifacts \
  -e BASE_URL="http://$APP:6443" "$PYTHON_IMAGE" python /probe/open-platform-release-probe.py \
  > "$ARTIFACTS/protocol-probe.json" 2> "$ARTIFACTS/logs/protocol-probe.log" \
  || fail "two-domain HTTP protocol probe failed"
CLIENT_ID="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["clientId"])' "$ARTIFACTS/open-platform-credentials.json")"
CLIENT_SECRET="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["clientSecret"])' "$ARTIFACTS/open-platform-credentials.json")"

info "running production-threshold k6 profile inside the CI network"
docker run --rm --network "$NET" -v "$STAGE/tests/load/k6":/scripts:ro \
  -v "$ARTIFACTS":/artifacts -e PROFILE=production -e BASE_URL="http://$APP:6443" \
  -e CLIENT_ID="$CLIENT_ID" -e CLIENT_SECRET="$CLIENT_SECRET" "$K6_IMAGE" run \
  --summary-export /artifacts/slo-summary.json /scripts/open-platform-slo.js \
  > "$ARTIFACTS/logs/k6.log" 2>&1 || fail "Open Platform production SLO thresholds failed"
rm -f "$ARTIFACTS/open-platform-credentials.json"

info "waiting for Webhook queue drain"
if [[ "$RELEASE_MUTATION" == "webhook-backlog" ]]; then
  docker exec "$PG" psql -U auraboot -d open_platform_ci -v ON_ERROR_STOP=1 -c \
    "INSERT INTO ab_webhook_subscription (tenant_id,pid,name,target_url,event_type)
       SELECT id,'ci-drain-mutation','CI drain mutation','https://example.invalid/hook','ci.mutation'
       FROM ab_tenant ORDER BY id LIMIT 1;
     INSERT INTO ab_webhook_delivery_log
       (pid,tenant_id,subscription_pid,request_url,delivery_status,retry_count,max_retries,next_retry_at,created_at,updated_at)
       SELECT 'ci-drain-delivery',tenant_id,pid,target_url,'pending',0,3,NOW(),NOW(),NOW()
       FROM ab_webhook_subscription WHERE pid='ci-drain-mutation';" >/dev/null
fi
DRAINED=0
DRAIN_ATTEMPTS=30
[[ "$RELEASE_MUTATION" != "webhook-backlog" ]] || DRAIN_ATTEMPTS=1
for attempt in $(seq 1 "$DRAIN_ATTEMPTS"); do
  BACKLOG="$(docker exec "$PG" psql -U auraboot -d open_platform_ci -Atc \
    "SELECT COUNT(*) FROM ab_webhook_delivery_log WHERE delivery_status IN ('pending','processing','failed','dead_letter')")"
  if [[ "$BACKLOG" == "0" ]]; then DRAINED=1; break; fi
  sleep 2
done
if [[ "$DRAINED" != 1 && "$RELEASE_MUTATION" == "webhook-backlog" ]]; then
  python3 - "$ARTIFACTS/mutation-receipt.json" <<PY
import json, sys
json.dump({"mutation": "webhook-backlog", "verdict": "EXPECTED_RED", "backlog": int("$BACKLOG"),
           "ref": "$REF_SHA", "releaseReceiptCreated": False}, open(sys.argv[1], "w"), indent=2)
PY
  fail "EXPECTED_RED: Webhook backlog mutation was rejected; backlog=$BACKLOG"
fi
[[ "$DRAINED" == 1 ]] || fail "Webhook queue did not drain cleanly; backlog=$BACKLOG"

docker logs "$APP" > "$ARTIFACTS/logs/app.log" 2>&1 || true
python3 - "$ARTIFACTS/release-image-receipt.json" <<PY
import json, sys
json.dump({
  "ref": "$REF_SHA", "image": "$IMAGE", "digest": "$DIGEST", "health": "UP",
  "protocolProbe": "assets+inventory+cursor+etag+catalog", "slo": "production-thresholds-ok",
  "webhookDrainBacklog": 0, "registryPush": "disabled", "builder": "$AURA_CI_BUILDER_ID",
  "job": "$AURA_CI_JOB_ID"
}, open(sys.argv[1], "w"), indent=2, sort_keys=True)
PY
info "Open Platform release image VERIFIED at $REF_SHA; registry push disabled"
