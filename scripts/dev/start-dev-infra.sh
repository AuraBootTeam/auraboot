#!/bin/bash
#
# Start per-worktree infrastructure only for daily host-mode development.
#
# This is Mode A of the Docker environment split:
#   - Docker: Postgres / Redis / optional MinIO
#   - Host: Spring Boot / Vite / BFF, using the ports written to the
#     global env registry under .aura/envs/<slug>
#
# Use scripts/dev/start-isolated.sh for Mode B full Docker merge verification.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# shellcheck source=scripts/dev/lib/env-loader.sh
source "$SCRIPT_DIR/lib/env-loader.sh"

SLUG=""
EXPLICIT_OFFSET=""
PRODUCT="${AURA_PRODUCT:-oss}"
WITH_STORAGE=0
DRY_RUN=0

usage() {
    cat <<USAGE
Usage: $0 [--slug=<name>] [--product=oss|enterprise] [--offset=<N>] [--with-storage] [--dry-run] [--help]

Options:
  --slug=<name>    Override auto-derived slug (lowercase / dash separated, <= 24 chars)
  --product=<name> Product runtime label for the global env registry
  --offset=<N>     Skip auto-probing; force port offset N (1-89)
  --with-storage   Also start MinIO with isolated host ports
  --dry-run        Print resolved plan and exports, don't start Docker
  --help           Show this message

This script starts only infrastructure. Run backend and frontend on host with
the printed environment values.
USAGE
}

for arg in "$@"; do
    case "$arg" in
        --slug=*) SLUG="${arg#--slug=}" ;;
        --product=*) PRODUCT="${arg#--product=}" ;;
        --offset=*) EXPLICIT_OFFSET="${arg#--offset=}" ;;
        --with-storage) WITH_STORAGE=1 ;;
        --dry-run) DRY_RUN=1 ;;
        --help|-h) usage; exit 0 ;;
        *) echo "ERROR: unknown argument: $arg" >&2; usage; exit 2 ;;
    esac
done

normalize_slug() {
    local raw="$1"
    printf '%s' "$raw" \
        | tr '[:upper:]' '[:lower:]' \
        | tr '/_' '--' \
        | sed -E 's/[^a-z0-9-]/-/g; s/-+/-/g; s/^-//; s/-$//' \
        | cut -c1-24
}

if [ -z "$SLUG" ]; then
    branch="$(git -C "$PROJECT_ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || echo HEAD)"
    if [ "$branch" = "HEAD" ]; then
        branch="$(basename "$PROJECT_ROOT")"
    fi
    SLUG="$(normalize_slug "$branch")"
fi

if [ -z "$SLUG" ] || ! [[ "$SLUG" =~ ^[a-z0-9][a-z0-9-]{0,23}$ ]]; then
    echo "ERROR: invalid slug '$SLUG' (must match ^[a-z0-9][a-z0-9-]{0,23}\$)" >&2
    exit 2
fi

case "$PRODUCT" in
    oss|enterprise) ;;
    *) echo "ERROR: unknown product '$PRODUCT' (expected oss|enterprise)" >&2; exit 2 ;;
esac

PROJECT_NAME="auraboot-${SLUG}"
REGISTRY_ROOT="$(aura_env__registry_root "$PROJECT_ROOT")"
ENV_ROOT="$REGISTRY_ROOT/envs/$SLUG"
REGISTRY_EXPORTS_FILE="$ENV_ROOT/exports.env"
REGISTRY_MANIFEST_FILE="$ENV_ROOT/manifest.json"
AUTH_ROOT="$ENV_ROOT/auth"

BASE_PG=5433
BASE_BE=6444
BASE_VITE=5174
BASE_BFF=3501
BASE_REDIS=6479
BASE_MINIO_API=9002
BASE_MINIO_CONSOLE=9102

compute_initial_offset() {
    if [ -n "$EXPLICIT_OFFSET" ]; then
        echo "$EXPLICIT_OFFSET"
        return
    fi
    local hex
    hex="$(printf '%s' "$SLUG" | shasum -a 1 | cut -c1-8)"
    printf '%d\n' "$(( 16#$hex % 89 + 1 ))"
}

is_port_free() {
    local port="$1"
    ! lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1
}

is_registry_ports_free() {
    node "$SCRIPT_DIR/lib/env-registry.mjs" assert-ports-free \
        --registry-root "$REGISTRY_ROOT" \
        --slug "$SLUG" \
        --pg-port "$PG_PORT" \
        --redis-port "$REDIS_PORT" \
        --be-port "$BE_PORT" \
        --vite-port "$VITE_PORT" \
        --bff-port "$BFF_PORT" \
        --minio-api-port "$MINIO_API_PORT" \
        --minio-console-port "$MINIO_CONSOLE_PORT" >/dev/null
}

REGISTERED_ENV=0
if [ -f "$REGISTRY_EXPORTS_FILE" ] && [ -z "$EXPLICIT_OFFSET" ]; then
    # shellcheck disable=SC1090
    source "$REGISTRY_EXPORTS_FILE"
    PROJECT_NAME="${COMPOSE_PROJECT_NAME:-$PROJECT_NAME}"
    REGISTERED_ENV=1
else
    OFFSET=""
    ATTEMPTS=0
    MAX_ATTEMPTS=5
    candidate_offset="$(compute_initial_offset)"
    while [ "$ATTEMPTS" -lt "$MAX_ATTEMPTS" ]; do
        PG_PORT=$((BASE_PG + candidate_offset))
        BE_PORT=$((BASE_BE + candidate_offset))
        VITE_PORT=$((BASE_VITE + candidate_offset))
        BFF_PORT=$((BASE_BFF + candidate_offset))
        REDIS_PORT=$((BASE_REDIS + candidate_offset))
        MINIO_API_PORT=$((BASE_MINIO_API + candidate_offset))
        MINIO_CONSOLE_PORT=$((BASE_MINIO_CONSOLE + candidate_offset))

        if is_port_free "$PG_PORT" \
            && is_port_free "$BE_PORT" \
            && is_port_free "$VITE_PORT" \
            && is_port_free "$BFF_PORT" \
            && is_port_free "$REDIS_PORT" \
            && { [ "$WITH_STORAGE" != "1" ] || { is_port_free "$MINIO_API_PORT" && is_port_free "$MINIO_CONSOLE_PORT"; }; } \
            && is_registry_ports_free; then
            OFFSET="$candidate_offset"
            break
        fi

        ATTEMPTS=$((ATTEMPTS + 1))
        candidate_offset=$(( (candidate_offset % 89) + 1 ))
    done

    if [ -z "$OFFSET" ]; then
        echo "ERROR: failed to find free ports after $MAX_ATTEMPTS attempts." >&2
        exit 3
    fi
fi

PW_E2E_RUN_ID="${PW_E2E_RUN_ID:-$(date -u +"%Y%m%dT%H%M%SZ")}"
PW_E2E_RUN_ROOT="${PW_E2E_RUN_ROOT:-test-results/runs/$SLUG/$PW_E2E_RUN_ID}"
PW_ARTIFACT_DIR="${PW_ARTIFACT_DIR:-$PW_E2E_RUN_ROOT/artifacts}"
PW_REPORT_DIR="${PW_REPORT_DIR:-$PW_E2E_RUN_ROOT/html-report}"
PW_RESULTS_JSON="${PW_RESULTS_JSON:-$PW_E2E_RUN_ROOT/results.json}"
PW_STORAGE_DIR="${PW_STORAGE_DIR:-$AUTH_ROOT}"
PW_ADMIN_STORAGE_STATE="${PW_ADMIN_STORAGE_STATE:-$AUTH_ROOT/admin.json}"
PW_OPERATOR_STORAGE_STATE="${PW_OPERATOR_STORAGE_STATE:-$AUTH_ROOT/operator.json}"
PW_VIEWER_STORAGE_STATE="${PW_VIEWER_STORAGE_STATE:-$AUTH_ROOT/viewer.json}"

current_branch_for_root() {
    local root="$1"
    git -C "$root" branch --show-current 2>/dev/null || echo "unknown"
}

resolve_enterprise_root() {
    if [ -n "${AURA_ENTERPRISE_ROOT:-}" ]; then
        echo "$AURA_ENTERPRISE_ROOT"
    elif [ -d "$PROJECT_ROOT/../auraboot-enterprise" ]; then
        cd "$PROJECT_ROOT/../auraboot-enterprise" && pwd
    elif [ -d "/Users/ghj/work/auraboot/auraboot-enterprise" ]; then
        echo "/Users/ghj/work/auraboot/auraboot-enterprise"
    else
        echo ""
    fi
}

write_registry_env() {
    local enterprise_root=""
    local enterprise_branch=""
    if [ "$PRODUCT" = "enterprise" ]; then
        enterprise_root="$(resolve_enterprise_root)"
        if [ -n "$enterprise_root" ]; then
            enterprise_branch="$(current_branch_for_root "$enterprise_root")"
        fi
    fi

    local args=(
        "$SCRIPT_DIR/lib/env-registry.mjs" upsert
        --registry-root "$REGISTRY_ROOT"
        --slug "$SLUG"
        --mode bugfix
        --product "$PRODUCT"
        --core-root "$PROJECT_ROOT"
        --core-branch "$(current_branch_for_root "$PROJECT_ROOT")"
        --compose-project "$PROJECT_NAME"
        --status running
        --pg-port "$PG_PORT"
        --redis-port "$REDIS_PORT"
        --be-port "$BE_PORT"
        --vite-port "$VITE_PORT"
        --bff-port "$BFF_PORT"
    )
    if [ -n "${OFFSET:-}" ]; then
        args+=(--offset "$OFFSET")
    fi
    if [ "$WITH_STORAGE" = "1" ]; then
        args+=(--minio-api-port "$MINIO_API_PORT" --minio-console-port "$MINIO_CONSOLE_PORT")
    fi
    if [ -n "$enterprise_root" ]; then
        args+=(--enterprise-root "$enterprise_root")
    fi
    if [ -n "$enterprise_branch" ]; then
        args+=(--enterprise-branch "$enterprise_branch")
    fi

    node "${args[@]}"
}

cat <<SUMMARY
Infra-only stack plan
  slug:           $SLUG
  project name:   $PROJECT_NAME
  postgres:       localhost:$PG_PORT
  redis:          localhost:$REDIS_PORT
  backend host:   http://localhost:$BE_PORT
  vite host:      http://localhost:$VITE_PORT
  bff host:       http://localhost:$BFF_PORT
  e2e artifacts:  web-admin/$PW_E2E_RUN_ROOT
  auth storage:    $AUTH_ROOT
  minio api:      $([ "$WITH_STORAGE" = "1" ] && echo "localhost:$MINIO_API_PORT" || echo "disabled")
  minio console:  $([ "$WITH_STORAGE" = "1" ] && echo "localhost:$MINIO_CONSOLE_PORT" || echo "disabled")
  registry:       $REGISTRY_MANIFEST_FILE
  exports:        $REGISTRY_EXPORTS_FILE
  reused:         $([ "$REGISTERED_ENV" = "1" ] && echo "yes" || echo "no")

Host app exports:
  source scripts/dev/r2-env-export.sh $SLUG

SUMMARY

if [ "$DRY_RUN" = "1" ]; then
    echo "(dry-run mode: not starting docker)"
    exit 0
fi

export COMPOSE_PROJECT_NAME="$PROJECT_NAME"
export PG_PORT REDIS_PORT MINIO_API_PORT MINIO_CONSOLE_PORT

cd "$PROJECT_ROOT"

SERVICES=(postgres redis)
PROFILES=(--profile cache)
if [ "$WITH_STORAGE" = "1" ]; then
    SERVICES+=(minio)
    PROFILES+=(--profile storage)
fi

docker compose \
    -f docker-compose.yml \
    -f docker-compose.isolated.yml \
    "${PROFILES[@]}" \
    up -d "${SERVICES[@]}"

write_registry_env

echo ""
echo "Infra stack '$PROJECT_NAME' starting. Stop with:"
echo "  docker compose -p $PROJECT_NAME -f docker-compose.yml -f docker-compose.isolated.yml --profile cache down"
