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
: "${AURA_RELEASE_SCREENSHOT_IDS:?AURA_RELEASE_SCREENSHOT_IDS is required}"
: "${AURA_RELEASE_REGISTRY:?AURA_RELEASE_REGISTRY is required}"
: "${AURA_RELEASE_REGISTRY_USERNAME:?AURA_RELEASE_REGISTRY_USERNAME is required}"
: "${AURA_RELEASE_REGISTRY_PASSWORD_FILE:?AURA_RELEASE_REGISTRY_PASSWORD_FILE is required}"
[[ -f "$AURA_RELEASE_REGISTRY_PASSWORD_FILE" && ! -L "$AURA_RELEASE_REGISTRY_PASSWORD_FILE" \
  && -r "$AURA_RELEASE_REGISTRY_PASSWORD_FILE" ]] \
  || fatal 'release registry password file must be a readable regular non-symlink file'

for command_name in curl docker git node openssl pnpm python3 sha256sum tar; do need "$command_name"; done
[[ "$(uname -s)" == Linux ]] || fatal 'release images must be built on the admitted Linux CI host'
[[ "$(uname -m)" == x86_64 ]] || fatal 'release image builder must be x86_64'
[[ "${AURA_OCI_BUILDER:-docker}" == docker ]] || fatal 'AURA_OCI_BUILDER must be docker; local container fallbacks are prohibited'
MUTATION="${AURA_RELEASE_MUTATION:-}"
case "$MUTATION" in
  ''|locked-plugin-byte) ;;
  *) fatal "unsupported release mutation: $MUTATION" ;;
esac

CORE_ROOT="$(cd "$AURA_CORE_PROJECT_ROOT" && pwd)"
PRODUCT_ROOT="$(cd "$AURA_PRODUCT_PROJECT_ROOT" && pwd)"
LIFECYCLE_SOURCE="$PRODUCT_ROOT/$AURA_PRODUCT_LIFECYCLE"
for repo in "$CORE_ROOT" "$PRODUCT_ROOT"; do
  [[ -z "$(git -C "$repo" status --porcelain --untracked-files=all)" ]] || fatal "CI checkout is dirty: $repo"
done
CORE_SHA="$(git -C "$CORE_ROOT" rev-parse HEAD)"
PRODUCT_SHA="$(git -C "$PRODUCT_ROOT" rev-parse HEAD)"
[[ "$CORE_SHA" =~ ^[0-9a-f]{40}$ && "$PRODUCT_SHA" =~ ^[0-9a-f]{40}$ ]] || fatal 'checkout HEAD is not immutable'

FIXTURE_REL="${AURA_PRODUCT_RELEASE_FIXTURE:-}"
FIXTURE_CONTAINER_ROOT=''
FIXTURE_DIGEST=''
if [[ -n "$FIXTURE_REL" ]]; then
  [[ "$FIXTURE_REL" != /* ]] || fatal 'release acceptance fixture must be product-repository relative'
  [[ "$FIXTURE_REL" != */ ]] || fatal 'release acceptance fixture must not have a trailing slash'
  [[ "$FIXTURE_REL" =~ ^[A-Za-z0-9._/-]+$ ]] \
    || fatal 'release acceptance fixture contains unsupported path characters'
  IFS='/' read -r -a fixture_parts <<<"$FIXTURE_REL"
  for fixture_part in "${fixture_parts[@]}"; do
    [[ -n "$fixture_part" && "$fixture_part" != . && "$fixture_part" != .. ]] \
      || fatal 'release acceptance fixture contains an unsafe path component'
  done
  [[ "$(git -C "$PRODUCT_ROOT" cat-file -t "$PRODUCT_SHA:$FIXTURE_REL" 2>/dev/null || true)" == tree ]] \
    || fatal 'release acceptance fixture must be a tracked directory at the exact product commit'
  FIXTURE_DIGEST="sha256:$(git -C "$PRODUCT_ROOT" archive "$PRODUCT_SHA" "$FIXTURE_REL" | sha256sum | awk '{print $1}')"
  FIXTURE_CONTAINER_ROOT="/tmp/aura-release-fixtures/$FIXTURE_REL"
fi

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
BUILD_PG_CONTAINER="$PROJECT-build-pg"
APP_CONTAINER="$PROJECT-app"
IMAGE_REF=''
IMAGE_ID=''
REGISTRY_IMAGE=''
REGISTRY_DIGEST_REF=''
DOCKER_CONFIG_ROOT="$WORK_ROOT/docker-config"
PG_IMAGE="${AURA_CI_PGVECTOR_IMAGE:-pgvector/pgvector:pg17}"
FLYWAY_IMAGE="${AURA_CI_FLYWAY_IMAGE:-flyway/flyway:12.8.1}"

cleanup() {
  local status=$?
  if [[ -x "$PRODUCT_RELEASE/$AURA_PRODUCT_LIFECYCLE" ]]; then
    AURA_APP_ARTIFACT_ROOT="$PRODUCT_RELEASE" AURA_STATE_ROOT="$STATE_ROOT" \
      "$PRODUCT_RELEASE/$AURA_PRODUCT_LIFECYCLE" stop >/dev/null 2>&1 || true
  fi
  docker rm -f "$APP_CONTAINER" "$PG_CONTAINER" "$BUILD_PG_CONTAINER" >/dev/null 2>&1 || true
  docker network rm "$NETWORK" >/dev/null 2>&1 || true
  [[ -z "$REGISTRY_IMAGE" ]] || docker image rm "$REGISTRY_IMAGE" >/dev/null 2>&1 || true
  [[ -z "$REGISTRY_DIGEST_REF" ]] || docker image rm "$REGISTRY_DIGEST_REF" >/dev/null 2>&1 || true
  [[ -z "$IMAGE_REF" ]] || docker image rm "$IMAGE_REF" >/dev/null 2>&1 || true
  [[ -z "$IMAGE_ID" ]] || docker image rm "$IMAGE_ID" >/dev/null 2>&1 || true
  [[ ! -d "$DOCKER_CONFIG_ROOT" ]] || find "$DOCKER_CONFIG_ROOT" -depth -delete
  if [[ "$status" -eq 0 && -d "$WORK_ROOT" ]]; then find "$WORK_ROOT" -depth -delete; fi
  exit "$status"
}
trap cleanup EXIT INT TERM

info "building AuraBoot artifacts core=$CORE_SHA"
CI=1 pnpm --dir "$CORE_ROOT" install --frozen-lockfile --ignore-scripts \
  >"$ARTIFACTS/logs/core-pnpm-install.log" 2>&1 \
  || fatal 'AuraBoot artifact build dependencies unavailable'
AURA_OCI_BUILDER=docker node "$CORE_ROOT/scripts/application/stage-core-only-artifacts.mjs" \
  --repo-root "$CORE_ROOT" --output "$CORE_RELEASE" >"$ARTIFACTS/logs/core-artifacts.log" 2>&1 \
  || fail 'AuraBoot artifact build failed; see logs/core-artifacts.log'

info "building $AURA_PRODUCT_ID artifacts product=$PRODUCT_SHA"
CI=1 pnpm --dir "$PRODUCT_ROOT" install --frozen-lockfile --ignore-scripts \
  >"$ARTIFACTS/logs/product-pnpm-install.log" 2>&1 \
  || fatal 'product artifact build dependencies unavailable'
docker network create "$NETWORK" >/dev/null
docker run -d --name "$BUILD_PG_CONTAINER" --network "$NETWORK" -p 127.0.0.1::5432 \
  -e POSTGRES_USER=auraboot -e POSTGRES_PASSWORD=auraboot_ci -e POSTGRES_DB=aura_product_build_ci \
  "$PG_IMAGE" >/dev/null || fatal 'product build PostgreSQL container failed to start'
for attempt in $(seq 1 60); do
  docker exec "$BUILD_PG_CONTAINER" pg_isready -U auraboot -d aura_product_build_ci >/dev/null 2>&1 && break
  [[ "$attempt" -lt 60 ]] || fatal 'product build PostgreSQL never became ready'
  sleep 1
done
BUILD_PG_PORT="$(docker port "$BUILD_PG_CONTAINER" 5432/tcp | tail -1)"; BUILD_PG_PORT="${BUILD_PG_PORT##*:}"
docker run --rm --network "$NETWORK" \
  -v "$CORE_RELEASE/migrations/core":/flyway/core:ro \
  -v "$PRODUCT_ROOT/migrations/$AURA_PRODUCT_MIGRATION_OWNER":/flyway/product:ro \
  "$FLYWAY_IMAGE" -url="jdbc:postgresql://$BUILD_PG_CONTAINER:5432/aura_product_build_ci" \
  -user=auraboot -password=auraboot_ci \
  -locations=filesystem:/flyway/core,filesystem:/flyway/product \
  -table=ab_flyway_schema_history -baselineOnMigrate=false -validateMigrationNaming=true -cleanDisabled=true migrate \
  >"$ARTIFACTS/logs/product-test-flyway.log" 2>&1 || fail 'product test database migration failed'
SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:$BUILD_PG_PORT/aura_product_build_ci" \
SPRING_DATASOURCE_USERNAME=auraboot SPRING_DATASOURCE_PASSWORD=auraboot_ci \
AURA_OCI_BUILDER=docker node "$PRODUCT_ROOT/scripts/build-application.mjs" \
  --platform-artifacts "$CORE_RELEASE" --output "$PRODUCT_RELEASE" \
  >"$ARTIFACTS/logs/product-artifacts.log" 2>&1 \
  || fail 'product artifact build failed; see logs/product-artifacts.log'
docker rm -f "$BUILD_PG_CONTAINER" >/dev/null

if [[ "$MUTATION" == locked-plugin-byte ]]; then
  MUTATION_TARGET="$(node -e "const l=require(process.argv[1]); const a=l.artifacts.find(x=>x.type==='plugin'); if(!a)process.exit(2); process.stdout.write(a.localPath)" "$PRODUCT_RELEASE/application.lock")" \
    || fatal 'controlled mutation could not resolve the locked plugin artifact'
  printf '\nAURA_CONTROLLED_RELEASE_MUTATION\n' >>"$PRODUCT_RELEASE/$MUTATION_TARGET"
  if node "$PRODUCT_RELEASE/bin/application/application-artifact-verifier.mjs" \
      --lock "$PRODUCT_RELEASE/application.lock" --artifact-root "$PRODUCT_RELEASE" \
      >"$ARTIFACTS/logs/mutation-verifier.log" 2>&1; then
    fatal 'controlled locked-plugin mutation was not rejected'
  fi
  grep -q 'checksum mismatch' "$ARTIFACTS/logs/mutation-verifier.log" \
    || fatal 'controlled mutation failed for a reason other than checksum mismatch'
  node - "$ARTIFACTS/mutation-receipt.json" "$AURA_PRODUCT_ID" "$CORE_SHA" "$PRODUCT_SHA" "$MUTATION_TARGET" "$ARTIFACTS" <<'NODE'
const [path, product, coreCommit, productCommit, target, evidenceRoot] = process.argv.slice(2);
const receipt = { schemaVersion: 1, status: 'EXPECTED_RED', mutation: 'locked-plugin-byte',
  detectedBy: 'application-artifact-verifier', reason: 'checksum mismatch', product,
  coreCommit, productCommit, target, evidenceRoot, verifierLog: 'logs/mutation-verifier.log',
  releaseImagePushed: false, finishedAt: new Date().toISOString() };
require('node:fs').writeFileSync(path, `${JSON.stringify(receipt, null, 2)}\n`);
NODE
  fail 'controlled locked-plugin mutation was correctly rejected (expected red)'
fi

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
  "test -f /opt/auraboot/runtime/application.jar && test -f /opt/auraboot/application.lock && test -f /opt/auraboot/artifact-catalog.json && test -x /opt/auraboot/bin/$AURA_PRODUCT_ID-env.sh && test -f /opt/auraboot/bin/application/application-artifact-verifier.mjs && test -d /opt/auraboot/plugins && test -d /opt/auraboot/config/core-meta && test -d /opt/auraboot/config/$AURA_PRODUCT_MIGRATION_OWNER && test -d /opt/auraboot/migrations/core && test -d /opt/auraboot/migrations/$AURA_PRODUCT_MIGRATION_OWNER && test -d /opt/auraboot/web" \
  >"$ARTIFACTS/logs/payload-check.log" 2>&1 || fail 'release image payload is incomplete'

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
COMMON_ENV=(AURA_APP_ARTIFACT_ROOT="$PRODUCT_RELEASE" AURA_SERVER_ARTIFACT_ROOT=/opt/auraboot AURA_STATE_ROOT="$STATE_ROOT" AURA_BACKEND_PORT="$APP_PORT" AURA_WEB_PORT="$WEB_PORT" PGHOST=127.0.0.1 PGPORT="$PG_PORT" PGDATABASE=aura_product_ci PGUSER=auraboot PGPASSWORD=auraboot_ci ADMIN_EMAIL=admin@auraboot.local ADMIN_PASSWORD="$ADMIN_PASSWORD" SESSION_SECRET="$SESSION_SECRET" JWT_SECRET="$JWT_SECRET" PUBLIC_URL="http://127.0.0.1:$WEB_PORT")
env "${COMMON_ENV[@]}" "$PRODUCT_RELEASE/$AURA_PRODUCT_LIFECYCLE" init-core >"$ARTIFACTS/logs/init-core.log" 2>&1 || fail 'explicit core initialization failed'
env "${COMMON_ENV[@]}" "$PRODUCT_RELEASE/$AURA_PRODUCT_LIFECYCLE" publish >"$ARTIFACTS/logs/publish.log" 2>&1 || fail 'explicit product publish failed'
if [[ -n "$FIXTURE_REL" ]]; then
  info "injecting exact-commit acceptance fixture $FIXTURE_REL ($FIXTURE_DIGEST)"
  docker exec "$APP_CONTAINER" mkdir -p /tmp/aura-release-fixtures
  git -C "$PRODUCT_ROOT" archive "$PRODUCT_SHA" "$FIXTURE_REL" \
    | docker cp - "$APP_CONTAINER:/tmp/aura-release-fixtures" \
    || fail 'release acceptance fixture injection failed'
  env "${COMMON_ENV[@]}" AURA_RELEASE_FIXTURE_ROOT="$FIXTURE_CONTAINER_ROOT" \
    "$PRODUCT_RELEASE/$AURA_PRODUCT_LIFECYCLE" publish-fixture \
    >"$ARTIFACTS/logs/publish-fixture.log" 2>&1 \
    || fail 'explicit release acceptance fixture publish failed'
fi
mkdir -p "$STATE_ROOT"; docker logs "$APP_CONTAINER" >"$STATE_ROOT/runtime.log" 2>&1
env "${COMMON_ENV[@]}" "$PRODUCT_RELEASE/$AURA_PRODUCT_LIFECYCLE" start-web >"$ARTIFACTS/logs/web.log" 2>&1 || fail 'release Web BFF failed to start'
env "${COMMON_ENV[@]}" "$PRODUCT_RELEASE/$AURA_PRODUCT_LIFECYCLE" verify >"$ARTIFACTS/logs/verify.log" 2>&1 || fail 'artifact identity verification failed'

pnpm --dir "$PRODUCT_ROOT" install --frozen-lockfile --ignore-scripts >"$ARTIFACTS/logs/pnpm-install.log" 2>&1 || fatal 'product test dependencies unavailable'
env "${COMMON_ENV[@]}" PLAYWRIGHT_BASE_URL="http://127.0.0.1:$WEB_PORT" PW_SKIP_WEBSERVER=1 \
  PW_ARTIFACT_DIR="$ARTIFACTS/e2e/artifacts" PW_RESULTS_JSON="$ARTIFACTS/e2e/results.json" \
  pnpm --dir "$PRODUCT_ROOT" exec playwright test --config playwright.release.config.ts \
  >"$ARTIFACTS/logs/playwright.log" 2>&1 || fail 'release-image browser journey failed'
node "$CORE_ROOT/scripts/application/create-release-screenshot-manifest.mjs" \
  --root "$ARTIFACTS/e2e/artifacts" --output "$ARTIFACTS/e2e/screenshot-manifest.json" \
  --required "$AURA_RELEASE_SCREENSHOT_IDS" --product "$AURA_PRODUCT_ID" \
  --core-commit "$CORE_SHA" --product-commit "$PRODUCT_SHA" --image-digest "$IMAGE_ID" \
  >"$ARTIFACTS/logs/screenshot-manifest.log" 2>&1 \
  || fail 'required release screenshots are incomplete or invalid'

REGISTRY_HOST="${AURA_RELEASE_REGISTRY%%/*}"
REGISTRY_REPOSITORY="${AURA_RELEASE_REGISTRY%/}/$AURA_PRODUCT_ID"
REGISTRY_IMAGE="$REGISTRY_REPOSITORY:${LAYOUT_DIGEST#sha256:}"
install -d -m 0700 "$DOCKER_CONFIG_ROOT"
docker --config "$DOCKER_CONFIG_ROOT" login "$REGISTRY_HOST" \
  --username "$AURA_RELEASE_REGISTRY_USERNAME" --password-stdin \
  <"$AURA_RELEASE_REGISTRY_PASSWORD_FILE" >"$ARTIFACTS/logs/docker-login.log" 2>&1 \
  || fatal 'release registry login failed'
docker tag "$IMAGE_REF" "$REGISTRY_IMAGE"
docker --config "$DOCKER_CONFIG_ROOT" push "$REGISTRY_IMAGE" \
  >"$ARTIFACTS/logs/docker-push.log" 2>&1 || fatal 'validated release image push failed'
PUSH_DIGEST="$(sed -n 's/.*digest: \(sha256:[0-9a-f]\{64\}\).*/\1/p' "$ARTIFACTS/logs/docker-push.log" | tail -1)"
[[ "$PUSH_DIGEST" == "$LAYOUT_DIGEST" ]] \
  || fail "registry digest mismatch: layout=$LAYOUT_DIGEST pushed=${PUSH_DIGEST:-missing}"
REGISTRY_DIGEST_REF="$REGISTRY_REPOSITORY@$PUSH_DIGEST"
docker image rm "$REGISTRY_IMAGE" >/dev/null 2>&1 || true
docker --config "$DOCKER_CONFIG_ROOT" pull "$REGISTRY_DIGEST_REF" \
  >"$ARTIFACTS/logs/docker-pull.log" 2>&1 || fatal 'release image digest pull failed'
PULLED_IMAGE_ID="$(docker image inspect "$REGISTRY_DIGEST_REF" --format '{{.Id}}')"
[[ "$PULLED_IMAGE_ID" == "$IMAGE_ID" ]] \
  || fail "registry pull changed image identity: loaded=$IMAGE_ID pulled=$PULLED_IMAGE_ID"
docker run --rm --entrypoint sh "$REGISTRY_DIGEST_REF" -ec \
  'test -f /opt/auraboot/application.lock && test -f /opt/auraboot/artifact-catalog.json' \
  >"$ARTIFACTS/logs/registry-payload-check.log" 2>&1 \
  || fail 'registry-pulled image payload is incomplete'
docker --config "$DOCKER_CONFIG_ROOT" logout "$REGISTRY_HOST" >/dev/null 2>&1 || true
find "$DOCKER_CONFIG_ROOT" -depth -delete

cp "$CORE_RELEASE/release-receipt.json" "$ARTIFACTS/core-build-receipt.json"
cp "$PRODUCT_RELEASE/release-receipt.json" "$ARTIFACTS/product-build-receipt.json"
python3 - "$ARTIFACTS/release-image-receipt.json" "$AURA_PRODUCT_ID" "$CORE_SHA" "$PRODUCT_SHA" "$LOCK_IDENTITY" "$LAYOUT_DIGEST" "$IMAGE_ID" "$REGISTRY_DIGEST_REF" "$PULLED_IMAGE_ID" "$AURA_CI_BUILDER_ID" "$AURA_CI_JOB_ID" "$FIXTURE_REL" "$FIXTURE_DIGEST" "$ARTIFACTS" "$APP_CONTAINER" "$PG_CONTAINER" "$WEB_PORT" <<'PY'
import datetime, json, sys
path, product, core, source, lock, layout, image_id, registry_image, pulled_id, builder, job, fixture_path, fixture_digest, evidence_root, app_container, pg_container, web_port = sys.argv[1:]
receipt = {"schemaVersion": 1, "status": "PASS", "product": product,
           "coreCommit": core, "productCommit": source, "lockIdentity": lock,
           "ociLayoutDigest": layout, "loadedImageId": image_id, "builder": builder,
           "registryImage": registry_image, "registryPulledImageId": pulled_id,
           "job": job, "freshDatabase": "PASS", "payload": "PASS",
           "readiness": "PASS", "browserJourney": "PASS",
           "database": {"engine": "PostgreSQL", "name": "aura_product_ci",
                        "container": pg_container, "fresh": True},
           "runtime": {"applicationContainer": app_container,
                       "webBaseUrl": f"http://127.0.0.1:{web_port}",
                       "lifecycle": "ephemeral-ci"},
           "evidenceRoot": evidence_root,
           "browserResults": "e2e/results.json",
           "screenshotManifest": "e2e/screenshot-manifest.json",
           "logsRoot": "logs",
           "finishedAt": datetime.datetime.now(datetime.timezone.utc).isoformat()}
if fixture_path:
    receipt["acceptanceFixture"] = {"sourcePath": fixture_path,
                                    "sourceCommit": source,
                                    "archiveDigest": fixture_digest,
                                    "includedInReleaseImage": False}
json.dump(receipt, open(path, "w"), indent=2, sort_keys=True)
PY
info "$AURA_PRODUCT_ID release image VERIFIED on Linux CI Docker (image=$IMAGE_ID)"
