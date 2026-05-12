#!/bin/bash
#
# Start the B2 production-like frontend surface for an existing isolated stack.
#
# Expected flow:
#   scripts/dev/start-isolated.sh --slug=<slug> --wait --rebuild
#   scripts/dev/start-production-like.sh --slug=<slug> --wait

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
STACK_DIR="$PROJECT_ROOT/.aura-stack"

SLUG=""
REBUILD=0
WAIT_FOR_HEALTH=0
DRY_RUN=0

usage() {
    cat <<USAGE
Usage: $0 --slug=<name> [--rebuild] [--wait] [--dry-run] [--help]

Options:
  --slug=<name>  Existing isolated stack slug.
  --rebuild      Rebuild web-admin/Dockerfile before starting.
  --wait         Wait for the production-like frontend health endpoint.
  --dry-run      Print the compose command without starting Docker.
  --help         Show this message.

This script only starts isolated-prod-frontend. It requires an existing
.aura-stack/<slug>.env created by start-isolated.sh.
USAGE
}

normalize_slug() {
    local raw="$1"
    printf '%s' "$raw" \
        | tr '[:upper:]' '[:lower:]' \
        | tr '/_' '--' \
        | sed -E 's/[^a-z0-9-]/-/g; s/-+/-/g; s/^-//; s/-$//' \
        | cut -c1-24
}

for arg in "$@"; do
    case "$arg" in
        --slug=*) SLUG="$(normalize_slug "${arg#--slug=}")" ;;
        --rebuild) REBUILD=1 ;;
        --wait) WAIT_FOR_HEALTH=1 ;;
        --dry-run) DRY_RUN=1 ;;
        --help|-h) usage; exit 0 ;;
        *) echo "ERROR: unknown argument: $arg" >&2; usage; exit 2 ;;
    esac
done

if [ -z "$SLUG" ]; then
    echo "ERROR: --slug=<name> is required" >&2
    usage
    exit 2
fi

STACK_ENV_FILE="$STACK_DIR/${SLUG}.env"
if [ ! -f "$STACK_ENV_FILE" ]; then
    echo "ERROR: missing $STACK_ENV_FILE; start the isolated stack first." >&2
    exit 2
fi

# shellcheck disable=SC1090
source "$STACK_ENV_FILE"

if [ "${STACK_MODE:-}" != "full" ]; then
    echo "ERROR: $STACK_ENV_FILE is STACK_MODE=${STACK_MODE:-unset}; production-like frontend requires a full isolated stack." >&2
    exit 2
fi

PROJECT_NAME="${COMPOSE_PROJECT_NAME:-auraboot-${SLUG}}"
PROD_FRONTEND_PORT="${PROD_FRONTEND_PORT:-$((3001 + ${OFFSET:-0}))}"
BUILD_FLAG="--no-build"
if [ "$REBUILD" = "1" ]; then
    BUILD_FLAG="--build"
fi

CMD=(
    docker compose
    -p "$PROJECT_NAME"
    -f docker-compose.yml
    -f docker-compose.isolated.yml
    --profile isolated
    --profile cache
    --profile production-like
    up -d
    "$BUILD_FLAG"
    isolated-prod-frontend
)

echo "Production-like frontend plan"
echo "  slug:          $SLUG"
echo "  project:       $PROJECT_NAME"
echo "  endpoint:      http://localhost:$PROD_FRONTEND_PORT"
echo "  rebuild:       $([ "$REBUILD" = "1" ] && echo yes || echo no)"
echo ""
echo "+ ${CMD[*]}"

if [ "$DRY_RUN" = "1" ]; then
    echo "(dry-run mode: not starting docker)"
    exit 0
fi

cd "$PROJECT_ROOT"
export COMPOSE_PROJECT_NAME="$PROJECT_NAME"
export PG_PORT BE_PORT VITE_PORT BFF_PORT PROD_FRONTEND_PORT REDIS_PORT
export ENTERPRISE_PLUGINS_DIR="${ENTERPRISE_PLUGINS_DIR:-$PROJECT_ROOT/.aura-stack/empty-enterprise-plugins}"
export ENTERPRISE_PLUGIN_JARS_DIR="${ENTERPRISE_PLUGIN_JARS_DIR:-$PROJECT_ROOT/.aura-stack/empty-enterprise-plugin-jars}"

"${CMD[@]}"

if [ "$WAIT_FOR_HEALTH" = "1" ]; then
    echo "Waiting for production-like frontend: http://localhost:$PROD_FRONTEND_PORT"
    for _ in $(seq 1 60); do
        if curl -fsS "http://localhost:$PROD_FRONTEND_PORT/health" >/dev/null 2>&1 \
            || curl -fsS "http://localhost:$PROD_FRONTEND_PORT/" >/dev/null 2>&1; then
            echo "Production-like frontend is responding."
            exit 0
        fi
        sleep 5
    done
    echo "ERROR: production-like frontend did not become healthy." >&2
    docker compose -p "$PROJECT_NAME" -f docker-compose.yml -f docker-compose.isolated.yml logs --tail 160 isolated-prod-frontend || true
    exit 4
fi

echo "Production-like frontend started: http://localhost:$PROD_FRONTEND_PORT"
