#!/bin/bash
#
# Unified per-worktree dev environment entrypoint.
#
# P0 scope:
#   - bugfix mode uses Docker infra plus host backend/Vite/BFF
#   - destructive reset/init steps are explicit
#   - stop targets exact tmux sessions and ports, never global pkill

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_ROOT"

# shellcheck source=scripts/dev/lib/env-loader.sh
source "$SCRIPT_DIR/lib/env-loader.sh"
# shellcheck source=scripts/dev/lib/process-manager.sh
source "$SCRIPT_DIR/lib/process-manager.sh"
# shellcheck source=scripts/dev/lib/health.sh
source "$SCRIPT_DIR/lib/health.sh"

COMMAND="${1:-}"
if [ -n "$COMMAND" ]; then
    shift
fi

MODE="bugfix"
PRODUCT="${AURA_PRODUCT:-oss}"
SLUG=""
DRY_RUN=0
LEVEL="health"
SERVICE="all"
WITH_STORAGE=0
PURGE=0

usage() {
    cat <<USAGE
Usage: scripts/dev/env.sh <start|stop|status|reset|demo|rebuild-demo|verify|logs|list|inspect> [options]

Options:
  --mode=bugfix           Daily bugfix mode: Docker infra, host apps
  --product=oss|enterprise
  --slug=<name>           Isolated stack slug
  --service=<name>        logs service: backend|frontend|bff|docker|all
  --level=health          verify level
  --with-storage          start optional storage infra
  --purge                 stop docker volumes when stopping isolated infra
  --dry-run               Print plan, do not mutate
  --help                  Show this help
USAGE
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --mode=*) MODE="${1#--mode=}" ;;
        --mode) MODE="${2:-}"; shift ;;
        --product=*) PRODUCT="${1#--product=}" ;;
        --product) PRODUCT="${2:-}"; shift ;;
        --slug=*) SLUG="${1#--slug=}" ;;
        --slug) SLUG="${2:-}"; shift ;;
        --service=*) SERVICE="${1#--service=}" ;;
        --service) SERVICE="${2:-}"; shift ;;
        --level=*) LEVEL="${1#--level=}" ;;
        --level) LEVEL="${2:-}"; shift ;;
        --with-storage) WITH_STORAGE=1 ;;
        --purge) PURGE=1 ;;
        --dry-run) DRY_RUN=1 ;;
        --help|-h) usage; exit 0 ;;
        *) echo "ERROR: unknown argument: $1" >&2; usage; exit 2 ;;
    esac
    shift
done

if [ -z "$COMMAND" ] || [ "$COMMAND" = "--help" ] || [ "$COMMAND" = "-h" ]; then
    usage
    exit 0
fi

normalize_slug() {
    local raw="$1"
    printf '%s' "$raw" \
        | tr '[:upper:]' '[:lower:]' \
        | tr '/_' '--' \
        | sed -E 's/[^a-z0-9-]/-/g; s/-+/-/g; s/^-//; s/-$//' \
        | cut -c1-24
}

derive_slug() {
    if [ -n "$SLUG" ]; then
        normalize_slug "$SLUG"
        return
    fi

    local branch
    branch="$(git -C "$PROJECT_ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || echo HEAD)"
    if [ "$branch" = "HEAD" ] || [ -z "$branch" ]; then
        branch="$(basename "$PROJECT_ROOT")"
    fi
    normalize_slug "$branch"
}

SLUG="$(derive_slug)"
if [ -z "$SLUG" ] || ! [[ "$SLUG" =~ ^[a-z0-9][a-z0-9-]{0,23}$ ]]; then
    echo "ERROR: invalid slug '$SLUG' (must match ^[a-z0-9][a-z0-9-]{0,23}\$)" >&2
    exit 2
fi

backend_session() {
    echo "auraboot-$SLUG-backend"
}

frontend_session() {
    echo "auraboot-$SLUG-frontend"
}

bff_session() {
    echo "auraboot-$SLUG-bff"
}

backend_log() {
    echo "/tmp/aura-$SLUG-backend.log"
}

frontend_log() {
    echo "/tmp/aura-$SLUG-frontend.log"
}

bff_log() {
    echo "/tmp/aura-$SLUG-bff.log"
}

registry_root() {
    aura_env__registry_root "$PROJECT_ROOT"
}

registry_exports_file() {
    echo "$(registry_root)/envs/$SLUG/exports.env"
}

registry_manifest_file() {
    echo "$(registry_root)/envs/$SLUG/manifest.json"
}

registry_manifest_value() {
    local key="$1"
    local manifest
    manifest="$(registry_manifest_file)"
    if [ ! -f "$manifest" ]; then
        return 1
    fi
    node -e 'const fs = require("fs"); const [file, key] = process.argv.slice(1); const value = JSON.parse(fs.readFileSync(file, "utf8"))[key]; if (value) process.stdout.write(String(value));' "$manifest" "$key"
}

registered_core_root() {
    local root
    root="$(registry_manifest_value coreRoot || true)"
    echo "${root:-$PROJECT_ROOT}"
}

current_branch_for_root() {
    local root="$1"
    git -C "$root" branch --show-current 2>/dev/null || echo "unknown"
}

resolve_enterprise_root() {
    local registered_enterprise_root
    registered_enterprise_root="$(registry_manifest_value enterpriseRoot || true)"
    if [ -n "${AURA_ENTERPRISE_ROOT:-}" ]; then
        echo "$AURA_ENTERPRISE_ROOT"
    elif [ -n "$registered_enterprise_root" ]; then
        echo "$registered_enterprise_root"
    elif [ -d "$PROJECT_ROOT/../auraboot-enterprise" ]; then
        cd "$PROJECT_ROOT/../auraboot-enterprise" && pwd
    elif [ -d "/Users/ghj/work/auraboot/auraboot-enterprise" ]; then
        echo "/Users/ghj/work/auraboot/auraboot-enterprise"
    else
        echo ""
    fi
}

backend_root_for_product() {
    case "$PRODUCT" in
        oss) echo "$(registered_core_root)/platform" ;;
        enterprise)
            local enterprise_root
            enterprise_root="$(resolve_enterprise_root)"
            if [ -z "$enterprise_root" ]; then
                echo "ERROR: AURA_ENTERPRISE_ROOT is required for product=enterprise" >&2
                return 1
            fi
            echo "$enterprise_root/platform"
            ;;
        *) echo "ERROR: unknown product '$PRODUCT' (expected oss|enterprise)" >&2; return 2 ;;
    esac
}

reset_script_for_product() {
    case "$PRODUCT" in
        oss) echo "$(registered_core_root)/scripts/reset-db.sh" ;;
        enterprise)
            local enterprise_root
            enterprise_root="$(resolve_enterprise_root)"
            if [ -n "$enterprise_root" ]; then
                echo "$enterprise_root/scripts/reset-db.sh"
            else
                echo "scripts/reset-db.sh"
            fi
            ;;
        *) echo "ERROR: unknown product '$PRODUCT' (expected oss|enterprise)" >&2; return 2 ;;
    esac
}

load_bugfix_env() {
    aura_env_load r2 "$SLUG"
}

registry_upsert_current() {
    local status="${1:-running}"
    local core_root
    core_root="$(registered_core_root)"
    local enterprise_root=""
    local enterprise_branch=""
    if [ "$PRODUCT" = "enterprise" ]; then
        enterprise_root="$(resolve_enterprise_root || true)"
        if [ -n "$enterprise_root" ]; then
            enterprise_branch="$(current_branch_for_root "$enterprise_root")"
        fi
    fi

    local args=(
        "$SCRIPT_DIR/lib/env-registry.mjs" upsert
        --registry-root "$(registry_root)"
        --slug "$SLUG"
        --mode "$MODE"
        --product "$PRODUCT"
        --core-root "$core_root"
        --core-branch "$(current_branch_for_root "$core_root")"
        --compose-project "${COMPOSE_PROJECT_NAME:-auraboot-$SLUG}"
        --status "$status"
        --pg-port "$PG_PORT"
        --redis-port "$REDIS_PORT"
        --be-port "$BE_PORT"
        --vite-port "$VITE_PORT"
        --bff-port "$BFF_PORT"
    )
    if [ -n "$enterprise_root" ]; then
        args+=(--enterprise-root "$enterprise_root")
    fi
    if [ -n "$enterprise_branch" ]; then
        args+=(--enterprise-branch "$enterprise_branch")
    fi
    if [ -n "${OFFSET:-}" ]; then
        args+=(--offset "$OFFSET")
    fi
    if [ -n "${MINIO_API_PORT:-}" ]; then
        args+=(--minio-api-port "$MINIO_API_PORT")
    fi
    if [ -n "${MINIO_CONSOLE_PORT:-}" ]; then
        args+=(--minio-console-port "$MINIO_CONSOLE_PORT")
    fi
    if [ -n "${PROD_FRONTEND_PORT:-}" ]; then
        args+=(--prod-frontend-port "$PROD_FRONTEND_PORT")
    fi

    node "${args[@]}"
}

print_start_plan() {
    local infra_cmd="scripts/dev/start-dev-infra.sh --slug=$SLUG"
    infra_cmd="$infra_cmd --product=$PRODUCT"
    [ "$WITH_STORAGE" = "1" ] && infra_cmd="$infra_cmd --with-storage"
    [ "$DRY_RUN" = "1" ] && infra_cmd="$infra_cmd --dry-run"
    local exports_file
    exports_file="$(registry_exports_file)"

    local backend_root
    backend_root="$(backend_root_for_product || true)"
    if [ -f "$exports_file" ]; then
        # shellcheck disable=SC1090
        source "$exports_file"
        infra_cmd="existing registry exports: $exports_file"
    fi

    cat <<PLAN
Bugfix environment start plan
  mode:             $MODE
  product:          $PRODUCT
  slug:             $SLUG
  infra command:    $infra_cmd
  backend root:     ${backend_root:-unresolved}
  backend session:  $(backend_session)
  frontend session: $(frontend_session)
  bff session:      $(bff_session)
  backend log:      $(backend_log)
  frontend log:     $(frontend_log)
  bff log:          $(bff_log)
  startup writes:   --auraboot.bootstrap.enabled=false
PLAN
}

start_infra_from_existing_env() {
    local project_name="${COMPOSE_PROJECT_NAME:-auraboot-$SLUG}"
    local services=(postgres redis)
    local profiles=(--profile cache)
    if [ "$WITH_STORAGE" = "1" ]; then
        services+=(minio)
        profiles+=(--profile storage)
    fi

    if [ "$DRY_RUN" = "1" ]; then
        echo "docker compose -p $project_name -f docker-compose.yml -f docker-compose.isolated.yml ${profiles[*]} up -d ${services[*]}"
        return 0
    fi

    export COMPOSE_PROJECT_NAME="$project_name"
    export PG_PORT REDIS_PORT MINIO_API_PORT MINIO_CONSOLE_PORT
    docker compose \
        -p "$project_name" \
        -f docker-compose.yml \
        -f docker-compose.isolated.yml \
        "${profiles[@]}" \
        up -d "${services[@]}"
}

start_backend_host() {
    local backend_root
    backend_root="$(backend_root_for_product)"
    local log_file
    log_file="$(backend_log)"
    local session
    session="$(backend_session)"
    local core_export=""
    if [ "$PRODUCT" = "enterprise" ]; then
        core_export="AURA_CORE_PROJECT_ROOT='$(registered_core_root)'"
    fi
    local cmd
    cmd="cd '$backend_root' && $core_export SPRING_PROFILES_ACTIVE=dev PGHOST='$PG_HOST' PGPORT='$PG_PORT' PGDATABASE='$PG_DB' PGUSER='$PG_USER' PGPASSWORD='$PGPASSWORD' ./gradlew bootRun --no-daemon --args='--server.port=$BE_PORT --spring.datasource.url=jdbc:postgresql://$PG_HOST:$PG_PORT/$PG_DB --spring.datasource.username=$PG_USER --spring.datasource.password=$PGPASSWORD --spring.data.redis.host=127.0.0.1 --spring.data.redis.port=$REDIS_PORT --auraboot.bootstrap.enabled=false' >'$log_file' 2>&1"

    if [ "$DRY_RUN" = "1" ]; then
        echo "tmux new-session -d -s $session \"$cmd\""
        return 0
    fi

    tmux kill-session -t "$session" 2>/dev/null || true
    tmux new-session -d -s "$session" "$cmd"
    echo "started backend session: $session"
}

start_frontend_host() {
    local log_file
    log_file="$(frontend_log)"
    local session
    session="$(frontend_session)"
    local cmd
    local core_root
    core_root="$(registered_core_root)"
    cmd="cd '$core_root/web-admin' && SPRING_BOOT_URL='$BACKEND_URL' BACKEND_URL='$BACKEND_URL' PLAYWRIGHT_BASE_URL='$PLAYWRIGHT_BASE_URL' BFF_URL='$BFF_URL' BFF_PORT='$BFF_PORT' VITE_PORT='$VITE_PORT' NO_PROXY='localhost,127.0.0.1' pnpm sync-plugins && exec env SPRING_BOOT_URL='$BACKEND_URL' BACKEND_URL='$BACKEND_URL' PLAYWRIGHT_BASE_URL='$PLAYWRIGHT_BASE_URL' BFF_URL='$BFF_URL' BFF_PORT='$BFF_PORT' VITE_PORT='$VITE_PORT' NO_PROXY='localhost,127.0.0.1' ./node_modules/.bin/react-router dev >'$log_file' 2>&1"

    if [ "$DRY_RUN" = "1" ]; then
        echo "tmux new-session -d -s $session \"$cmd\""
        return 0
    fi

    tmux kill-session -t "$session" 2>/dev/null || true
    tmux new-session -d -s "$session" "$cmd"
    echo "started frontend session: $session"
}

start_bff_host() {
    local log_file
    log_file="$(bff_log)"
    local session
    session="$(bff_session)"
    local cmd
    local core_root
    core_root="$(registered_core_root)"
    cmd="cd '$core_root/web-admin' && exec env SPRING_BOOT_URL='$BACKEND_URL' BACKEND_URL='$BACKEND_URL' PLAYWRIGHT_BASE_URL='$PLAYWRIGHT_BASE_URL' BFF_URL='$BFF_URL' BFF_PORT='$BFF_PORT' VITE_PORT='$VITE_PORT' NO_PROXY='localhost,127.0.0.1' ./node_modules/.bin/tsx app/server/bff.server.ts >'$log_file' 2>&1"

    if [ "$DRY_RUN" = "1" ]; then
        echo "tmux new-session -d -s $session \"$cmd\""
        return 0
    fi

    tmux kill-session -t "$session" 2>/dev/null || true
    tmux new-session -d -s "$session" "$cmd"
    echo "started bff session: $session"
}

command_start() {
    if [ "$MODE" != "bugfix" ]; then
        echo "ERROR: start currently supports --mode=bugfix" >&2
        exit 2
    fi

    print_start_plan
    if [ -f "$(registry_exports_file)" ]; then
        load_bugfix_env
        start_infra_from_existing_env
    fi
    if [ "$DRY_RUN" = "1" ]; then
        echo ""
        echo "(dry-run mode: not starting docker or host processes)"
        return 0
    fi

    if [ ! -f "$(registry_exports_file)" ]; then
        local infra_args=(--slug="$SLUG" --product="$PRODUCT")
        [ "$WITH_STORAGE" = "1" ] && infra_args+=(--with-storage)
        local enterprise_root
        enterprise_root="$(resolve_enterprise_root || true)"
        env \
            AURA_ENV_REGISTRY_ROOT="$(registry_root)" \
            AURA_PRODUCT="$PRODUCT" \
            AURA_ENTERPRISE_ROOT="$enterprise_root" \
            "$SCRIPT_DIR/start-dev-infra.sh" "${infra_args[@]}"
        load_bugfix_env
    fi
    registry_upsert_current running >/dev/null
    start_backend_host
    start_frontend_host
    start_bff_host
}

command_status() {
    load_bugfix_env
    aura_env_to_json
    if command -v tmux >/dev/null 2>&1; then
        echo "tmux backend=$(tmux has-session -t "$(backend_session)" 2>/dev/null && echo running || echo stopped)"
        echo "tmux frontend=$(tmux has-session -t "$(frontend_session)" 2>/dev/null && echo running || echo stopped)"
        echo "tmux bff=$(tmux has-session -t "$(bff_session)" 2>/dev/null && echo running || echo stopped)"
    fi
    local be_pids vite_pids bff_pids
    be_pids="$(aura_dev_listen_pids_for_port "$BE_PORT" | tr '\n' ',' | sed 's/,$//')"
    vite_pids="$(aura_dev_listen_pids_for_port "$VITE_PORT" | tr '\n' ',' | sed 's/,$//')"
    bff_pids="$(aura_dev_listen_pids_for_port "$BFF_PORT" | tr '\n' ',' | sed 's/,$//')"
    echo "ports be=${be_pids:-none} vite=${vite_pids:-none} bff=${bff_pids:-none}"
}

command_reset() {
    if [ "$MODE" != "bugfix" ]; then
        echo "ERROR: reset currently supports --mode=bugfix" >&2
        exit 2
    fi
    load_bugfix_env
    local reset_script
    reset_script="$(reset_script_for_product)"
    local reset_label
    reset_label="${reset_script#$PROJECT_ROOT/}"

    cat <<PLAN
Bugfix reset plan
  product:     $PRODUCT
  slug:        $SLUG
  database:    $PG_HOST:$PG_PORT/$PG_DB
  reset:       $reset_label
  bootstrap:   POST $BACKEND_URL/api/bootstrap/setup
  cleanup:     no global pkill; use scripts/dev/env.sh stop --slug=$SLUG for exact host process cleanup
PLAN

    if [ "$DRY_RUN" = "1" ]; then
        echo "(dry-run mode: not resetting database or bootstrapping)"
        return 0
    fi

    if [ ! -f "$reset_script" ]; then
        echo "ERROR: reset script not found: $reset_script" >&2
        exit 1
    fi

    printf 'y\n' | env \
        COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-auraboot-$SLUG}" \
        AURA_ENV_PROFILE=r2 \
        PG_HOST="$PG_HOST" \
        PG_PORT="$PG_PORT" \
        PG_USER="$PG_USER" \
        PG_DB="$PG_DB" \
        PGPASSWORD="$PGPASSWORD" \
        "$reset_script"
}

command_stop() {
    load_bugfix_env
    echo "Bugfix stop plan"
    echo "  slug:             $SLUG"
    echo "  backend session:  $(backend_session)"
    echo "  frontend session: $(frontend_session)"
    echo "  bff session:      $(bff_session)"
    echo "  exact ports:      $BE_PORT $VITE_PORT $BFF_PORT"
    echo "  cleanup:          no global pkill"
    aura_dev_stop_tmux_session "$(backend_session)" "$DRY_RUN"
    aura_dev_stop_tmux_session "$(frontend_session)" "$DRY_RUN"
    aura_dev_stop_tmux_session "$(bff_session)" "$DRY_RUN"
    aura_dev_stop_web_processes_for_root "$DRY_RUN" "$(registered_core_root)/web-admin"
    aura_dev_stop_ports "$DRY_RUN" "$BE_PORT" "$VITE_PORT" "$BFF_PORT"

    if [ "$PURGE" = "1" ]; then
        local stop_args=(--slug="$SLUG" --purge)
        if [ "$DRY_RUN" = "1" ]; then
            echo "scripts/dev/stop-isolated.sh --slug=$SLUG --purge"
        else
            "$SCRIPT_DIR/stop-isolated.sh" "${stop_args[@]}"
        fi
    else
        if [ "$DRY_RUN" = "1" ]; then
            echo "scripts/dev/stop-isolated.sh --slug=$SLUG"
        else
            local stop_rc
            set +e
            "$SCRIPT_DIR/stop-isolated.sh" --slug="$SLUG"
            stop_rc=$?
            set -e
            if [ "$stop_rc" -eq 2 ]; then
                echo "docker infra not running for slug: $SLUG"
            elif [ "$stop_rc" -ne 0 ]; then
                exit "$stop_rc"
            fi
        fi
    fi

    if [ "$DRY_RUN" != "1" ]; then
        registry_upsert_current stopped >/dev/null
    fi
}

command_verify() {
    load_bugfix_env
    if [ "$LEVEL" != "health" ]; then
        echo "ERROR: verify currently supports --level=health" >&2
        exit 2
    fi
    if [ "$DRY_RUN" = "1" ]; then
        echo "Health targets"
        aura_dev_health_targets
        return 0
    fi
    local failed=0
    while read -r name url; do
        aura_dev_check_http "$name" "$url" || failed=1
    done < <(aura_dev_health_targets)
    return "$failed"
}

command_demo() {
    if [ "$MODE" != "bugfix" ]; then
        echo "ERROR: demo currently supports --mode=bugfix" >&2
        exit 2
    fi
    "$SCRIPT_DIR/prepare-bugfix-demo.sh" \
        --slug="$SLUG" \
        --product="$PRODUCT" \
        $([ "$DRY_RUN" = "1" ] && printf '%s' "--dry-run")
}

command_rebuild_demo() {
    if [ "$MODE" != "bugfix" ]; then
        echo "ERROR: rebuild-demo currently supports --mode=bugfix" >&2
        exit 2
    fi
    if [ "$PRODUCT" != "oss" ]; then
        echo "ERROR: rebuild-demo currently supports --product=oss only" >&2
        exit 2
    fi
    if [ "$DRY_RUN" = "1" ]; then
        command_start
        echo ""
        echo "Bugfix rebuild-demo dry-run"
        echo "  reset command: scripts/dev/env.sh reset --mode=$MODE --product=$PRODUCT --slug=$SLUG"
        command_demo
        return 0
    fi
    command_start
    command_reset
    command_demo
}

command_logs() {
    case "$SERVICE" in
        backend) echo "$(backend_log)" ;;
        frontend) echo "$(frontend_log)" ;;
        bff) echo "$(bff_log)" ;;
        docker) echo "docker compose -p auraboot-$SLUG logs" ;;
        all)
            echo "$(backend_log)"
            echo "$(frontend_log)"
            echo "$(bff_log)"
            echo "docker compose -p auraboot-$SLUG logs"
            ;;
        *) echo "ERROR: unknown service '$SERVICE' (expected backend|frontend|bff|docker|all)" >&2; exit 2 ;;
    esac
}

command_list() {
    node "$SCRIPT_DIR/lib/env-registry.mjs" list --registry-root "$(registry_root)"
}

command_inspect() {
    node "$SCRIPT_DIR/lib/env-registry.mjs" inspect --registry-root "$(registry_root)" --slug "$SLUG"
}

case "$COMMAND" in
    start) command_start ;;
    stop) command_stop ;;
    status) command_status ;;
    reset) command_reset ;;
    demo) command_demo ;;
    rebuild-demo) command_rebuild_demo ;;
    verify) command_verify ;;
    logs) command_logs ;;
    list) command_list ;;
    inspect) command_inspect ;;
    *) echo "ERROR: unknown command '$COMMAND'" >&2; usage; exit 2 ;;
esac
