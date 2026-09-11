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
#   ./scripts/oss-golden-stack.sh up   <name> [--slot N] [--runtime-mode development|verification|control|performance] [--no-frontend] [--no-warm] [--fresh-db] [--ttl 6h] [--extra-plugin-root PATH] [--plugin-profile P|--plugin X]
#       --no-warm : keep the frontend but skip the setup/auth/pre-warm step — for goldens
#                   that self-provision accounts and run with --no-deps (no storageState).
#       --fresh-db: drop + recreate the slot's database before applying the snapshot. `up`
#                   otherwise refuses to run on a database that predates the current
#                   snapshot (db/snapshots/schema-current.sql is a pg_dump — plain CREATE
#                   TABLE, so it cannot back-fill columns into tables that already exist).
#       --extra-plugin-root: repeatable explicit fallback after this checkout's OSS plugins;
#                            sibling plugin repositories are never guessed implicitly.
#   ./scripts/oss-golden-stack.sh import <name> [--extra-plugin-root PATH] [--plugin-profile P|--plugin X]
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
# CI uses sibling repositories; local worktrees usually find dev.sh above them.
WORKSPACE="${AURA_WORKSPACE_ROOT:-${AURA_CI_WORKSPACE_ROOT:-}}"
if [ -z "$WORKSPACE" ] && [ -f "$(dirname "$REPO_ROOT")/auraboot-workspace/dev.sh" ]; then
  WORKSPACE="$(dirname "$REPO_ROOT")/auraboot-workspace"
fi
[ -n "$WORKSPACE" ] || WORKSPACE="$REPO_ROOT"
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
[ -f "$WORKSPACE/dev.sh" ] || { echo "FATAL: cannot find workspace dev.sh for $REPO_ROOT"; exit 1; }
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

web_admin_node_modules_usable() {
  local candidate="$1"
  local candidate_real checkout_root checkout_node_modules_real entry entry_real
  [ -d "$candidate" ] || return 1
  candidate_real="$(cd "$candidate" 2>/dev/null && pwd -P)" || return 1
  checkout_root="$(cd "$(dirname "$candidate")/.." 2>/dev/null && pwd -P)" || return 1
  checkout_node_modules_real=""
  if [ -d "$checkout_root/node_modules" ]; then
    checkout_node_modules_real="$(cd "$checkout_root/node_modules" 2>/dev/null && pwd -P)" || return 1
  fi

  # A published dependency view must own the files it exposes. Merely checking
  # package.json readability accepts capsules whose package symlinks escape into
  # a removed worktree. A normal pnpm workspace may resolve into the same
  # checkout's root node_modules/.pnpm store, so that one local boundary is also
  # allowed. Anything outside both boundaries is stale and must be rejected.
  for entry in \
    react/index.js \
    react-dom/client.js \
    @tailwindcss/vite/dist/index.mjs \
    tailwindcss/index.css
  do
    [ -r "$candidate/$entry" ] || return 1
    entry_real="$(realpath "$candidate/$entry" 2>/dev/null)" || return 1
    case "$entry_real" in
      "$candidate_real"/*) continue ;;
    esac
    [ -n "$checkout_node_modules_real" ] || return 1
    case "$entry_real" in
      "$checkout_node_modules_real"/*) ;;
      *) return 1 ;;
    esac
  done
}

web_admin_node_modules_seed() {
  local candidate
  for candidate in "$CANONICAL/web-admin/node_modules" "$REPO_ROOT/web-admin/node_modules"; do
    web_admin_node_modules_usable "$candidate" && { echo "$candidate"; return 0; }
  done

  while IFS= read -r candidate; do
    candidate="$candidate/web-admin/node_modules"
    web_admin_node_modules_usable "$candidate" && { echo "$candidate"; return 0; }
  done < <(git -C "$REPO_ROOT" worktree list --porcelain 2>/dev/null | awk '/^worktree /{print substr($0,10)}')

  return 1
}

# Resolve backend packages from the requested import profile/explicit plugin list,
# build their PF4J jars from the explicitly configured roots, and stage them before
# the backend starts. A backend.jarPath declaration is the packaging denominator:
# pluginType can drift, but a declared runtime backend must never be silently skipped.
# Import-time handler validation is intentionally fail-closed: importing the DSL
# first and hot-loading a jar later leaves a backend package impossible to install.
stage_requested_backend_jars() {
  local sd="$1" runtime_name="$2"; shift 2
  local backend_rows backend_specs=()
  backend_rows="$(node "$SCRIPT_DIR/dev/resolve-plugin-backends.mjs" \
    --repo-root "$REPO_ROOT" --format tsv "$@")" \
    || die "could not resolve requested plugin backends"

  local plugin_name plugin_dir backend_dir jar_path entry_class
  while IFS=$'\t' read -r plugin_name plugin_dir backend_dir jar_path entry_class; do
    [ -n "$plugin_name" ] && backend_specs+=("$plugin_name"$'\t'"$plugin_dir"$'\t'"$backend_dir"$'\t'"$jar_path"$'\t'"$entry_class")
  done <<< "$backend_rows"

  mkdir -p "$sd/pf4j-plugins"
  find "$sd/pf4j-plugins" -maxdepth 1 -type f -name '*.jar' -delete
  printf 'plugin\tplugin_dir\tsource_jar\tstaged_jar\tsha256\n' >"$sd/pf4j-staging.tsv"
  [ "${#backend_specs[@]}" -gt 0 ] || return 0

  log "4.5/9 build and stage ${#backend_specs[@]} PF4J backend jar(s) from explicit source roots"
  local maven_repo gradle_home
  maven_repo="$(runtime_env "$runtime_name" MAVEN_REPO_LOCAL)"
  gradle_home="$(runtime_env "$runtime_name" GRADLE_USER_HOME)"
  [ -n "$maven_repo" ] || die "runtime Maven repository is missing for $runtime_name"
  [ -n "$gradle_home" ] || die "runtime Gradle home is missing for $runtime_name"
  mkdir -p "$maven_repo" "$gradle_home"
  # Use the workspace managed Gradle entry instead of invoking the wrapper
  # directly. Besides preserving the runtime-local Maven/Gradle homes, this
  # seeds the runtime's shared wrapper distribution from the validated host
  # cache and injects the canonical China mirror init script. A direct wrapper
  # call here made fresh CI runtimes bypass both contracts and redownload the
  # Gradle distribution from services.gradle.org.
  "$DEV" gradle "$runtime_name" --project "$REPO_ROOT/platform" -- \
      --no-build-cache \
      :platform-plugin-api:publishToMavenLocal :publishToMavenLocal --console=plain \
      >"$sd/platform-publications.log" 2>&1 \
    || die "platform-plugin-api/auraboot-core publish failed — see $sd/platform-publications.log"

  local spec staged_path jar_hash jar_entry_class entry_class_path
  for spec in "${backend_specs[@]}"; do
    IFS=$'\t' read -r plugin_name plugin_dir backend_dir jar_path entry_class <<< "$spec"
    [ -d "$backend_dir" ] || die "plugin backend missing for $plugin_name: $backend_dir"
    "$DEV" gradle "$runtime_name" --project "$backend_dir" \
      --wrapper "$REPO_ROOT/platform/gradlew" -- clean jar --console=plain \
      >"$sd/${plugin_name}-jar.log" 2>&1 \
      || die "plugin backend jar build failed for $plugin_name — see $sd/${plugin_name}-jar.log"
    [ -f "$jar_path" ] || die "plugin backend jar missing after build for $plugin_name: $jar_path"
    # JAR manifests fold physical lines after 72 bytes. Reassemble continuation
    # lines before comparing a long fully-qualified Plugin-Class value.
    jar_entry_class="$(unzip -p "$jar_path" META-INF/MANIFEST.MF 2>/dev/null \
      | tr -d '\r' \
      | awk '
          /^Plugin-Class:[[:space:]]*/ {
            sub(/^Plugin-Class:[[:space:]]*/, "")
            value = $0
            collecting = 1
            next
          }
          collecting && /^ / {
            sub(/^ /, "")
            value = value $0
            next
          }
          collecting {
            print value
            collecting = 0
            exit
          }
          END {
            if (collecting) print value
          }
        ')"
    [ "$jar_entry_class" = "$entry_class" ] \
      || die "plugin backend entryClass mismatch for $plugin_name: declared=$entry_class jar=${jar_entry_class:-missing}"
    entry_class_path="$(printf '%s' "$entry_class" | tr '.' '/').class"
    jar tf "$jar_path" | grep -Fxq "$entry_class_path" \
      || die "plugin backend entryClass is missing from jar for $plugin_name: $entry_class_path"
    staged_path="$sd/pf4j-plugins/$(basename "$jar_path")"
    [ ! -e "$staged_path" ] || die "duplicate staged PF4J jar name: $staged_path"
    cp "$jar_path" "$staged_path"
    jar_hash="$(shasum -a 256 "$jar_path" | awk '{print $1}')"
    [ "$jar_hash" = "$(shasum -a 256 "$staged_path" | awk '{print $1}')" ] \
      || die "staged PF4J jar hash mismatch for $plugin_name"
    printf '%s\t%s\t%s\t%s\t%s\n' \
      "$plugin_name" "$plugin_dir" "$jar_path" "$staged_path" "$jar_hash" \
      >>"$sd/pf4j-staging.tsv"
    log "    staged $plugin_name (${jar_hash:0:12})"
  done
  log "    PF4J staging receipt: $sd/pf4j-staging.tsv"
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
  local slot="" ttl="6h" runtime_mode="development" frontend=1 warm=1 fresh_db=0
  local plugin_profile="" import_plugins=() extra_plugin_roots=()
  local extra_root plugin_item
  while [ $# -gt 0 ]; do case "$1" in
    --slot) slot="$2"; shift 2;;
    --ttl) ttl="$2"; shift 2;;
    --runtime-mode) runtime_mode="$2"; shift 2;;
    --no-frontend) frontend=0; shift;;
    --no-warm) warm=0; shift;;
    --fresh-db) fresh_db=1; shift;;
    --extra-plugin-root)
      [ -d "$2" ] || die "extra plugin root does not exist: $2"
      extra_plugin_roots+=("$(cd "$2" && pwd)")
      shift 2
      ;;
    --extra-plugin-root=*)
      extra_root="${1#--extra-plugin-root=}"
      [ -d "$extra_root" ] || die "extra plugin root does not exist: $extra_root"
      extra_plugin_roots+=("$(cd "$extra_root" && pwd)")
      shift
      ;;
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
  case "$runtime_mode" in
    development|verification|control|performance) ;;
    *) die "--runtime-mode must be development|verification|control|performance" ;;
  esac

  local sd; sd="$(state_dir "$name")"; mkdir -p "$sd"

  log "1/9 allocate runtime '$name' (slot $slot) + ensure infra"
  # `runtime ensure` is the idempotent allocation contract: the same stable name + slot +
  # source worktree is reused, while a different slot/worktree/branch is rejected. Keep a
  # compatibility branch for workspaces that have not yet upgraded the root dispatcher.
  if "$DEV" runtime 2>/dev/null | grep -q 'runtime ensure'; then
    "$DEV" runtime ensure auraboot "$name" --slot "$slot" \
      --purpose "OSS host-first golden stack" --ttl "$ttl" --source-root "$REPO_ROOT" \
      --mode "$runtime_mode" >/dev/null
    log "    ensured stable allocation (slot $slot, source=$REPO_ROOT, mode=$runtime_mode)"
  else
    local allocated_slot=""
    local env_file="$WORKSPACE/.workspace/env/$name.env"
    if [ -f "$env_file" ]; then
      allocated_slot="$(grep -E '^AURA_WORKSPACE_SLOT=' "$env_file" | head -1 | cut -d= -f2- || true)"
    fi
    if [ -n "$allocated_slot" ]; then
      [ "$allocated_slot" = "$slot" ] \
        || die "runtime '$name' is already allocated on slot $allocated_slot, not $slot — pick another name, or: $DEV runtime destroy $name"
      log "    reusing existing allocation (slot $slot; legacy dispatcher)"
    else
      "$DEV" runtime allocate auraboot "$name" --slot "$slot" --purpose "OSS host-first golden stack" --ttl "$ttl" >/dev/null
    fi
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
  # created by an older checkout. The bring-up file is db/snapshots/schema-current.sql — the
  # Flyway-generated snapshot (a pg_dump, plain CREATE TABLE). It can only be loaded onto a
  # fresh database; it cannot back-fill columns onto an existing one. So a reused database that
  # predates the current snapshot is missing columns and the stack dies much later with an
  # unrelated-looking error (2026-07-13: ab_named_query.resource_code missing → plugin import
  # failed with `25P02 current transaction is aborted` pointing at a COUNT(*) on another table).
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
    log "    $pg_db has no tables yet — applying snapshot"
  elif ! PG_HOST=127.0.0.1 PG_PORT=5432 PG_USER=auraboot PG_PASSWORD=auraboot \
       "$REPO_ROOT/scripts/db/check-db-matches-snapshot.sh" "$pg_db" --quiet; then
    die "database '$pg_db' predates the current snapshot (see the missing columns above).
     The snapshot is a pg_dump and cannot back-fill an existing database. Either:
       $0 destroy $name          # then 'up' again on a clean database
       $0 up $name --slot $slot --fresh-db   # drop + recreate the database in place"
  else
    # The drift check just established that this database matches the snapshot, so
    # replaying it would do no work — and cannot succeed anyway: the snapshot is a
    # pg_dump of plain CREATE TABLE statements (no IF NOT EXISTS), so a second apply
    # always dies on the first table that already exists. Reuse the database as-is.
    log "    database already matches the snapshot — skipping replay"
    skip_schema=1
  fi

  if [ "${skip_schema:-0}" != "1" ]; then
    PGPASSWORD=auraboot psql -v ON_ERROR_STOP=1 -h 127.0.0.1 -p 5432 -U auraboot -d "$pg_db" \
      -q -f "$REPO_ROOT/platform/src/main/resources/db/snapshots/schema-current.sql" >"$sd/schema-apply.log" 2>&1 \
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

  local staging_args=(--profile "${plugin_profile:-none}")
  if [ "${#extra_plugin_roots[@]}" -gt 0 ]; then
    for extra_root in "${extra_plugin_roots[@]}"; do
      staging_args+=(--extra-plugin-root "$extra_root")
    done
  fi
  if [ "${#import_plugins[@]}" -gt 0 ]; then
    for plugin_item in "${import_plugins[@]}"; do
      staging_args+=(--plugin "$plugin_item")
    done
  fi
  stage_requested_backend_jars "$sd" "$name" "${staging_args[@]}"

  log "5/9 start backend (java -jar) on $server_port"
  mkdir -p "$sd/pf4j-plugins"
  spawn_detached "$sd/backend.pid" "$REPO_ROOT/platform" "$sd/backend.log" \
    env SERVER_PORT="$server_port" \
      SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:5432/${pg_db}?charSet=UTF8" \
      SPRING_DATASOURCE_USERNAME=auraboot SPRING_DATASOURCE_PASSWORD=auraboot \
      SPRING_DATA_REDIS_HOST=127.0.0.1 SPRING_DATA_REDIS_PORT=6379 SPRING_DATA_REDIS_DATABASE="$redis_db" \
      SPRING_KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:9092 \
      AURA_PLUGINS_DIR="$sd/pf4j-plugins" \
      LOGGING_LEVEL_COM_AURABOOT_FRAMEWORK_META_MAPPER=DEBUG \
      LOGGING_LEVEL_COM_AURABOOT_FRAMEWORK_PERMISSION_MAPPER=DEBUG \
      LOGGING_LEVEL_COM_AURABOOT_FRAMEWORK_TENANT_MAPPER=DEBUG \
      LOGGING_LEVEL_COM_AURABOOT_FRAMEWORK_VIEW_MAPPER=DEBUG \
      LOGGING_LEVEL_COM_AURABOOT_FRAMEWORK_USER_MAPPER=DEBUG \
      LOGGING_LEVEL_COM_AURABOOT_FRAMEWORK_OBSERVABILITY_MAPPER=DEBUG \
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
    local import_args=(--plugin-profile "${plugin_profile:-none}")
    if [ "${#extra_plugin_roots[@]}" -gt 0 ]; then
      for extra_root in "${extra_plugin_roots[@]}"; do
        import_args+=(--extra-plugin-root "$extra_root")
      done
    fi
    if [ "${#import_plugins[@]}" -gt 0 ]; then
      for plugin_item in "${import_plugins[@]}"; do
        import_args+=(--plugin "$plugin_item")
      done
    fi
    cmd_import "$name" "${import_args[@]}"
  fi

  if [ "$frontend" -eq 1 ]; then
    log "7/9 frontend: reuse or provision node_modules + start Vite+BFF"
    if ! web_admin_node_modules_usable "$REPO_ROOT/web-admin/node_modules"; then
      if [ -L "$REPO_ROOT/web-admin/node_modules" ]; then
        rm -f "$REPO_ROOT/web-admin/node_modules"
      elif [ -e "$REPO_ROOT/web-admin/node_modules" ]; then
        die "web-admin/node_modules exists but required runtime packages are unreadable; refusing to replace a real directory"
      fi
      local node_modules_seed
      node_modules_seed="$(web_admin_node_modules_seed || true)"
      if [ -n "$node_modules_seed" ]; then
        ln -sfn "$node_modules_seed" "$REPO_ROOT/web-admin/node_modules"
      else
        log "    no reusable node_modules found; installing from lockfile with the runtime pnpm store"
        local npm_registry="${NPM_CONFIG_REGISTRY:-https://registry.npmmirror.com}"
        local pnpm_version="${AURA_PNPM_VERSION:-9.15.9}"
        COREPACK_NPM_REGISTRY="$npm_registry" COREPACK_DEFAULT_TO_LATEST=0 \
          COREPACK_ENABLE_DOWNLOAD_PROMPT=0 \
          "$DEV" run "$name" --workdir "$REPO_ROOT/web-admin" -- \
            corepack install --global "pnpm@$pnpm_version" \
            >"$sd/frontend-corepack.log" 2>&1 \
          || die "pnpm $pnpm_version bootstrap failed — see $sd/frontend-corepack.log"
        CI=true NPM_CONFIG_REGISTRY="$npm_registry" \
          COREPACK_NPM_REGISTRY="$npm_registry" COREPACK_DEFAULT_TO_LATEST=0 \
          "$DEV" run "$name" --workdir "$REPO_ROOT/web-admin" -- \
            pnpm --filter auraboot-app install --frozen-lockfile --reporter=append-only \
            >"$sd/frontend-dependencies.log" 2>&1 \
          || die "web-admin dependency install failed — see $sd/frontend-dependencies.log"
        web_admin_node_modules_usable "$REPO_ROOT/web-admin/node_modules" \
          || die "web-admin dependency install completed without usable runtime packages"
      fi
    fi
    # A slot can be reused by a newer checkout/composition. Vite's on-disk optimized dependency
    # cache is keyed by port for this runner; keeping it across destroy→up allowed React itself and
    # a lazily optimized react-router chunk from different generations to coexist, producing an
    # Invalid hook call in otherwise valid components. The runtime is exclusively owned here, so
    # rebuild this exact slot cache as part of every fresh stack lifecycle.
    local vite_cache_dir="$REPO_ROOT/web-admin/.vite/$vite_port"
    case "$vite_cache_dir" in
      "$REPO_ROOT/web-admin/.vite/"*) rm -rf "$vite_cache_dir" ;;
      *) die "refusing to clear unexpected Vite cache path: $vite_cache_dir" ;;
    esac
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
  local plugins=() extra_plugin_roots=()
  local extra_root
  while [ $# -gt 0 ]; do case "$1" in
    --plugin-profile) plugin_profile="$2"; profile_explicit=1; shift 2;;
    --plugin-profile=*) plugin_profile="${1#--plugin-profile=}"; profile_explicit=1; shift;;
    --profile) plugin_profile="$2"; profile_explicit=1; shift 2;;
    --profile=*) plugin_profile="${1#--profile=}"; profile_explicit=1; shift;;
    --extra-plugin-root)
      [ -d "$2" ] || die "extra plugin root does not exist: $2"
      extra_plugin_roots+=("$(cd "$2" && pwd)")
      shift 2
      ;;
    --extra-plugin-root=*)
      extra_root="${1#--extra-plugin-root=}"
      [ -d "$extra_root" ] || die "extra plugin root does not exist: $extra_root"
      extra_plugin_roots+=("$(cd "$extra_root" && pwd)")
      shift
      ;;
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
  if [ "${#extra_plugin_roots[@]}" -gt 0 ]; then
    for extra_root in "${extra_plugin_roots[@]}"; do
      args+=("--extra-plugin-root=$extra_root")
    done
  fi
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
  local pg_host pg_port pg_user pg_db pg_pass evidence_root
  if [ -f "$sd/pgenv" ]; then
    read -r pg_host pg_port pg_user pg_db pg_pass <"$sd/pgenv"
  fi
  evidence_root="$(runtime_env "$name" AURA_EVIDENCE_ROOT)"
  [ -n "$evidence_root" ] || die "runtime env lacks AURA_EVIDENCE_ROOT; deploy the workspace runtime lifecycle before running this gate"
  cat <<EOF
# Playwright env contract for golden specs against '$name' (run from web-admin/):
export PLAYWRIGHT_BASE_URL=http://127.0.0.1:$vite_port
export BACKEND_URL=http://127.0.0.1:$server_port
export BE_PORT=$server_port
export BFF_PORT=$bff_port
export PW_SKIP_WEBSERVER=1
export NO_PROXY=localhost,127.0.0.1
export AURA_EVIDENCE_ROOT=$evidence_root
export PW_ARTIFACT_DIR=$evidence_root/playwright/artifacts
export PW_REPORT_DIR=$evidence_root/playwright/report
export PW_RESULTS_JSON=$evidence_root/playwright/report/results.json
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
