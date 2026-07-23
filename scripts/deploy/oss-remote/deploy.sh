#!/usr/bin/env bash
# =============================================================================
# AuraBoot OSS — remote deploy via pre-built images ("image mechanism").
#
# Builds the OSS backend + frontend images OFF the target host, ships them with
# `docker save | ssh docker load`, brings up a single-instance stack, bootstraps
# the admin + plugins, seeds showcase demo data, and verifies the live URL.
#
# Designed for hosts that can't pull the images from a registry (air-gapped /
# GHCR-blocked / CN networks) — nothing is compiled on the target host.
#
#   ./deploy.sh                       # full flow using env below
#   SKIP_BUILD=1 ./deploy.sh          # reuse already-built local images
#   SKIP_SEED=1 ./deploy.sh           # deploy without demo data
#   STEP=verify ./deploy.sh           # run a single phase (build|ship|up|bootstrap|seed|verify)
#
# Required env:
#   HOST=root@1.2.3.4                 # ssh target
#   PUBLIC_URL=https://example.com    # public base URL users open
# Common env:
#   TAG=oss-YYYYmmdd-HHMM             # image tag (default: date-based)
#   MODE=direct|coexist               # direct: gateway owns host :80 (default)
#                                     # coexist: join EDGE_NETWORK behind an existing proxy
#   EDGE_NETWORK=<docker net>         # (coexist) external network of the fronting proxy
#   STOP_CONTAINERS="a b c"           # containers to `docker stop` before bringing OSS up
#   PUBLIC_HTTP_PORT=80               # (direct) host port for the gateway
#   ADMIN_EMAIL / ADMIN_PASSWORD      # default admin@auraboot.com / Test2026x
#   SEED_PHASES="data extended workflow ai supplement"
#   SHOWCASE_DEFAULT_DASHBOARD_CODE=crm_overview
#   APK_MIRROR=mirrors.aliyun.com  NPM_REGISTRY=https://registry.npmmirror.com
#   PLATFORM=linux/amd64              # target arch of the host
#
# Host prereqs: docker + docker compose, python3 + curl (for bootstrap).
# Build-host prereqs: docker buildx, JDK 21 + gradle (bundled wrapper), and
#   web-admin deps installed (`pnpm -C web-admin install`) if seeding.
# =============================================================================
set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SELF_DIR/../../.." && pwd)"      # repo root
WEB_ADMIN="$ROOT/web-admin"

# --- config ------------------------------------------------------------------
HOST="${HOST:?set HOST=user@ip}"
PUBLIC_URL="${PUBLIC_URL:?set PUBLIC_URL=https://...}"
TAG="${TAG:-oss-$(date +%Y%m%d-%H%M)}"
MODE="${MODE:-direct}"
REMOTE_DIR="${REMOTE_DIR:-/opt/auraboot-oss}"
ADMIN_EMAIL="${ADMIN_EMAIL:-admin@auraboot.com}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-Test2026x}"
COMPANY_NAME="${COMPANY_NAME:-AuraBoot}"
PLATFORM="${PLATFORM:-linux/amd64}"
LOCAL_ADMIN_PORT="${LOCAL_ADMIN_PORT:-18081}"
PUBLIC_HTTP_PORT="${PUBLIC_HTTP_PORT:-80}"
APK_MIRROR="${APK_MIRROR:-}"
NPM_REGISTRY="${NPM_REGISTRY:-https://registry.npmjs.org}"
SEED_PHASES="${SEED_PHASES:-data extended workflow ai supplement}"
SHOWCASE_DEFAULT_DASHBOARD_CODE="${SHOWCASE_DEFAULT_DASHBOARD_CODE:-crm_overview}"
STEP="${STEP:-all}"
SSH=(ssh -o BatchMode=yes "$HOST")
BE_IMG="auraboot-oss/backend:$TAG"; FE_IMG="auraboot-oss/frontend:$TAG"

log(){ printf '\033[1;34m[deploy]\033[0m %s\n' "$*"; }
run_remote(){ "${SSH[@]}" "$@"; }
want(){ [ "$STEP" = "all" ] || [ "$STEP" = "$1" ]; }

# --- build (off-host) --------------------------------------------------------
build(){
  [ "${SKIP_BUILD:-0}" = "1" ] && { log "SKIP_BUILD=1 — reusing $BE_IMG / $FE_IMG"; return; }
  log "build: backend bootJar (native, arch-independent)"
  ( cd "$ROOT/platform" && ./gradlew bootJar -x test --no-daemon )
  log "build: backend runtime image ($PLATFORM)"
  docker buildx build --platform "$PLATFORM" --load \
    --build-arg APK_MIRROR="$APK_MIRROR" \
    -f "$SELF_DIR/Dockerfile.backend-runtime" -t "$BE_IMG" "$ROOT/platform"

  log "build: frontend image ($PLATFORM, cross-compiled)"
  local fedf; fedf="$(mktemp)"
  if [ -n "$APK_MIRROR" ]; then
    awk -v m="RUN sed -i \"s|dl-cdn.alpinelinux.org|$APK_MIRROR|g\" /etc/apk/repositories 2>/dev/null || true" \
      '/^RUN apk add/ && !d {print m; d=1} {print}' "$WEB_ADMIN/Dockerfile" > "$fedf"
  else cp "$WEB_ADMIN/Dockerfile" "$fedf"; fi
  docker buildx build --platform "$PLATFORM" --load \
    --build-arg NPM_REGISTRY="$NPM_REGISTRY" \
    -f "$fedf" -t "$FE_IMG" "$ROOT"
  rm -f "$fedf"
  for i in "$BE_IMG" "$FE_IMG"; do
    local a; a="$(docker image inspect "$i" --format '{{.Os}}/{{.Architecture}}')"
    [ "$a" = "${PLATFORM}" ] || { echo "ERROR: $i is $a, expected $PLATFORM" >&2; exit 1; }
  done
  log "build: OK — both images are $PLATFORM"
}

# --- ship images + config to the host ----------------------------------------
ship(){
  log "ship: prepare $REMOTE_DIR on $HOST"
  run_remote "mkdir -p '$REMOTE_DIR'"
  # config bundle
  # OSS community deploy has no Flyway; the DB is built at first-init from the
  # Flyway-generated snapshot (shipped as schema.sql, mounted into initdb.d).
  cp "$ROOT/platform/src/main/resources/db/snapshots/schema-current.sql" "$SELF_DIR/schema.sql"
  cp "$ROOT/scripts/quickstart.sh" "$SELF_DIR/quickstart.sh"
  scp -q "$SELF_DIR/docker-compose.remote.yml" "$SELF_DIR/override.$MODE.yml" \
        "$SELF_DIR/gateway.conf" "$SELF_DIR/schema.sql" "$SELF_DIR/quickstart.sh" \
        "$HOST:$REMOTE_DIR/"
  rm -f "$SELF_DIR/schema.sql" "$SELF_DIR/quickstart.sh"
  # plugins (builtin config plugins mounted read-only into the backend)
  log "ship: plugins/"
  tar --exclude='node_modules' --exclude='.git' -C "$ROOT" -czf - plugins \
    | run_remote "tar xzf - -C '$REMOTE_DIR'"
  # .env (generated once; kept if it already exists so re-deploys are stable)
  if run_remote "test -f '$REMOTE_DIR/.env'"; then
    log "ship: reusing existing $REMOTE_DIR/.env"
  else
    log "ship: generating $REMOTE_DIR/.env (0600)"
    run_remote "umask 077; cat > '$REMOTE_DIR/.env'" <<EOF
TAG=$TAG
POSTGRES_DB=aura_boot
POSTGRES_USER=auraboot
POSTGRES_PASSWORD=$(openssl rand -hex 16)
JWT_SECRET=$(openssl rand -hex 32)
SESSION_SECRET=$(openssl rand -hex 32)
PUBLIC_URL=$PUBLIC_URL
EDGE_NETWORK=${EDGE_NETWORK:-}
LOCAL_ADMIN_PORT=$LOCAL_ADMIN_PORT
PUBLIC_HTTP_PORT=$PUBLIC_HTTP_PORT
EOF
  fi
  # keep TAG current on re-deploy
  run_remote "sed -i 's|^TAG=.*|TAG=$TAG|' '$REMOTE_DIR/.env'"
  log "ship: images ($BE_IMG, $FE_IMG) via save|load — this can take a few minutes"
  docker save "$BE_IMG" | gzip -1 | run_remote "docker load"
  docker save "$FE_IMG" | gzip -1 | run_remote "docker load"
  log "ship: OK"
}

compose_cmd(){ echo "cd '$REMOTE_DIR' && docker compose --env-file .env -f docker-compose.remote.yml -f override.$MODE.yml"; }

# --- bring the stack up ------------------------------------------------------
up(){
  if [ -n "${STOP_CONTAINERS:-}" ]; then
    log "up: stopping coexisting containers: $STOP_CONTAINERS"
    run_remote "docker stop $STOP_CONTAINERS || true"
  fi
  log "up: docker compose up -d ($MODE mode)"
  run_remote "$(compose_cmd) up -d"
  run_remote "$(compose_cmd) ps"
}

# --- bootstrap admin + plugins -----------------------------------------------
bootstrap(){
  log "bootstrap: quickstart (admin + plugin import) via 127.0.0.1:$LOCAL_ADMIN_PORT"
  run_remote "cd '$REMOTE_DIR' && BACKEND_URL=http://127.0.0.1:$LOCAL_ADMIN_PORT PLUGINS_PATH=/app/plugins \
    ADMIN_EMAIL='$ADMIN_EMAIL' ADMIN_PASSWORD='$ADMIN_PASSWORD' COMPANY_NAME='$COMPANY_NAME' bash quickstart.sh"
}

# --- seed showcase demo data (from the build host, over HTTP) ----------------
seed(){
  [ "${SKIP_SEED:-0}" = "1" ] && { log "SKIP_SEED=1 — no demo data"; return; }
  local ss; ss="$(mktemp -d)/admin.json"
  log "seed: minting admin storageState for $PUBLIC_URL"
  SEED_BASE_URL="$PUBLIC_URL" ADMIN_EMAIL="$ADMIN_EMAIL" ADMIN_PASSWORD="$ADMIN_PASSWORD" \
    node "$SELF_DIR/gen-admin-storage.mjs" "$ss"
  log "seed: showcase sequence [$SEED_PHASES] + dashboard-default"
  ( cd "$WEB_ADMIN" && PLAYWRIGHT_BASE_URL="$PUBLIC_URL" PW_ADMIN_STORAGE_STATE="$ss" PW_SKIP_WEBSERVER=1 \
      SHOWCASE_DEFAULT_DASHBOARD_CODE="$SHOWCASE_DEFAULT_DASHBOARD_CODE" \
      node scripts/run-showcase-seed-sequence.mjs --config=playwright.seed.config.ts \
        --output-prefix="$(dirname "$ss")/seed" $SEED_PHASES dashboard-default )
  log "seed: workflow-demo leave balances (best-effort)"
  ( cd "$WEB_ADMIN" && PLAYWRIGHT_BASE_URL="$PUBLIC_URL" PW_ADMIN_STORAGE_STATE="$ss" \
      node scripts/seed-workflow-demo.mjs --base-url="$PUBLIC_URL" --storage-state="$ss" ) || \
      log "seed: workflow-demo leave requests skipped (BPM rule) — non-fatal"
  rm -rf "$(dirname "$ss")"
  log "seed: OK"
}

# --- verify the live URL -----------------------------------------------------
verify(){
  log "verify: on-host health"
  run_remote "curl -sf -o /dev/null -w 'local 127.0.0.1:$LOCAL_ADMIN_PORT/api/bootstrap/status -> %{http_code}\n' http://127.0.0.1:$LOCAL_ADMIN_PORT/api/bootstrap/status"
  log "verify: public URL"
  curl -sf -o /dev/null -w "$PUBLIC_URL/api/bootstrap/status -> %{http_code}\n" "$PUBLIC_URL/api/bootstrap/status" \
    || echo "  (public URL not reachable from here — check DNS / proxy / firewall)"
  log "DONE — $PUBLIC_URL  (admin: $ADMIN_EMAIL / $ADMIN_PASSWORD — change it)"
}

# --- run ---------------------------------------------------------------------
log "target=$HOST url=$PUBLIC_URL tag=$TAG mode=$MODE step=$STEP"
want build     && build
want ship      && ship
want up        && up
want bootstrap && bootstrap
want seed      && seed
want verify    && verify
