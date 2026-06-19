#!/usr/bin/env bash
#
# oss-golden-stack.sh — one-click host-first golden stack for OSS auraboot.
#
# Brings up a fully isolated host-first stack (zero docker) ready to run real-browser
# golden specs against the code in THIS checkout/worktree, collapsing the ~6 manual
# failure points documented in the enterprise agent-rules e2e-playwright runbook
#   (docs/agent-rules/engineering-gotchas/e2e-playwright.md)
#   §"OSS auraboot host-first 视觉 golden 全栈 bring-up runbook".
#
# It does NOT reset the shared host and NEVER FORCE_HOSTs — it always uses an isolated
# dev.sh runtime (slot-offset DB/redis/kafka), so it is safe to run alongside other
# worktrees / concurrent sessions. It does a *minimal* bootstrap (admin + tenant), which
# is enough for designer / page golden that don't need the showcase seed. For golden that
# need the full showcase data, run scripts/oss-reset-and-init.sh separately (dormancy-guarded).
#
# Usage:
#   ./scripts/oss-golden-stack.sh up   <name> [--slot N] [--no-frontend] [--ttl 6h]
#   ./scripts/oss-golden-stack.sh env  <name>          # print the Playwright env exports
#   ./scripts/oss-golden-stack.sh status <name>
#   ./scripts/oss-golden-stack.sh down <name>          # stop backend+frontend (keep runtime/DB)
#   ./scripts/oss-golden-stack.sh destroy <name>       # down + infra cleanup + runtime destroy
#
# Then run golden specs (the `up` banner prints this, `env` re-prints it):
#   cd web-admin && eval "$(../scripts/oss-golden-stack.sh env <name>)" \
#     && npx playwright test -c playwright.gt5.config.ts tests/e2e/bpm-designer/<spec>.spec.ts
#
set -euo pipefail

# ---- locate this checkout + the workspace root (dir holding dev.sh) ------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"            # the auraboot checkout this script lives in
WORKSPACE="$REPO_ROOT"
while [ "$WORKSPACE" != "/" ] && [ ! -f "$WORKSPACE/dev.sh" ]; do WORKSPACE="$(dirname "$WORKSPACE")"; done
[ -f "$WORKSPACE/dev.sh" ] || { echo "FATAL: cannot find workspace dev.sh above $REPO_ROOT"; exit 1; }
CANONICAL="$WORKSPACE/auraboot"                      # canonical OSS checkout (for gradle wrapper / node_modules seed)
DEV="$WORKSPACE/dev.sh"

ADMIN_EMAIL="admin@auraboot.com"
ADMIN_PASSWORD="Test2026x"

log() { printf '\033[36m[golden-stack]\033[0m %s\n' "$*"; }
die() { printf '\033[31m[golden-stack] FATAL:\033[0m %s\n' "$*" >&2; exit 1; }

state_dir() { echo "$WORKSPACE/.workspace/golden/$1"; }

# Read a key from the runtime env file.
runtime_env() {
  local name="$1" key="$2" f="$WORKSPACE/.workspace/env/$1.env"
  [ -f "$f" ] || die "runtime env not found: $f (run 'up' first / check the name)"
  grep -E "^${key}=" "$f" | head -1 | cut -d= -f2-
}

poll_http() {  # poll_http <url> <pattern> <timeout-s> <label>
  local url="$1" pat="$2" timeout="$3" label="$4" i=0
  while [ "$i" -lt "$timeout" ]; do
    if curl -s -m 3 "$url" 2>/dev/null | grep -q "$pat"; then return 0; fi
    i=$((i+3)); sleep 3
  done
  return 1
}

# ---- up ------------------------------------------------------------------------------
cmd_up() {
  local name="$1"; shift
  local slot="" ttl="6h" frontend=1
  while [ $# -gt 0 ]; do case "$1" in
    --slot) slot="$2"; shift 2;;
    --ttl) ttl="$2"; shift 2;;
    --no-frontend) frontend=0; shift;;
    *) die "unknown arg: $1";;
  esac; done
  [ -n "$slot" ] || die "--slot N is required for 'up' (pick a free slot: $DEV runtime list)"

  local sd; sd="$(state_dir "$name")"; mkdir -p "$sd"

  log "1/8 allocate runtime '$name' (slot $slot) + ensure infra"
  "$DEV" runtime allocate auraboot "$name" --slot "$slot" --purpose "OSS host-first golden stack" --ttl "$ttl" >/dev/null
  "$DEV" infra ensure "$name" --yes >/dev/null

  local server_port vite_port bff_port pg_db redis_db
  server_port="$(runtime_env "$name" SERVER_PORT)"
  vite_port="$(runtime_env "$name" VITE_PORT)"
  bff_port="$(runtime_env "$name" BFF_PORT)"
  pg_db="$(runtime_env "$name" POSTGRES_DB)"
  redis_db="$(runtime_env "$name" REDIS_DATABASE)"
  log "    backend=$server_port vite=$vite_port bff=$bff_port db=$pg_db redis-db=$redis_db"

  log "2/8 apply schema to $pg_db"
  PGPASSWORD=auraboot psql -h 127.0.0.1 -p 5432 -U auraboot -d "$pg_db" \
    -f "$REPO_ROOT/platform/src/main/resources/database/schema.sql" >/dev/null 2>&1 \
    || die "schema apply failed"

  log "3/8 seed gradle wrapper jar (fresh-worktree gotcha)"
  if [ ! -f "$REPO_ROOT/platform/gradle/wrapper/gradle-wrapper.jar" ]; then
    mkdir -p "$REPO_ROOT/platform/gradle/wrapper"
    cp "$CANONICAL/platform/gradle/wrapper/gradle-wrapper.jar" "$REPO_ROOT/platform/gradle/wrapper/" \
      || die "cannot seed gradle-wrapper.jar from $CANONICAL"
    cp "$CANONICAL/platform/gradlew" "$REPO_ROOT/platform/gradlew" 2>/dev/null && chmod +x "$REPO_ROOT/platform/gradlew" || true
  fi

  log "4/8 build bootJar (default ~/.gradle for plugin/mirror resolution; --no-daemon)"
  ( cd "$REPO_ROOT/platform" && ./gradlew --no-daemon :bootJar -x test --console=plain ) >"$sd/bootjar.log" 2>&1 \
    || die "bootJar build failed — see $sd/bootjar.log"
  local jar; jar="$(ls "$REPO_ROOT"/platform/build/libs/*-boot.jar 2>/dev/null | head -1)"
  [ -n "$jar" ] || die "boot jar not found after build"

  log "5/8 start backend (java -jar) on $server_port"
  ( SERVER_PORT="$server_port" \
    SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:5432/${pg_db}?charSet=UTF8" \
    SPRING_DATASOURCE_USERNAME=auraboot SPRING_DATASOURCE_PASSWORD=auraboot \
    SPRING_DATA_REDIS_HOST=127.0.0.1 SPRING_DATA_REDIS_PORT=6379 SPRING_DATA_REDIS_DATABASE="$redis_db" \
    SPRING_KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:9092 \
    AURA_BUILTIN_PLUGINS_DIR="$REPO_ROOT/plugins" \
    nohup java -jar "$jar" >"$sd/backend.log" 2>&1 & echo $! >"$sd/backend.pid" )
  echo "$server_port $vite_port $bff_port" >"$sd/ports"
  poll_http "http://127.0.0.1:$server_port/actuator/health" '"status":"UP"' 150 backend \
    || die "backend did not become healthy — see $sd/backend.log"
  log "    backend UP (pid $(cat "$sd/backend.pid"))"

  log "6/8 bootstrap (minimal admin + tenant; idempotent)"
  if ! curl -s -m 10 "http://127.0.0.1:$server_port/api/bootstrap/status" 2>/dev/null | grep -q '"initialized":true'; then
    curl -s -m 60 -X POST "http://127.0.0.1:$server_port/api/bootstrap/setup" -H 'Content-Type: application/json' \
      -d "{\"companyName\":\"AuraBoot Dev\",\"adminEmail\":\"$ADMIN_EMAIL\",\"adminPassword\":\"$ADMIN_PASSWORD\",\"adminDisplayName\":\"Admin\",\"systemMode\":\"single\",\"seedDemoData\":false}" \
      | grep -q '"success":true' || die "bootstrap failed"
  fi
  curl -s -m 15 -X POST "http://127.0.0.1:$server_port/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" | grep -q '"jwt"' \
    || die "login round-trip failed after bootstrap"
  log "    bootstrap OK ($ADMIN_EMAIL / $ADMIN_PASSWORD)"

  if [ "$frontend" -eq 1 ]; then
    log "7/8 frontend: symlink node_modules (if missing) + start Vite+BFF"
    [ -e "$REPO_ROOT/web-admin/node_modules" ] || ln -sfn "$CANONICAL/web-admin/node_modules" "$REPO_ROOT/web-admin/node_modules"
    ( cd "$REPO_ROOT/web-admin" && \
      VITE_PORT="$vite_port" BFF_PORT="$bff_port" SPRING_BOOT_URL="http://127.0.0.1:$server_port" NODE_ENV=development \
      nohup pnpm dev:full >"$sd/frontend.log" 2>&1 & echo $! >"$sd/frontend.pid" )
    poll_http "http://127.0.0.1:$vite_port/" '' 120 vite || true   # 302/200 → up; pattern empty = any response
    # explicit reachability check (302 to /login is fine)
    local code; code="$(curl -s -m 3 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$vite_port/" 2>/dev/null || echo 000)"
    [ "$code" != "000" ] || die "Vite did not come up on $vite_port — see $sd/frontend.log"
    log "    frontend UP (supervisor pid $(cat "$sd/frontend.pid"), vite http=$code)"
  else
    log "7/8 frontend: skipped (--no-frontend)"
  fi

  log "8/8 ready ✓"
  echo
  cmd_env "$name"
}

# ---- env -----------------------------------------------------------------------------
cmd_env() {
  local name="$1" sd; sd="$(state_dir "$name")"
  [ -f "$sd/ports" ] || die "no running stack for '$name' (run 'up' first)"
  read -r server_port vite_port bff_port <"$sd/ports"
  cat <<EOF
# Playwright env contract for golden specs against '$name' (run from web-admin/):
export PLAYWRIGHT_BASE_URL=http://127.0.0.1:$vite_port
export BACKEND_URL=http://127.0.0.1:$server_port
export BE_PORT=$server_port
export BFF_PORT=$bff_port
export PW_SKIP_WEBSERVER=1
export NO_PROXY=localhost,127.0.0.1
# example: cd web-admin && eval "\$(../scripts/oss-golden-stack.sh env $name)" \\
#   && npx playwright test -c playwright.gt5.config.ts tests/e2e/bpm-designer/designer-property-edit.spec.ts
# NOTE: the FIRST golden run after 'up' may flake on a heavy route (cold Vite optimizeDeps —
#       e.g. waitForFunction/route timeout); just re-run, or pre-warm: curl -s \$PLAYWRIGHT_BASE_URL/<route>.
EOF
}

# ---- status --------------------------------------------------------------------------
cmd_status() {
  local name="$1" sd; sd="$(state_dir "$name")"
  [ -f "$sd/ports" ] || { echo "no stack for '$name'"; return 1; }
  read -r server_port vite_port bff_port <"$sd/ports"
  local be vi
  be="$(curl -s -m 3 "http://127.0.0.1:$server_port/actuator/health" 2>/dev/null | grep -o '"status":"UP"' || echo DOWN)"
  vi="$(curl -s -m 3 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$vite_port/" 2>/dev/null || echo 000)"
  echo "backend($server_port)=$be  vite($vite_port)=$vi  bff=$bff_port"
}

# ---- down (stop processes, keep runtime/DB) ------------------------------------------
cmd_down() {
  local name="$1" sd; sd="$(state_dir "$name")"
  [ -d "$sd" ] || { echo "no stack for '$name'"; return 0; }
  # kill recorded supervisor + backend (frontend tree first: pnpm dev:full → concurrently → web+bff)
  if [ -f "$sd/frontend.pid" ]; then local fp; fp="$(cat "$sd/frontend.pid")"; pkill -P "$fp" 2>/dev/null || true; kill "$fp" 2>/dev/null || true; fi
  if [ -f "$sd/backend.pid" ]; then kill "$(cat "$sd/backend.pid")" 2>/dev/null || true; fi
  sleep 2
  # kill any straggler bound to THIS runtime's exact ports only (never a shared/other-slot port)
  if [ -f "$sd/ports" ]; then
    read -r server_port vite_port bff_port <"$sd/ports"
    for p in "$server_port" "$vite_port" "$bff_port"; do
      local pid; pid="$(lsof -nP -iTCP:"$p" -sTCP:LISTEN -t 2>/dev/null || true)"
      [ -n "$pid" ] && kill -9 $pid 2>/dev/null && log "killed straggler on $p" || true
    done
  fi
  rm -f "$sd/backend.pid" "$sd/frontend.pid"
  log "stopped '$name' processes (runtime/DB kept; 'destroy' to remove)"
}

# ---- destroy (down + infra cleanup + runtime destroy) --------------------------------
cmd_destroy() {
  local name="$1"
  cmd_down "$name" || true
  log "infra cleanup + runtime destroy '$name'"
  "$DEV" infra cleanup "$name" --yes >/dev/null 2>&1 || true
  "$DEV" runtime destroy "$name" --yes >/dev/null 2>&1 || true
  rm -rf "$(state_dir "$name")"
  # remove the node_modules symlink we created (gitignored, but keep the worktree clean)
  [ -L "$REPO_ROOT/web-admin/node_modules" ] && rm -f "$REPO_ROOT/web-admin/node_modules" || true
  log "destroyed '$name' ✓"
}

# ---- dispatch ------------------------------------------------------------------------
[ $# -ge 2 ] || { sed -n '2,30p' "${BASH_SOURCE[0]}"; exit 1; }
sub="$1"; name="$2"; shift 2
case "$sub" in
  up) cmd_up "$name" "$@";;
  env) cmd_env "$name";;
  status) cmd_status "$name";;
  down) cmd_down "$name";;
  destroy) cmd_destroy "$name";;
  *) die "unknown subcommand: $sub (up|env|status|down|destroy)";;
esac
