#!/usr/bin/env bash
set -Eeuo pipefail

# CI-only renderer release-image gate. Builds the renderer-capable backend
# image (platform/Dockerfile.renderer-runtime) from a staged context of the
# exact checked-out ref, boots it against an ephemeral Postgres stack (schema
# via the pinned Flyway image), requires health UP, and then proves the
# in-image report renderer end to end: a CJK report is rendered by the
# packaged Node + Playwright Chromium + Noto CJK stack and the resulting PDF
# must be a Chromium-authored document whose extracted text still contains
# the CJK content. Local macOS renderer packages never substitute this gate.

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PLATFORM_DIR="$REPO_ROOT/platform"

# Exit-code contract (tools/regression/orchestrator.sh): 0 pass, 1 product
# failure, 2 environment-invalid.
fatal() { printf 'renderer-release-image-gate: %s\n' "$*" >&2; exit 2; }
fail() { printf 'renderer-release-image-gate: %s\n' "$*" >&2; exit 1; }
info() { printf '==> %s\n' "$*"; }
need() { command -v "$1" >/dev/null 2>&1 || fatal "missing dependency: $1"; }

PUSH_ENABLED=1
while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-push) PUSH_ENABLED=0 ;;
    --help|-h)
      printf 'Usage: %s [--no-push]\nRun through the local CI control plane. --no-push verifies packaging without registry login or publication.\n' "$0"
      exit 0
      ;;
    *) fatal "unknown argument: $1" ;;
  esac
  shift
done

: "${AURA_CI_JOB_ID:?AURA_CI_JOB_ID is required; run through the local CI control plane}"
: "${AURA_REGRESSION_ARTIFACTS:?AURA_REGRESSION_ARTIFACTS is required}"
: "${AURA_CI_BUILDER_ID:?AURA_CI_BUILDER_ID is required}"

for command_name in docker git openssl python3; do need "$command_name"; done
[[ "$(uname -s)" == "Linux" ]] || fatal "release images must be built on the admitted Linux CI host"
[[ "$(uname -m)" == "x86_64" ]] || fatal "release image builder must be x86_64"

[[ -d "$REPO_ROOT/.git" || -f "$REPO_ROOT/.git" ]] || fatal "CI source checkout missing: $REPO_ROOT"
[[ -z "$(git -C "$REPO_ROOT" status --porcelain --untracked-files=all)" ]] \
  || fatal "CI source checkout is dirty: $REPO_ROOT"

REF_SHA="$(git -C "$REPO_ROOT" rev-parse HEAD)"
[[ "$REF_SHA" =~ ^[0-9a-f]{40}$ ]] || fatal "checkout HEAD is not a 40-character OID: $REF_SHA"
IMAGE_TAG="r7-${REF_SHA:0:12}"
IMAGE_NAME="auraboot-platform-renderer:$IMAGE_TAG"

ARTIFACTS="$(cd "$AURA_REGRESSION_ARTIFACTS" && pwd)"
mkdir -p "$ARTIFACTS/logs"
WORK_PARENT="${AURA_CI_RELEASE_WORK_ROOT:-/opt/aura-ci/state/release-image-work}"
LOCK_PARENT="${AURA_CI_RELEASE_LOCK_ROOT:-/opt/aura-ci/state/locks}"
mkdir -p "$WORK_PARENT" "$LOCK_PARENT"
WORK_ROOT="$WORK_PARENT/$AURA_CI_JOB_ID"
LOCK_DIR="$LOCK_PARENT/renderer-release-images.lock"
[[ ! -e "$WORK_ROOT" ]] || fatal "stale release-image work root exists: $WORK_ROOT"
mkdir "$LOCK_DIR" 2>/dev/null || fatal "another release-image build owns $LOCK_DIR"
LOCK_TOKEN="$(openssl rand -hex 16)"
printf '%s\n' "$LOCK_TOKEN" > "$LOCK_DIR/owner"
mkdir "$WORK_ROOT"

PROJECT="renderer-release-local-ci-${AURA_CI_JOB_ID//[^A-Za-z0-9_-]/-}"
NET="renderer-release-$AURA_CI_JOB_ID"
PG_CONTAINER="$PROJECT-pg"
APP_CONTAINER="$PROJECT-app"
PG_PORT_ON_NET=5432

PULL_TIMEOUT="${AURA_CI_RELEASE_PULL_TIMEOUT:-300}"
FLYWAY_IMAGE="${AURA_CI_RELEASE_FLYWAY_IMAGE:-flyway/flyway:12.8.1}"

cleanup() {
  local status=$?
  docker rm -f "$APP_CONTAINER" "$PG_CONTAINER" >/dev/null 2>&1 || true
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

for image in eclipse-temurin:25-jre node:22-bookworm-slim pgvector/pgvector:pg16 "$FLYWAY_IMAGE"; do
  prefetch_pull "$image"
done

# ---- package: host-first boot jar, then a staged context of the exact ref
info "building platform bootJar at $REF_SHA"
(cd "$PLATFORM_DIR" && ./gradlew bootJar --console=plain) > "$ARTIFACTS/logs/gradle-bootjar.log" 2>&1 \
  || fail "bootJar build failed — see logs/gradle-bootjar.log"
BOOT_JAR="$(ls -t "$PLATFORM_DIR"/build/libs/*-boot.jar 2>/dev/null | head -1)"
[[ -n "$BOOT_JAR" ]] || fail "boot jar not found after build"

STAGE="$WORK_ROOT/context"
mkdir -p "$STAGE"
info "staging build context from exact ref $REF_SHA"
git -C "$REPO_ROOT" archive --format=tar "HEAD" | tar -x -C "$STAGE"
cp "$BOOT_JAR" "$STAGE/auraboot-boot.jar"

info "packaging $IMAGE_NAME"
docker build -f "$STAGE/platform/Dockerfile.renderer-runtime" -t "$IMAGE_NAME" "$STAGE" \
  > "$ARTIFACTS/logs/docker-build.log" 2>&1 \
  || fail "docker build failed — see logs/docker-build.log"
IMAGE_DIGEST="$(docker image inspect "$IMAGE_NAME" --format '{{index .RepoDigests 0}}' 2>/dev/null || true)"
[[ -n "$IMAGE_DIGEST" ]] || IMAGE_DIGEST="local:$IMAGE_NAME"

# ---- verify boot against the ephemeral stack with the renderer wired in
info "verifying runtime image against ephemeral stack"
docker network create "$NET" >/dev/null
docker run -d --name "$PG_CONTAINER" --network "$NET" -e POSTGRES_USER=auraboot \
  -e POSTGRES_PASSWORD=auraboot_r7 -e POSTGRES_DB=aura_boot_r7 pgvector/pgvector:pg16 >/dev/null
for i in $(seq 1 30); do
  if docker exec "$PG_CONTAINER" pg_isready -U auraboot -d aura_boot_r7 >/dev/null 2>&1; then break; fi
  [[ "$i" == "30" ]] && fatal "postgres never became ready"
  sleep 1
done

docker run --rm --network "$NET" \
  -v "$STAGE/platform/src/main/resources/db/migration/core":/flyway/sql:ro \
  "$FLYWAY_IMAGE" \
  -url="jdbc:postgresql://$PG_CONTAINER:$PG_PORT_ON_NET/aura_boot_r7" \
  -user=auraboot -password=auraboot_r7 \
  -locations=filesystem:/flyway/sql \
  -table=ab_flyway_schema_history \
  -baselineOnMigrate=false -validateMigrationNaming=true -cleanDisabled=true \
  migrate > "$ARTIFACTS/logs/flyway-migrate.log" 2>&1 \
  || fail "flyway migrate failed — see logs/flyway-migrate.log"
info "schema migrated via $FLYWAY_IMAGE"

docker run -d --name "$APP_CONTAINER" --network "$NET" \
  -e SERVER_PORT=6443 \
  -e DATABASE_URL="jdbc:postgresql://$PG_CONTAINER:$PG_PORT_ON_NET/aura_boot_r7" \
  -e SPRING_DATASOURCE_USERNAME=auraboot \
  -e SPRING_DATASOURCE_PASSWORD=auraboot_r7 \
  -e SPRING_APPLICATION_JSON='{"auraboot":{"report-export":{"renderer":{"command":["/app/report-renderer/render-report"]}}}}' \
  "$IMAGE_NAME" > "$ARTIFACTS/logs/app-container.log" 2>&1 || fail "app container failed to start"

HEALTH=""
for i in $(seq 1 60); do
  HEALTH="$(docker exec "$APP_CONTAINER" wget -q -O - http://localhost:6443/actuator/health 2>/dev/null || true)"
  if printf '%s' "$HEALTH" | grep -q '"status":"UP"'; then break; fi
  [[ "$i" == "60" ]] && {
    docker logs "$APP_CONTAINER" > "$ARTIFACTS/logs/app-container-tail.log" 2>&1 || true
    fail "container never reported health UP (last=$HEALTH) — see logs/app-container-tail.log"
  }
  sleep 3
done
info "health UP after ~$((i * 3))s"

# ---- in-image renderer proof: Node + Playwright Chromium + Noto CJK
info "rendering a CJK report inside the image"
SAMPLE='{"model":{"version":"1.0.0","title":"发布镜像中文字体校验","body":[{"id":"t","blockType":"table","columns":[{"field":"v","label":"中文表头"}]}]},"dataSets":{}}'
PDF_PATH=/tmp/renderer-proof.pdf
if ! printf '%s' "$SAMPLE" | docker exec -i "$APP_CONTAINER" /app/report-renderer/render-report --out "$PDF_PATH" \
  > "$ARTIFACTS/logs/renderer-run.log" 2>&1; then
  fail "in-image renderer execution failed — see logs/renderer-run.log"
fi
docker exec "$APP_CONTAINER" sh -c "head -c 4 '$PDF_PATH'" > "$ARTIFACTS/renderer-proof-head.txt" 2>/dev/null || true
[[ "$(cat "$ARTIFACTS/renderer-proof-head.txt" 2>/dev/null)" == "%PDF" ]] \
  || fail "renderer output is not a PDF (head=$(cat "$ARTIFACTS/renderer-proof-head.txt" 2>/dev/null))"
if ! docker exec "$APP_CONTAINER" sh -c "grep -a -m1 '/Creator' '$PDF_PATH' || strings '$PDF_PATH' | grep -m1 Chromium" \
  > "$ARTIFACTS/renderer-proof-creator.txt" 2>&1; then
  fail "renderer PDF shows no Chromium authorship — see logs/renderer-run.log"
fi
docker exec "$APP_CONTAINER" pdftotext "$PDF_PATH" - > "$ARTIFACTS/renderer-proof.txt" 2>/dev/null || true
grep -q '发布镜像中文字体校验' "$ARTIFACTS/renderer-proof.txt" \
  || fail "CJK content missing from extracted PDF text — fonts not usable in image"
grep -q '中文表头' "$ARTIFACTS/renderer-proof.txt" \
  || fail "CJK table header missing from extracted PDF text"
info "in-image Chromium PDF with CJK text verified"

docker logs "$APP_CONTAINER" > "$ARTIFACTS/logs/app-container.log" 2>&1 || true
docker cp "$APP_CONTAINER" "$PDF_PATH" "$ARTIFACTS/renderer-proof.pdf" >/dev/null 2>&1 || true

PUSH_STATUS="skipped:no-registry-credentials"
if [[ "$PUSH_ENABLED" -eq 0 ]]; then
  PUSH_STATUS="skipped:disabled"
fi
if [[ "$PUSH_ENABLED" -eq 1 && -n "${AURA_RELEASE_REGISTRY:-}" && -n "${AURA_RELEASE_REGISTRY_USERNAME:-}" \
    && -n "${AURA_RELEASE_REGISTRY_PASSWORD_FILE:-}" && -r "${AURA_RELEASE_REGISTRY_PASSWORD_FILE:-}" ]]; then
  FULL_IMAGE="${AURA_RELEASE_REGISTRY}/auraboot-platform-renderer:${IMAGE_TAG}"
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
  renderer "chromium-cjk-ok" \
  push "$PUSH_STATUS" \
  builder "$AURA_CI_BUILDER_ID" \
  job "$AURA_CI_JOB_ID" \
  finishedAt "$(date -u +%FT%TZ)"
info "receipt written; push=$PUSH_STATUS"
info "renderer release image VERIFIED (health UP, Chromium CJK PDF ok, ref=$REF_SHA)"
