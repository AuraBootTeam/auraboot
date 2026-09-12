#!/usr/bin/env bash
set -Eeuo pipefail

fatal() { printf 'product-release-image-gate: %s\n' "$*" >&2; exit 2; }
fail() { printf 'product-release-image-gate: %s\n' "$*" >&2; exit 1; }
info() { printf '==> %s\n' "$*"; }
need() { command -v "$1" >/dev/null 2>&1 || fatal "missing dependency: $1"; }

: "${AURA_CI_JOB_ID:?run through the local CI control plane}"
: "${AURA_REGRESSION_ARTIFACTS:?AURA_REGRESSION_ARTIFACTS is required}"
: "${AURA_CI_BUILDER_ID:?AURA_CI_BUILDER_ID is required}"
: "${AURA_CORE_PROJECT_ROOT:?AURA_CORE_PROJECT_ROOT is required}"
: "${AURA_PRODUCT_PROJECT_ROOT:?AURA_PRODUCT_PROJECT_ROOT is required}"
: "${AURA_PRODUCT_ID:?AURA_PRODUCT_ID is required}"
: "${AURA_PRODUCT_MIGRATION_OWNER:?AURA_PRODUCT_MIGRATION_OWNER is required}"
: "${AURA_PRODUCT_IMAGE_LAYOUT:?AURA_PRODUCT_IMAGE_LAYOUT is required}"
: "${AURA_PRODUCT_LIFECYCLE:?AURA_PRODUCT_LIFECYCLE is required}"

for command_name in curl docker git node openssl pnpm python3 tar; do need "$command_name"; done
[[ "$(uname -s)" == Linux ]] || fatal 'release images must be built on the admitted Linux CI host'
[[ "$(uname -m)" == x86_64 ]] || fatal 'release image builder must be x86_64'
[[ "${AURA_OCI_BUILDER:-docker}" == docker ]] || fatal 'AURA_OCI_BUILDER must be docker; local container fallbacks are prohibited'

CORE_ROOT="$(cd "$AURA_CORE_PROJECT_ROOT" && pwd)"
PRODUCT_ROOT="$(cd "$AURA_PRODUCT_PROJECT_ROOT" && pwd)"
LIFECYCLE_SOURCE="$PRODUCT_ROOT/$AURA_PRODUCT_LIFECYCLE"
for repo in "$CORE_ROOT" "$PRODUCT_ROOT"; do
  [[ -z "$(git -C "$repo" status --porcelain --untracked-files=all)" ]] || fatal "CI checkout is dirty: $repo"
done
CORE_SHA="$(git -C "$CORE_ROOT" rev-parse HEAD)"
PRODUCT_SHA="$(git -C "$PRODUCT_ROOT" rev-parse HEAD)"
[[ "$CORE_SHA" =~ ^[0-9a-f]{40}$ && "$PRODUCT_SHA" =~ ^[0-9a-f]{40}$ ]] || fatal 'checkout HEAD is not immutable'

ARTIFACTS="$(cd "$AURA_REGRESSION_ARTIFACTS" && pwd)"
mkdir -p "$ARTIFACTS/logs" "$ARTIFACTS/e2e"
WORK_PARENT="${AURA_CI_RELEASE_WORK_ROOT:-/opt/aura-ci/state/release-image-work}"
mkdir -p "$WORK_PARENT"
WORK_ROOT="$WORK_PARENT/${AURA_CI_JOB_ID}-${AURA_PRODUCT_ID}"
[[ ! -e "$WORK_ROOT" ]] || fatal "stale work root exists: $WORK_ROOT"
mkdir "$WORK_ROOT"
CORE_RELEASE="$WORK_ROOT/core-release"
PRODUCT_RELEASE="$WORK_ROOT/product-release"
STATE_ROOT="$WORK_ROOT/runtime"
PROJECT="${AURA_PRODUCT_ID}-${AURA_CI_JOB_ID//[^A-Za-z0-9_-]/-}"
NETWORK="$PROJECT-net"
PG_CONTAINER="$PROJECT-pg"
APP_CONTAINER="$PROJECT-app"
IMAGE_REF=''
IMAGE_ID=''

cleanup() {
  local status=$?
  if [[ -x "$PRODUCT_RELEASE/$AURA_PRODUCT_LIFECYCLE" ]]; then
    AURA_APP_ARTIFACT_ROOT="$PRODUCT_RELEASE" AURA_STATE_ROOT="$STATE_ROOT" \
      "$PRODUCT_RELEASE/$AURA_PRODUCT_LIFECYCLE" stop >/dev/null 2>&1 || true
  fi
  docker rm -f "$APP_CONTAINER" "$PG_CONTAINER" >/dev/null 2>&1 || true
  docker network rm "$NETWORK" >/dev/null 2>&1 || true
  [[ -z "$IMAGE_REF" ]] || docker image rm "$IMAGE_REF" >/dev/null 2>&1 || true
  [[ -z "$IMAGE_ID" ]] || docker image rm "$IMAGE_ID" >/dev/null 2>&1 || true
  if [[ "$status" -eq 0 && -d "$WORK_ROOT" ]]; then find "$WORK_ROOT" -depth -delete; fi
  exit "$status"
}
trap cleanup EXIT INT TERM

info "building AuraBoot artifacts core=$CORE_SHA"
AURA_OCI_BUILDER=docker node "$CORE_ROOT/scripts/application/stage-core-only-artifacts.mjs" \
  --repo-root "$CORE_ROOT" --output "$CORE_RELEASE" >"$ARTIFACTS/logs/core-artifacts.log" 2>&1 \
  || fail 'AuraBoot artifact build failed; see logs/core-artifacts.log'

info "building $AURA_PRODUCT_ID artifacts product=$PRODUCT_SHA"
AURA_OCI_BUILDER=docker node "$PRODUCT_ROOT/scripts/build-application.mjs" \
  --platform-artifacts "$CORE_RELEASE" --output "$PRODUCT_RELEASE" \
  >"$ARTIFACTS/logs/product-artifacts.log" 2>&1 \
  || fail 'product artifact build failed; see logs/product-artifacts.log'

OCI_TAR="$WORK_ROOT/product-image.oci.tar"
tar -C "$PRODUCT_RELEASE/$AURA_PRODUCT_IMAGE_LAYOUT" -cf "$OCI_TAR" .
LOAD_OUTPUT="$(docker load --input "$OCI_TAR")" || fail 'Docker could not load the OCI image'
printf '%s\n' "$LOAD_OUTPUT" >"$ARTIFACTS/logs/docker-load.log"
IMAGE_REF="$(printf '%s\n' "$LOAD_OUTPUT" | sed -n 's/^Loaded image: //p' | tail -1)"
[[ -n "$IMAGE_REF" ]] || IMAGE_REF="$(printf '%s\n' "$LOAD_OUTPUT" | sed -n 's/^Loaded image ID: //p' | tail -1)"
[[ -n "$IMAGE_REF" ]] || fail 'Docker load did not report an image reference'
IMAGE_ID="$(docker image inspect "$IMAGE_REF" --format '{{.Id}}')"
[[ "$IMAGE_ID" =~ ^sha256:[0-9a-f]{64}$ ]] || fail 'loaded image lacks an immutable image ID'

docker run --rm --entrypoint sh "$IMAGE_REF" -ec \
  "test -f /opt/auraboot/runtime/application.jar && test -f /opt/auraboot/application.lock && test -d /opt/auraboot/plugins && test -d /opt/auraboot/web" \
  >"$ARTIFACTS/logs/payload-check.log" 2>&1 || fail 'release image payload is incomplete'

PG_IMAGE="${AURA_CI_PGVECTOR_IMAGE:-pgvector/pgvector:pg17}"
FLYWAY_IMAGE="${AURA_CI_FLYWAY_IMAGE:-flyway/flyway:12.8.1}"
docker network create "$NETWORK" >/dev/null
docker run -d --name "$PG_CONTAINER" --network "$NETWORK" -p 127.0.0.1::5432 \
  -e POSTGRES_USER=auraboot -e POSTGRES_PASSWORD=auraboot_ci -e POSTGRES_DB=aura_product_ci \
  "$PG_IMAGE" >/dev/null || fatal 'PostgreSQL container failed to start'
for attempt in $(seq 1 60); do
  docker exec "$PG_CONTAINER" pg_isready -U auraboot -d aura_product_ci >/dev/null 2>&1 && break
  [[ "$attempt" -lt 60 ]] || fatal 'PostgreSQL never became ready'
  sleep 1
done
PG_PORT="$(docker port "$PG_CONTAINER" 5432/tcp | tail -1)"; PG_PORT="${PG_PORT##*:}"

docker run --rm --network "$NETWORK" \
  -v "$PRODUCT_RELEASE/migrations/core":/flyway/core:ro \
  -v "$PRODUCT_RELEASE/migrations/$AURA_PRODUCT_MIGRATION_OWNER":/flyway/product:ro \
  "$FLYWAY_IMAGE" -url="jdbc:postgresql://$PG_CONTAINER:5432/aura_product_ci" \
  -user=auraboot -password=auraboot_ci \
  -locations=filesystem:/flyway/core,filesystem:/flyway/product \
  -table=ab_flyway_schema_history -baselineOnMigrate=false -validateMigrationNaming=true -cleanDisabled=true migrate \
  >"$ARTIFACTS/logs/flyway.log" 2>&1 || fail 'fresh database migration failed; see logs/flyway.log'

LOCK_IDENTITY="$(node -e "const fs=require('node:fs');process.stdout.write(JSON.parse(fs.readFileSync(process.argv[1],'utf8')).identity)" "$PRODUCT_RELEASE/application.lock")"
APP_VERSION="$(node -e "const fs=require('node:fs');process.stdout.write(JSON.parse(fs.readFileSync(process.argv[1],'utf8')).application.version)" "$PRODUCT_RELEASE/application.lock")"
LAYOUT_DIGEST="$(node -e "const r=require(process.argv[1]);process.stdout.write(r.image.digest)" "$PRODUCT_RELEASE/release-receipt.json")"
JWT_SECRET="$(openssl rand -hex 32)"; SESSION_SECRET="$(openssl rand -hex 32)"; ADMIN_PASSWORD="$(openssl rand -base64 24 | tr -d '\n')Aa1!"
docker run -d --name "$APP_CONTAINER" --network "$NETWORK" -p 127.0.0.1::6443 \
  -e SERVER_PORT=6443 -e SPRING_PROFILES_ACTIVE=community \
  -e SPRING_DATASOURCE_URL="jdbc:postgresql://$PG_CONTAINER:5432/aura_product_ci" \
  -e SPRING_DATASOURCE_USERNAME=auraboot -e SPRING_DATASOURCE_PASSWORD=auraboot_ci \
  -e AURA_APPLICATION_MODE=application -e AURABOOT_BOOTSTRAP_ENABLED=false -e AURABOOT_DEMO_SEED=false \
  -e JWT_SECRET="$JWT_SECRET" -e JAVA_TOOL_OPTIONS=-Daura.plugins.dir=/opt/auraboot/plugins \
  -e AURA_APPLICATION_ID="$AURA_PRODUCT_ID" -e AURA_APPLICATION_VERSION="$APP_VERSION" \
  -e AURA_APPLICATION_LOCK_IDENTITY="$LOCK_IDENTITY" -e AURA_APPLICATION_SOURCE_COMMIT="$PRODUCT_SHA" \
  -e AURA_APPLICATION_IMAGE_DIGEST="$LAYOUT_DIGEST" "$IMAGE_REF" \
  --aura.persistence.tenant-bypass-table-prefixes=se_ >/dev/null || fail 'application image failed to start'
APP_PORT="$(docker port "$APP_CONTAINER" 6443/tcp | tail -1)"; APP_PORT="${APP_PORT##*:}"
for attempt in $(seq 1 90); do
  curl -fsS "http://127.0.0.1:$APP_PORT/actuator/health" | grep -q '"status":"UP"' && break
  [[ "$attempt" -lt 90 ]] || { docker logs "$APP_CONTAINER" >"$ARTIFACTS/logs/application.log" 2>&1; fail 'application image never became healthy'; }
  sleep 2
done

WEB_PORT="$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()')"
COMMON_ENV=(AURA_APP_ARTIFACT_ROOT="$PRODUCT_RELEASE" AURA_STATE_ROOT="$STATE_ROOT" AURA_BACKEND_PORT="$APP_PORT" AURA_WEB_PORT="$WEB_PORT" PGHOST=127.0.0.1 PGPORT="$PG_PORT" PGDATABASE=aura_product_ci PGUSER=auraboot PGPASSWORD=auraboot_ci ADMIN_EMAIL=admin@auraboot.local ADMIN_PASSWORD="$ADMIN_PASSWORD" SESSION_SECRET="$SESSION_SECRET" JWT_SECRET="$JWT_SECRET" PUBLIC_URL="http://127.0.0.1:$WEB_PORT")
env "${COMMON_ENV[@]}" "$PRODUCT_RELEASE/$AURA_PRODUCT_LIFECYCLE" init-core >"$ARTIFACTS/logs/init-core.log" 2>&1 || fail 'explicit core initialization failed'
env "${COMMON_ENV[@]}" "$PRODUCT_RELEASE/$AURA_PRODUCT_LIFECYCLE" publish >"$ARTIFACTS/logs/publish.log" 2>&1 || fail 'explicit product publish failed'
mkdir -p "$STATE_ROOT"; docker logs "$APP_CONTAINER" >"$STATE_ROOT/runtime.log" 2>&1
env "${COMMON_ENV[@]}" "$PRODUCT_RELEASE/$AURA_PRODUCT_LIFECYCLE" start-web >"$ARTIFACTS/logs/web.log" 2>&1 || fail 'release Web BFF failed to start'
env "${COMMON_ENV[@]}" "$PRODUCT_RELEASE/$AURA_PRODUCT_LIFECYCLE" verify >"$ARTIFACTS/logs/verify.log" 2>&1 || fail 'artifact identity verification failed'

pnpm --dir "$PRODUCT_ROOT" install --frozen-lockfile --ignore-scripts >"$ARTIFACTS/logs/pnpm-install.log" 2>&1 || fatal 'product test dependencies unavailable'
env "${COMMON_ENV[@]}" PLAYWRIGHT_BASE_URL="http://127.0.0.1:$WEB_PORT" PW_SKIP_WEBSERVER=1 \
  PW_ARTIFACT_DIR="$ARTIFACTS/e2e/artifacts" PW_RESULTS_JSON="$ARTIFACTS/e2e/results.json" \
  pnpm --dir "$PRODUCT_ROOT" exec playwright test --config playwright.release.config.ts \
  >"$ARTIFACTS/logs/playwright.log" 2>&1 || fail 'release-image browser journey failed'

cp "$CORE_RELEASE/release-receipt.json" "$ARTIFACTS/core-build-receipt.json"
cp "$PRODUCT_RELEASE/release-receipt.json" "$ARTIFACTS/product-build-receipt.json"
python3 - "$ARTIFACTS/release-image-receipt.json" "$AURA_PRODUCT_ID" "$CORE_SHA" "$PRODUCT_SHA" "$LOCK_IDENTITY" "$LAYOUT_DIGEST" "$IMAGE_ID" "$AURA_CI_BUILDER_ID" "$AURA_CI_JOB_ID" <<'PY'
import datetime, json, sys
path, product, core, source, lock, layout, image_id, builder, job = sys.argv[1:]
json.dump({"schemaVersion": 1, "status": "PASS", "product": product,
           "coreCommit": core, "productCommit": source, "lockIdentity": lock,
           "ociLayoutDigest": layout, "loadedImageId": image_id, "builder": builder,
           "job": job, "freshDatabase": "PASS", "payload": "PASS",
           "readiness": "PASS", "browserJourney": "PASS",
           "finishedAt": datetime.datetime.now(datetime.timezone.utc).isoformat()},
          open(path, "w"), indent=2, sort_keys=True)
PY
info "$AURA_PRODUCT_ID release image VERIFIED on Linux CI Docker (image=$IMAGE_ID)"
