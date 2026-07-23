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
#   ./scripts/oss-golden-stack.sh up   <name> [--slot N] [--no-frontend] [--no-warm] [--fresh-db] [--ttl 6h] [--plugin-profile P|--plugin X]
#       --no-warm : keep the frontend but skip the setup/auth/pre-warm step — for goldens
#                   that self-provision accounts and run with --no-deps (no storageState).
#       --fresh-db: drop + recreate the slot's database before applying schema.sql. `up`
#                   otherwise refuses to run on a database that predates the current
#                   schema.sql (schema.sql is CREATE TABLE IF NOT EXISTS — it cannot
#                   back-fill columns into tables that already exist).
#   ./scripts/oss-golden-stack.sh import <name> [--plugin-profile P|--plugin X]
#   ./scripts/oss-golden-stack.sh warm <name>          # re-run setup→auth→pre-warm (up does this)
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
# Normal case: this checkout lives under the workspace, so dev.sh is an ancestor.
WORKSPACE="$REPO_ROOT"
while [ "$WORKSPACE" != "/" ] && [ ! -f "$WORKSPACE/dev.sh" ]; do WORKSPACE="$(dirname "$WORKSPACE")"; done
# Sibling-worktree case: `git worktree add` outside the workspace tree (e.g.
# /Users/.../auraboot-golden alongside /Users/.../auraboot) means dev.sh is NOT
# an ancestor. Fall back to the git main worktree (the canonical checkout): its
# parent holds dev.sh / the canonical `auraboot` checkout.
if [ ! -f "$WORKSPACE/dev.sh" ]; then
  main_wt="$(git -C "$REPO_ROOT" worktree list --porcelain 2>/dev/null | awk '/^worktree /{print $2; exit}')"
  if [ -n "$main_wt" ]; then
    cand="$(dirname "$main_wt")"
    [ -f "$cand/dev.sh" ] && WORKSPACE="$cand"
  fi
fi
[ -f "$WORKSPACE/dev.sh" ] || { echo "FATAL: cannot find workspace dev.sh above $REPO_ROOT (and no sibling main-worktree fallback)"; exit 1; }
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

web_admin_node_modules_seed() {
  local candidate
  for candidate in "$CANONICAL/web-admin/node_modules" "$REPO_ROOT/web-admin/node_modules"; do
    [ -d "$candidate" ] && { echo "$candidate"; return 0; }
  done

  while IFS= read -r candidate; do
    candidate="$candidate/web-admin/node_modules"
    [ -d "$candidate" ] && { echo "$candidate"; return 0; }
  done < <(git -C "$REPO_ROOT" worktree list --porcelain 2>/dev/null | awk '/^worktree /{print substr($0,10)}')

  return 1
}

poll_http() {  # poll_http <url> <pattern> <timeout-s> <label>
  local url="$1" pat="$2" timeout="$3" label="$4" i=0
  while [ "$i" -lt "$timeout" ]; do
    if curl --noproxy '*' -s -m 3 "$url" 2>/dev/null | grep -q "$pat"; then return 0; fi
    i=$((i+3)); sleep 3
  done
  return 1
}

# Poll until the URL returns ANY HTTP status (i.e. the listener accepts and
# responds) — used for Vite, where a 302 → /login has an empty body that a
# body-grep poll would never match (it would silently wait the full timeout).
poll_http_up() {  # poll_http_up <url> <timeout-s>
  local url="$1" timeout="$2" i=0 code
  while [ "$i" -lt "$timeout" ]; do
    code="$(curl --noproxy '*' -s -m 3 -o /dev/null -w '%{http_code}' "$url" 2>/dev/null || true)"
    case "$code" in ""|000) ;; *) return 0;; esac
    i=$((i+3)); sleep 3
  done
  return 1
}

spawn_detached() {  # spawn_detached <pid-file> <work-dir> <log-file> <cmd> [args...]
  local pid_file="$1" work_dir="$2" log_file="$3"; shift 3
  local py; py="$(command -v python3 2>/dev/null || true)"
  if [ -n "$py" ]; then
    "$py" - "$pid_file" "$work_dir" "$log_file" "$@" <<'PY'
import os
import sys

pid_file, work_dir, log_file, *cmd = sys.argv[1:]
pid = os.fork()
if pid:
    with open(pid_file, "w", encoding="utf-8") as fh:
        fh.write(f"{pid}\n")
    os._exit(0)

os.setsid()
os.chdir(work_dir)

devnull = os.open(os.devnull, os.O_RDONLY)
os.dup2(devnull, 0)
if devnull > 2:
    os.close(devnull)

log_fd = os.open(log_file, os.O_CREAT | os.O_WRONLY | os.O_TRUNC, 0o644)
os.dup2(log_fd, 1)
os.dup2(log_fd, 2)
if log_fd > 2:
    os.close(log_fd)

os.execvp(cmd[0], cmd)
PY
  else
    ( cd "$work_dir" && nohup "$@" >"$log_file" 2>&1 & echo $! >"$pid_file" )
  fi
}

# ---- up ------------------------------------------------------------------------------
cmd_up() {
  local name="$1"; shift
  local slot="" ttl="6h" frontend=1 warm=1 fresh_db=0
  local plugin_profile="" import_plugins=()
  while [ $# -gt 0 ]; do case "$1" in
    --slot) slot="$2"; shift 2;;
    --ttl) ttl="$2"; shift 2;;
    --no-frontend) frontend=0; shift;;
    --no-warm) warm=0; shift;;
    --fresh-db) fresh_db=1; shift;;
    --plugin-profile) plugin_profile="$2"; shift 2;;
    --plugin) import_plugins+=("$2"); shift 2;;
    --plugins)
      IFS=',' read -r -a plugin_items <<< "$2"
      for plugin_item in "${plugin_items[@]}"; do
        [ -n "$plugin_item" ] && import_plugins+=("$plugin_item")
      done
      shift 2
      ;;
    *) die "unknown arg: $1";;
  esac; done
  [ -n "$slot" ] || die "--slot N is required for 'up' (pick a free slot: $DEV runtime list)"

  local sd; sd="$(state_dir "$name")"; mkdir -p "$sd"

  log "1/9 allocate runtime '$name' (slot $slot) + ensure infra"
  # Re-running `up` after a mid-way failure (a plugin the validator rejects, say) must not
  # trip over its own allocation from the first attempt — every later step here is already
  # idempotent. Reuse an existing allocation only when it is on the slot being asked for;
  # a name pinned to a different slot is a real conflict and still stops the run.
  # Read the slot straight off the env file rather than through runtime_env(), which die()s
  # when the file is absent — and absent is the normal case on a first run.
  local allocated_slot=""
  local env_file="$WORKSPACE/.workspace/env/$name.env"
  if [ -f "$env_file" ]; then
    allocated_slot="$(grep -E '^AURA_WORKSPACE_SLOT=' "$env_file" | head -1 | cut -d= -f2- || true)"
  fi
  if [ -n "$allocated_slot" ]; then
    [ "$allocated_slot" = "$slot" ] \
      || die "runtime '$name' is already allocated on slot $allocated_slot, not $slot — pick another name, or: $DEV runtime destroy $name"
    log "    reusing existing allocation (slot $slot)"
  else
    "$DEV" runtime allocate auraboot "$name" --slot "$slot" --purpose "OSS host-first golden stack" --ttl "$ttl" >/dev/null
  fi
  "$DEV" infra ensure "$name" --yes >/dev/null

  local server_port vite_port bff_port pg_db redis_db pg_host pg_port pg_user pg_pass
  server_port="$(runtime_env "$name" SERVER_PORT)"
  vite_port="$(runtime_env "$name" VITE_PORT)"
  bff_port="$(runtime_env "$name" BFF_PORT)"
  pg_db="$(runtime_env "$name" POSTGRES_DB)"
  redis_db="$(runtime_env "$name" REDIS_DATABASE)"
  pg_host="$(runtime_env "$name" POSTGRES_HOST)"; pg_host="${pg_host:-127.0.0.1}"
  pg_port="$(runtime_env "$name" POSTGRES_PORT)"; pg_port="${pg_port:-5432}"
  pg_user="$(runtime_env "$name" POSTGRES_USER)"; pg_user="${pg_user:-auraboot}"
  pg_pass="$(runtime_env "$name" POSTGRES_PASSWORD)"; pg_pass="${pg_pass:-auraboot}"
  log "    backend=$server_port vite=$vite_port bff=$bff_port db=$pg_db redis-db=$redis_db"
  # Persist PG coordinates so 'env' can export PG* for the Playwright setup
  # project (00-bootstrap verifies the isolated DB via node-postgres / PG* vars).
  printf '%s\n' "$pg_host $pg_port $pg_user $pg_db $pg_pass" >"$sd/pgenv"

  log "2/9 apply schema to $pg_db"
  # `dev.sh infra ensure` reuses an existing database for the slot, which may have been
  # created by an older checkout. schema.sql is all CREATE TABLE IF NOT EXISTS, so applying
  # it to such a database silently skips every table that already exists and leaves columns
  # added since then missing — the stack then dies much later with an unrelated-looking
  # error (2026-07-13: ab_named_query.resource_code missing → plugin import failed with
  # `25P02 current transaction is aborted` pointing at a COUNT(*) on another table).
  if [ "$fresh_db" = "1" ]; then
    log "    --fresh-db: dropping and recreating $pg_db"
    PGPASSWORD=auraboot psql -h 127.0.0.1 -p 5432 -U auraboot -d postgres -q \
      -c "DROP DATABASE IF EXISTS $pg_db WITH (FORCE)" -c "CREATE DATABASE $pg_db" \
      || die "could not recreate $pg_db"
  elif [ "$(PGPASSWORD=auraboot psql -h 127.0.0.1 -p 5432 -U auraboot -d "$pg_db" -tAc \
            "SELECT count(*) FROM information_schema.tables WHERE table_schema='public'" \
            2>/dev/null || echo 0)" = "0" ]; then
    # An empty database is the one case the drift check cannot speak to. It
    # answers "is anything missing from the tables you have", and a database
    # with no tables has nothing missing — so it reports no drift, and the
    # branch below would skip the apply and leave the schema unbuilt. The
    # backend then dies on `relation "ab_scheduled_task" does not exist`,
    # which reads like a migration problem and is not one.
    #
    # This is the path every gate runner takes: `destroy` then `up` is the
    # documented way to guarantee a fresh database, and it lands here every
    # single run.
    log "    $pg_db has no tables yet — applying schema.sql"
  elif ! PG_HOST=127.0.0.1 PG_PORT=5432 PG_USER=auraboot PG_PASSWORD=auraboot \
       "$REPO_ROOT/scripts/db/check-db-matches-schema-sql.sh" "$pg_db" --quiet; then
    die "database '$pg_db' predates the current schema.sql (see the missing columns above).
     Re-applying schema.sql cannot repair it. Either:
       $0 destroy $name          # then 'up' again on a clean database
       $0 up $name --slot $slot --fresh-db   # drop + recreate the database in place"
  else
    # The drift check just established that this database matches schema.sql, so
    # replaying it would do no work — and cannot succeed anyway: the file carries
    # 40 unguarded ALTER TABLE ... ADD CONSTRAINT statements (Postgres has no
    # ADD CONSTRAINT IF NOT EXISTS), so a second apply always dies on the first
    # one. That made `up` impossible on any existing database: every backend
    # rebuild forced --fresh-db and a full plugin re-import.
    log "    database already matches schema.sql — skipping replay"
    skip_schema=1
  fi

  if [ "${skip_schema:-0}" != "1" ]; then
    PGPASSWORD=auraboot psql -v ON_ERROR_STOP=1 -h 127.0.0.1 -p 5432 -U auraboot -d "$pg_db" \
      -q -f "$REPO_ROOT/platform/src/main/resources/database/schema.sql" >"$sd/schema-apply.log" 2>&1 \
      || { tail -5 "$sd/schema-apply.log" >&2; die "schema apply failed — see $sd/schema-apply.log"; }
  fi

  log "3/9 seed gradle wrapper jar (fresh-worktree gotcha)"
  if [ ! -f "$REPO_ROOT/platform/gradle/wrapper/gradle-wrapper.jar" ]; then
    mkdir -p "$REPO_ROOT/platform/gradle/wrapper"
    cp "$CANONICAL/platform/gradle/wrapper/gradle-wrapper.jar" "$REPO_ROOT/platform/gradle/wrapper/" \
      || die "cannot seed gradle-wrapper.jar from $CANONICAL"
    cp "$CANONICAL/platform/gradlew" "$REPO_ROOT/platform/gradlew" 2>/dev/null && chmod +x "$REPO_ROOT/platform/gradlew" || true
  fi

  log "4/9 build bootJar (default ~/.gradle for plugin/mirror resolution; --no-daemon)"
  # --no-build-cache: a golden stack must produce a correct, reproducible jar. The
  # shared local Gradle build cache can hand a fresh worktree a corrupt :compileJava
  # entry (observed 2026-07-23: MqProvider.class/MqMessageHandler.class missing from
  # the cached output → platform-mq-kafka fails to resolve them, masked by UP-TO-DATE),
  # so bypass it here rather than trust a cross-worktree cache for a release build.
  ( cd "$REPO_ROOT/platform" && ./gradlew --no-daemon --no-build-cache :bootJar -x test --console=plain ) >"$sd/bootjar.log" 2>&1 \
    || die "bootJar build failed — see $sd/bootjar.log"
  local jar; jar="$(ls "$REPO_ROOT"/platform/build/libs/*-boot.jar 2>/dev/null | head -1)"
  [ -n "$jar" ] || die "boot jar not found after build"

  log "5/9 start backend (java -jar) on $server_port"
  mkdir -p "$sd/pf4j-plugins"
  spawn_detached "$sd/backend.pid" "$REPO_ROOT/platform" "$sd/backend.log" \
    env SERVER_PORT="$server_port" \
      SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:5432/${pg_db}?charSet=UTF8" \
      SPRING_DATASOURCE_USERNAME=auraboot SPRING_DATASOURCE_PASSWORD=auraboot \
      SPRING_DATA_REDIS_HOST=127.0.0.1 SPRING_DATA_REDIS_PORT=6379 SPRING_DATA_REDIS_DATABASE="$redis_db" \
      SPRING_KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:9092 \
      AURA_PLUGINS_DIR="$sd/pf4j-plugins" \
      AURA_BUILTIN_PLUGINS_DIR="$REPO_ROOT/plugins" \
      AGENT_LLM_STUB_MODE="${AGENT_LLM_STUB_MODE:-true}" \
      java -jar "$jar"
  echo "$server_port $vite_port $bff_port" >"$sd/ports"
  poll_http "http://127.0.0.1:$server_port/actuator/health" '"status":"UP"' 150 backend \
    || die "backend did not become healthy — see $sd/backend.log"
  # Port-ownership guard (2026-07-20): a FOREIGN listener on the slot port
  # (e.g. an enterprise stack whose range overlaps) answers the health poll
  # and everything downstream silently runs against the wrong stack
  # (bootstrap skipped, login 401). Health UP is not ownership — verify the
  # listener is OUR spawned pid before proceeding.
  own_pid="$(cat "$sd/backend.pid")"
  if ! lsof -ti ":$server_port" 2>/dev/null | grep -qx "$own_pid"; then
    die "port $server_port is served by a foreign process ($(lsof -ti ":$server_port" 2>/dev/null | head -1)), not our backend pid $own_pid — pick another slot; see $sd/backend.log"
  fi
  log "    backend UP (pid $own_pid, port ownership verified)"

  log "6/9 bootstrap (minimal admin + tenant; idempotent)"
  if ! curl --noproxy '*' -s -m 10 "http://127.0.0.1:$server_port/api/bootstrap/status" 2>/dev/null | grep -q '"initialized":true'; then
    curl --noproxy '*' -s -m 60 -X POST "http://127.0.0.1:$server_port/api/bootstrap/setup" -H 'Content-Type: application/json' \
      -d "{\"companyName\":\"AuraBoot Dev\",\"adminEmail\":\"$ADMIN_EMAIL\",\"adminPassword\":\"$ADMIN_PASSWORD\",\"adminDisplayName\":\"Admin\",\"systemMode\":\"single\",\"seedDemoData\":false}" \
      | grep -q '"success":true' || die "bootstrap failed"
  fi
  curl --noproxy '*' -s -m 15 -X POST "http://127.0.0.1:$server_port/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" | grep -q '"jwt"' \
    || die "login round-trip failed after bootstrap"
  log "    bootstrap OK ($ADMIN_EMAIL / $ADMIN_PASSWORD)"

  if [ -n "$plugin_profile" ] || [ "${#import_plugins[@]}" -gt 0 ]; then
    cmd_import "$name" --plugin-profile "${plugin_profile:-none}" "${import_plugins[@]/#/--plugin=}"
  fi

  if [ "$frontend" -eq 1 ]; then
    log "7/9 frontend: symlink node_modules (if missing) + start Vite+BFF"
    if [ ! -e "$REPO_ROOT/web-admin/node_modules" ]; then
      local node_modules_seed
      node_modules_seed="$(web_admin_node_modules_seed)" \
        || die "web-admin/node_modules not found in canonical checkout or existing worktrees"
      ln -sfn "$node_modules_seed" "$REPO_ROOT/web-admin/node_modules"
    fi
    spawn_detached "$sd/frontend.pid" "$REPO_ROOT/web-admin" "$sd/frontend.log" \
      env VITE_PORT="$vite_port" BFF_PORT="$bff_port" SPRING_BOOT_URL="http://127.0.0.1:$server_port" \
      BFF_INTERNAL_URL="http://127.0.0.1:$server_port" NODE_ENV=development \
      pnpm dev:full
    # Wait for Vite to start accepting connections (302 → /login is fine). Poll
    # on HTTP status, not body — a 302 has an empty body that a grep-poll would
    # never match (it would stall the full timeout before warm could start).
    poll_http_up "http://127.0.0.1:$vite_port/" 120 || true
    local code; code="$(curl --noproxy '*' -s -m 3 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$vite_port/" 2>/dev/null || true)"
    case "$code" in ""|000) die "Vite did not come up on $vite_port — see $sd/frontend.log";; esac
    log "    frontend UP (supervisor pid $(cat "$sd/frontend.pid"), vite http=$code)"
  else
    log "7/9 frontend: skipped (--no-frontend)"
  fi

  if [ "$frontend" -eq 1 ] && [ "$warm" -eq 1 ]; then
    log "8/9 warm: setup → auth storageState → pre-warm heavy routes"
    cmd_warm "$name"
  elif [ "$frontend" -eq 1 ]; then
    log "8/9 warm: skipped (--no-warm; caller's golden self-provisions via --no-deps)"
  else
    log "8/9 warm: skipped (--no-frontend)"
  fi

  log "9/9 ready ✓"
  echo
  cmd_env "$name"
}

# ---- import plugins into a running host-first stack ----------------------------------
cmd_import() {
  local name="$1"; shift
  local sd; sd="$(state_dir "$name")"
  [ -f "$sd/ports" ] || die "no running stack for '$name' (run 'up' first)"
  read -r server_port _vite_port _bff_port <"$sd/ports"

  local plugin_profile="core" profile_explicit=0
  local plugins=()
  while [ $# -gt 0 ]; do case "$1" in
    --plugin-profile) plugin_profile="$2"; profile_explicit=1; shift 2;;
    --plugin-profile=*) plugin_profile="${1#--plugin-profile=}"; profile_explicit=1; shift;;
    --profile) plugin_profile="$2"; profile_explicit=1; shift 2;;
    --profile=*) plugin_profile="${1#--profile=}"; profile_explicit=1; shift;;
    --plugin) plugins+=("$2"); shift 2;;
    --plugin=*) plugins+=("${1#--plugin=}"); shift;;
    --plugins)
      IFS=',' read -r -a plugin_items <<< "$2"
      for plugin_item in "${plugin_items[@]}"; do
        [ -n "$plugin_item" ] && plugins+=("$plugin_item")
      done
      shift 2
      ;;
    --plugins=*)
      IFS=',' read -r -a plugin_items <<< "${1#--plugins=}"
      for plugin_item in "${plugin_items[@]}"; do
        [ -n "$plugin_item" ] && plugins+=("$plugin_item")
      done
      shift
      ;;
    *) die "unknown import arg: $1";;
  esac; done

  if [ "$profile_explicit" -eq 1 ] && [ "$plugin_profile" = "none" ] && [ "${#plugins[@]}" -eq 0 ]; then
    die "--plugin-profile none requires at least one --plugin"
  fi

  local pg_host pg_port pg_user pg_db pg_pass
  if [ -f "$sd/pgenv" ]; then
    read -r pg_host pg_port pg_user pg_db pg_pass <"$sd/pgenv"
  fi

  local args=("--backend-url=http://127.0.0.1:$server_port" "--edition=oss" "--plugin-root=$REPO_ROOT/plugins")
  if [ "${#plugins[@]}" -gt 0 ]; then
    args+=("${plugins[@]}")
    log "6.5/9 import plugins (host-first): ${plugins[*]}"
  else
    args+=("--profile=$plugin_profile")
    log "6.5/9 import plugin profile '$plugin_profile' (host-first)"
  fi

  (
    export PGHOST="${pg_host:-127.0.0.1}"
    export PGPORT="${pg_port:-5432}"
    export PGUSER="${pg_user:-auraboot}"
    export PGDATABASE="${pg_db:-aura_boot}"
    export PGPASSWORD="${pg_pass:-auraboot}"
    export PG_HOST="$PGHOST"
    export PG_PORT="$PGPORT"
    export PG_USER="$PGUSER"
    export PG_DB="$PGDATABASE"
    export PG_PASSWORD="$PGPASSWORD"
    "$SCRIPT_DIR/import-plugins.sh" "${args[@]}"
  ) >"$sd/import.log" 2>&1 || die "plugin import failed — see $sd/import.log"
  log "    plugin import OK — see $sd/import.log"
}

# ---- warm (setup → auth storageState → pre-warm heavy routes) ------------------------
# Makes the FIRST golden run after 'up' reliable:
#   1. Run the Playwright `setup` project (00-bootstrap + 01-multi-role-users) so the
#      isolated stack has a selectable business space + admin membership. The script's
#      inline minimal bootstrap (companyName "AuraBoot Dev") already creates a business
#      tenant, but running the canonical setup specs is the contract auth.setup expects
#      and is idempotent. Loop up to 5× to absorb cold-start hiccups.
#   2. Run `auth --no-deps` until tests/storage/admin.json exists (storageState the
#      chromium golden project depends on). Loop up to 5×.
#   3. Pre-warm /report-designer + /dashboard with a real authenticated headless nav so
#      the client lazy chunk + Vite client deps are hot before any golden run.
cmd_warm() {
  local name="$1" sd; sd="$(state_dir "$name")"
  [ -f "$sd/ports" ] || die "no running stack for '$name' (run 'up' first)"
  local fe="$REPO_ROOT/web-admin"
  local admin_json="$fe/tests/storage/admin.json"
  local env_exports; env_exports="$(cmd_env "$name")"

  # 1) setup project — creates business space + multi-role users (idempotent).
  local i=0 setup_ok=0
  while [ "$i" -lt 5 ]; do
    i=$((i+1))
    log "    warm[setup] attempt $i/5"
    if ( cd "$fe" && eval "$env_exports" \
         && npx playwright test --project=setup --no-deps \
              tests/api/setup/00-bootstrap.spec.ts \
              tests/api/setup/01-multi-role-users.spec.ts \
              --reporter=line ) >>"$sd/warm.log" 2>&1; then
      setup_ok=1; break
    fi
    sleep 3
  done
  [ "$setup_ok" -eq 1 ] || die "warm: setup project failed after 5 attempts — see $sd/warm.log"

  # 2) auth project — produces tests/storage/admin.json (storageState).
  i=0
  rm -f "$admin_json" 2>/dev/null || true
  while [ "$i" -lt 5 ]; do
    i=$((i+1))
    log "    warm[auth] attempt $i/5"
    ( cd "$fe" && eval "$env_exports" \
        && npx playwright test --project=auth --no-deps \
             --reporter=line ) >>"$sd/warm.log" 2>&1 || true
    # Require a NON-EMPTY admin.json with a __session cookie (empty {cookies:[]}
    # means login failed — never accept that as ready).
    if [ -s "$admin_json" ] && grep -q '__session' "$admin_json" 2>/dev/null; then
      log "    warm[auth] admin.json ready (has __session)"
      break
    fi
    sleep 3
  done
  if ! { [ -s "$admin_json" ] && grep -q '__session' "$admin_json" 2>/dev/null; }; then
    die "warm: admin.json never got a working session after 5 attempts — see $sd/warm.log"
  fi

  # 3) pre-warm the heavy lazy routes with a real authenticated headless nav.
  log "    warm[routes] navigating /report-designer + /dashboard (real auth)"
  if ( cd "$fe" && eval "$env_exports" \
       && npx playwright test --project=chromium --no-deps \
            tests/e2e/_golden-stack-warm.spec.ts \
            --reporter=line ) >>"$sd/warm.log" 2>&1; then
    log "    warm[routes] heavy routes hot ✓"
  else
    # Non-fatal: a failed warm nav doesn't break the stack; first golden will
    # just pay the chunk-compile cost. Surface it so the operator can look.
    log "    warm[routes] WARNING: pre-warm nav failed (see $sd/warm.log); stack still usable"
  fi
  log "    warm OK"
}

# ---- env -----------------------------------------------------------------------------
cmd_env() {
  local name="$1" sd; sd="$(state_dir "$name")"
  [ -f "$sd/ports" ] || die "no running stack for '$name' (run 'up' first)"
  read -r server_port vite_port bff_port <"$sd/ports"
  local pg_host pg_port pg_user pg_db pg_pass
  if [ -f "$sd/pgenv" ]; then
    read -r pg_host pg_port pg_user pg_db pg_pass <"$sd/pgenv"
  fi
  cat <<EOF
# Playwright env contract for golden specs against '$name' (run from web-admin/):
export PLAYWRIGHT_BASE_URL=http://127.0.0.1:$vite_port
export BACKEND_URL=http://127.0.0.1:$server_port
export BE_PORT=$server_port
export BFF_PORT=$bff_port
export PW_SKIP_WEBSERVER=1
export NO_PROXY=localhost,127.0.0.1
# Isolated-DB coordinates for the Playwright 'setup' project (00-bootstrap's
# node-postgres invariant checks read PG* / PGHOST etc.); harmless for golden specs.
export PGHOST=${pg_host:-127.0.0.1}
export PGPORT=${pg_port:-5432}
export PGUSER=${pg_user:-auraboot}
export PGDATABASE=${pg_db:-aura_boot}
export PGPASSWORD=${pg_pass:-auraboot}
export PG_HOST=${pg_host:-127.0.0.1}
export PG_PORT=${pg_port:-5432}
export PG_USER=${pg_user:-auraboot}
export PG_DB=${pg_db:-aura_boot}
# example: cd web-admin && eval "\$(../scripts/oss-golden-stack.sh env $name)" \\
#   && npx playwright test -c playwright.gt5.config.ts tests/e2e/bpm-designer/designer-property-edit.spec.ts
# NOTE: 'up' runs an internal warm step (full setup → auth storageState → pre-warm
#       /report-designer + /dashboard), and web-admin/vite.config.ts pre-bundles the
#       heavy lazy-route deps (optimizeDeps.include, #947). The FIRST golden run after
#       'up' is therefore reliable. Fallback if a brand-new route ever cold-reopts:
#       curl --noproxy '*' -s \$PLAYWRIGHT_BASE_URL/<route> once before running.
EOF
}

# ---- status --------------------------------------------------------------------------
cmd_status() {
  local name="$1" sd; sd="$(state_dir "$name")"
  [ -f "$sd/ports" ] || { echo "no stack for '$name'"; return 1; }
  read -r server_port vite_port bff_port <"$sd/ports"
  local be vi
  be="$(curl --noproxy '*' -s -m 3 "http://127.0.0.1:$server_port/actuator/health" 2>/dev/null | grep -o '"status":"UP"' || echo DOWN)"
  vi="$(curl --noproxy '*' -s -m 3 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$vite_port/" 2>/dev/null || true)"
  vi="${vi:-000}"
  echo "backend($server_port)=$be  vite($vite_port)=$vi  bff=$bff_port"
}

# Recursively SIGKILL a PID and ALL its descendants (post-order: leaves first).
# The frontend tree is pnpm dev:full → sh -c → concurrently → {vite, bff}; a plain
# `pkill -P` only reaps direct children and orphans vite/bff (which keep their
# listeners and break the next 'up' with EADDRINUSE). SIGKILL (not SIGTERM) is
# required because `concurrently --restart-tries 20` traps SIGTERM and respawns
# its children; -9 stops it dead.
kill_tree() {
  local pid="$1" child sig="${2:-KILL}"
  for child in $(pgrep -P "$pid" 2>/dev/null); do kill_tree "$child" "$sig"; done
  kill -"$sig" "$pid" 2>/dev/null || true
}

# SIGKILL the process listening on $1 AND its ancestor chain UP TO the
# `concurrently` supervisor (matched by command line), so the restart-loop leader
# dies too. Scoped to a single exact port → never touches another slot's stack.
kill_listener_supervisor() {
  local port="$1" pid ppid cmd
  for pid in $(lsof -nP -iTCP:"$port" -sTCP:LISTEN -t 2>/dev/null || true); do
    [ -n "$pid" ] || continue
    # Walk up to (and including) the concurrently restart-loop leader, then kill
    # that whole subtree. If the tree has already detached/reparented, keep the
    # highest repo/frontend-related ancestor as the kill target.
    local cur="$pid" sup="$pid" i=0
    while [ "$i" -lt 12 ]; do
      cmd="$(ps -o command= -p "$cur" 2>/dev/null || true)"
      case "$cmd" in
        *concurrently*) sup="$cur"; break;;
        *"$REPO_ROOT/web-admin"*|*"pnpm dev:"*) sup="$cur";;
      esac
      ppid="$(ps -o ppid= -p "$cur" 2>/dev/null | tr -d ' ')"
      [ -n "$ppid" ] && [ "$ppid" != "1" ] && [ "$ppid" != "0" ] || break
      cur="$ppid"; i=$((i+1))
    done
    kill_tree "$sup"
    kill_tree "$pid"
  done
}

# ---- down (stop processes, keep runtime/DB) ------------------------------------------
cmd_down() {
  local name="$1" sd; sd="$(state_dir "$name")"
  [ -d "$sd" ] || { echo "no stack for '$name'"; return 0; }
  # Kill recorded supervisor trees before relying on port listeners; pnpm/concurrently
  # can otherwise respawn vite/bff while the shutdown is in progress.
  if [ -f "$sd/frontend.pid" ]; then local fp; fp="$(cat "$sd/frontend.pid")"; kill_tree "$fp"; fi
  if [ -f "$sd/backend.pid" ]; then kill_tree "$(cat "$sd/backend.pid")"; fi
  sleep 2
  # Belt: kill anything still bound to THIS runtime's exact ports only (never a
  # shared/other-slot port). For the frontend ports, walk up to the concurrently
  # restart-loop leader and SIGKILL its subtree — killing just the listener lets
  # `--restart-tries` respawn it. Retry a few times to absorb a mid-restart race.
  if [ -f "$sd/ports" ]; then
    read -r server_port vite_port bff_port <"$sd/ports"
    local attempt
    for attempt in 1 2 3 4; do
      local any=0
      # frontend ports: kill the supervisor subtree behind the listener
      for p in "$vite_port" "$bff_port"; do
        local pid; pid="$(lsof -nP -iTCP:"$p" -sTCP:LISTEN -t 2>/dev/null || true)"
        if [ -n "$pid" ]; then any=1; kill_listener_supervisor "$p"; log "killed frontend supervisor on $p (attempt $attempt)"; fi
      done
      # backend port: a plain SIGKILL of the listener is enough (no restart loop)
      local bpid; bpid="$(lsof -nP -iTCP:"$server_port" -sTCP:LISTEN -t 2>/dev/null || true)"
      if [ -n "$bpid" ]; then any=1; kill -9 $bpid 2>/dev/null && log "killed straggler on $server_port (attempt $attempt)" || true; fi
      [ "$any" -eq 0 ] && break
      sleep 1
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
  import) cmd_import "$name" "$@";;
  warm) cmd_warm "$name";;
  env) cmd_env "$name";;
  status) cmd_status "$name";;
  down) cmd_down "$name";;
  destroy) cmd_destroy "$name";;
  *) die "unknown subcommand: $sub (up|import|warm|env|status|down|destroy)";;
esac
