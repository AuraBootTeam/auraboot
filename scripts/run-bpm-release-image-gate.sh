#!/usr/bin/env bash
set -Eeuo pipefail

# CI-only BPM release-image gate (L6). Builds the OSS platform runtime image
# from the control-plane-synced checkout at the pinned ref, boots it against
# an ephemeral Postgres+Redis stack, and requires a green health endpoint.
#
# Daily development and the host-first release layers (P1-P5 in
# scripts/bpm-release-gate.sh) never run this script; per the self-contained
# gate runner rules, Docker packaging authority lives on the Linux CI host.

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PLATFORM_DIR="$REPO_ROOT/platform"

fatal() { printf 'bpm-release-image-gate: %s\n' "$*" >&2; exit 2; }
info() { printf '==> %s\n' "$*"; }
need() { command -v "$1" >/dev/null 2>&1 || fatal "missing dependency: $1"; }

: "${AURA_CI_JOB_ID:?AURA_CI_JOB_ID is required; run through the local CI control plane}"
: "${AURA_REGRESSION_ARTIFACTS:?AURA_REGRESSION_ARTIFACTS is required}"
: "${AURA_CI_BUILDER_ID:?AURA_CI_BUILDER_ID is required}"

for command_name in docker git openssl; do need "$command_name"; done
[[ "$(uname -s)" == "Linux" ]] || fatal "release images must be built on the admitted Linux CI host"
[[ "$(uname -m)" == "x86_64" ]] || fatal "release image builder must be x86_64"

[[ -d "$REPO_ROOT/.git" || -f "$REPO_ROOT/.git" ]] || fatal "CI source checkout missing: $REPO_ROOT"
[[ -z "$(git -C "$REPO_ROOT" status --porcelain --untracked-files=all)" ]] \
  || fatal "CI source checkout is dirty: $REPO_ROOT"

REF_SHA="$(git -C "$REPO_ROOT" rev-parse HEAD)"
[[ "$REF_SHA" =~ ^[0-9a-f]{40}$ ]] || fatal "checkout HEAD is not a 40-character OID: $REF_SHA"
IMAGE_TAG="l6-${REF_SHA:0:12}"
IMAGE_NAME="auraboot-platform:$IMAGE_TAG"

ARTIFACTS="$(cd "$AURA_REGRESSION_ARTIFACTS" && pwd)"
mkdir -p "$ARTIFACTS/logs"
WORK_PARENT="${AURA_CI_RELEASE_WORK_ROOT:-/opt/aura-ci/state/release-image-work}"
LOCK_PARENT="${AURA_CI_RELEASE_LOCK_ROOT:-/opt/aura-ci/state/locks}"
mkdir -p "$WORK_PARENT" "$LOCK_PARENT"
WORK_ROOT="$WORK_PARENT/$AURA_CI_JOB_ID"
LOCK_DIR="$LOCK_PARENT/bpm-release-images.lock"
[[ ! -e "$WORK_ROOT" ]] || fatal "stale release-image work root exists: $WORK_ROOT"
mkdir "$LOCK_DIR" 2>/dev/null || fatal "another release-image build owns $LOCK_DIR"
LOCK_TOKEN="$(openssl rand -hex 16)"
printf '%s\n' "$LOCK_TOKEN" > "$LOCK_DIR/owner"
mkdir "$WORK_ROOT"

PROJECT="bpm-release-local-ci-${AURA_CI_JOB_ID//[^A-Za-z0-9_-]/-}"
NET="bpm-release-$AURA_CI_JOB_ID"
PG_CONTAINER="$PROJECT-pg"
REDIS_CONTAINER="$PROJECT-redis"
APP_CONTAINER="$PROJECT-app"
PG_PORT_ON_NET=5432

PULL_TIMEOUT="${AURA_CI_RELEASE_PULL_TIMEOUT:-180}"

cleanup() {
  local status=$?
  docker rm -f "$APP_CONTAINER" "$REDIS_CONTAINER" "$PG_CONTAINER" >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
  if [[ -d "$LOCK_DIR" && "$(cat "$LOCK_DIR/owner" 2>/dev/null || true)" == "$LOCK_TOKEN" ]]; then
    rm -rf "$LOCK_DIR"
  fi
  if [[ "$status" -eq 0 ]]; then
    docker image rm "$IMAGE_NAME" >/dev/null 2>&1 || true
  fi
  exit "$status"
}
trap cleanup EXIT INT TERM

prefetch_pull() {
  info "prefetch pull (bounded ${PULL_TIMEOUT}s): $1"
  timeout "$PULL_TIMEOUT" docker pull "$1" >/dev/null 2>&1 \
    || fatal "bounded preflight pull failed for $1 — copy the image into the controlled registry namespace by immutable digest (owner step) instead of adding public fallbacks"
}

receipt() {
  python3 - "$ARTIFACTS/release-image-receipt.json" "$@" <<'PY'
import json, sys
path = sys.argv[1]
pairs = sys.argv[2:]
receipt = {}
try:
    receipt = json.load(open(path))
except Exception:
    receipt = {}
for i in range(0, len(pairs), 2):
    receipt[pairs[i]] = pairs[i + 1]
json.dump(receipt, open(path, "w"), indent=1, sort_keys=True)
PY
}

for image in eclipse-temurin:25-jre-alpine pgvector/pgvector:pg16 redis:7-alpine; do
  prefetch_pull "$image"
done

# ---- package: host-first boot jar (warmed wrapper cache), Docker packaging only
info "building platform bootJar at $REF_SHA"
(cd "$PLATFORM_DIR" && ./gradlew bootJar --console=plain) > "$ARTIFACTS/logs/gradle-bootjar.log" 2>&1 \
  || fatal "bootJar build failed — see $(basename "$ARTIFACTS")/logs/gradle-bootjar.log"
BOOT_JAR="$(ls -t "$PLATFORM_DIR"/build/libs/*-boot.jar 2>/dev/null | head -1)"
[[ -n "$BOOT_JAR" ]] || fatal "boot jar not found after build"

info "packaging $IMAGE_NAME"
docker build -f "$PLATFORM_DIR/Dockerfile.runtime" -t "$IMAGE_NAME" "$PLATFORM_DIR" \
  > "$ARTIFACTS/logs/docker-build.log" 2>&1 \
  || fatal "docker build failed — see logs/docker-build.log"
IMAGE_DIGEST="$(docker image inspect "$IMAGE_NAME" --format '{{index .RepoDigests 0}}' 2>/dev/null || true)"
[[ -n "$IMAGE_DIGEST" ]] || IMAGE_DIGEST="local:$IMAGE_NAME"

# ---- verify: boot the image against ephemeral pg+redis, require health UP
info "verifying runtime image against ephemeral stack"
docker network create "$NET" >/dev/null
docker run -d --name "$PG_CONTAINER" --network "$NET" -e POSTGRES_USER=auraboot \
  -e POSTGRES_PASSWORD=auraboot_l6 -e POSTGRES_DB=aura_boot_l6 pgvector/pgvector:pg16 >/dev/null
docker run -d --name "$REDIS_CONTAINER" --network "$NET" redis:7-alpine >/dev/null
for i in $(seq 1 30); do
  if docker exec "$PG_CONTAINER" pg_isready -U auraboot -d aura_boot_l6 >/dev/null 2>&1; then break; fi
  [[ "$i" == "30" ]] && fatal "postgres never became ready"
  sleep 1
done

docker run -d --name "$APP_CONTAINER" --network "$NET" \
  -e SERVER_PORT=6443 \
  -e DATABASE_URL="jdbc:postgresql://$PG_CONTAINER:$PG_PORT_ON_NET/aura_boot_l6" \
  -e SPRING_DATASOURCE_USERNAME=auraboot \
  -e SPRING_DATASOURCE_PASSWORD=auraboot_l6 \
  -e PGHOST="$PG_CONTAINER" -e PGPORT="$PG_PORT_ON_NET" -e PG_DB=aura_boot_l6 \
  -e PGUSER=auraboot -e PGPASSWORD=auraboot_l6 \
  "$IMAGE_NAME" > "$ARTIFACTS/logs/app-container.log" 2>&1 || fatal "app container failed to start"

HEALTH=""
for i in $(seq 1 60); do
  HEALTH="$(docker exec "$APP_CONTAINER" wget -q -O - http://localhost:6443/actuator/health 2>/dev/null || true)"
  if printf '%s' "$HEALTH" | grep -q '"status":"UP"'; then break; fi
  [[ "$i" == "60" ]] && {
    docker logs "$APP_CONTAINER" > "$ARTIFACTS/logs/app-container-tail.log" 2>&1 || true
    fatal "container never reported health UP (last=$HEALTH) — see logs/app-container-tail.log"
  }
  sleep 3
done
info "health UP after ~$((i * 3))s"

docker logs "$APP_CONTAINER" > "$ARTIFACTS/logs/app-container.log" 2>&1 || true

PUSH_STATUS="skipped:no-registry-credentials"
if [[ -n "${AURA_RELEASE_REGISTRY:-}" && -n "${AURA_RELEASE_REGISTRY_USERNAME:-}" \
    && -n "${AURA_RELEASE_REGISTRY_PASSWORD_FILE:-}" && -r "${AURA_RELEASE_REGISTRY_PASSWORD_FILE}" ]]; then
  FULL_IMAGE="${AURA_RELEASE_REGISTRY}/auraboot-platform:${IMAGE_TAG}"
  docker tag "$IMAGE_NAME" "$FULL_IMAGE"
  if docker login "${AURA_RELEASE_REGISTRY%%/*}" \
      -u "$AURA_RELEASE_REGISTRY_USERNAME" \
      --password-stdin < "$AURA_RELEASE_REGISTRY_PASSWORD_FILE" > /dev/null 2>&1; then
    docker push "$FULL_IMAGE" > "$ARTIFACTS/logs/docker-push.log" 2>&1 \
      && PUSH_STATUS="pushed:$FULL_IMAGE" \
      || PUSH_STATUS="push-failed:see-logs"
    docker logout "${AURA_RELEASE_REGISTRY%%/*}" >/dev/null 2>&1 || true
  else
    PUSH_STATUS="push-failed:login"
  fi
fi

receipt \
  ref "$REF_SHA" \
  image "$IMAGE_NAME" \
  digest "$IMAGE_DIGEST" \
  health "UP" \
  push "$PUSH_STATUS" \
  builder "$AURA_CI_BUILDER_ID" \
  job "$AURA_CI_JOB_ID" \
  finishedAt "$(date -u +%FT%TZ)"
info "receipt written; push=$PUSH_STATUS"
info "L6 BPM release image VERIFIED (health UP, ref=$REF_SHA)"
