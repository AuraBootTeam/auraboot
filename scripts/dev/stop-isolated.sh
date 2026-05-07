#!/usr/bin/env bash
#
# stop-isolated.sh — bring down a per-worktree isolated docker stack.
#
# Pairs with start-isolated.sh. Requires --slug=<name> identifying which
# stack to stop; reads ports / project info from .aura-stack/<slug>.env.
#
# Spec: docs/plans/2026-05/2026-05-07-docker-per-worktree-isolation-design.md
# (P0 #3)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

usage() {
    cat <<'EOF'
Usage: stop-isolated.sh --slug=<name> [--purge] [--help]

Required:
  --slug=<name>   Slug identifying which stack to stop. Reads
                  .aura-stack/<slug>.env for the COMPOSE_PROJECT_NAME and
                  per-stack ports.

Options:
  --purge         Also remove named volumes (postgres_data, redis_data,
                  backend_data, isolated_node_modules,
                  isolated_web_admin_node_modules) and delete
                  .aura-stack/<slug>.env. Use this when you want a
                  clean DB on the next `start-isolated.sh --slug=<name>`.
  --help          Show this message.
EOF
}

err() {
    printf 'ERROR: %s\n' "$*" >&2
}

info() {
    printf 'INFO:  %s\n' "$*" >&2
}

# ─── Parse args ──────────────────────────────────────────────────────────
SLUG=""
PURGE=0

for arg in "$@"; do
    case "$arg" in
        --slug=*)  SLUG="${arg#*=}" ;;
        --purge)   PURGE=1 ;;
        --help|-h) usage; exit 0 ;;
        *)
            err "unknown argument: $arg"
            usage >&2
            exit 2
            ;;
    esac
done

if [ -z "$SLUG" ]; then
    err "--slug=<name> is required."
    usage >&2
    exit 2
fi

ENV_FILE="${REPO_ROOT}/.aura-stack/${SLUG}.env"

if [ ! -f "$ENV_FILE" ]; then
    err "no env file at ${ENV_FILE}"
    err "  This stack was either never started via start-isolated.sh,"
    err "  or its env file was already purged."
    err "  Available stacks:"
    if [ -d "${REPO_ROOT}/.aura-stack" ]; then
        ls "${REPO_ROOT}/.aura-stack"/*.env 2>/dev/null \
            | sed 's|.*/||; s|\.env$||; s/^/    /' >&2 \
            || echo "    (none)" >&2
    else
        echo "    (.aura-stack directory missing)" >&2
    fi
    exit 3
fi

# Source for COMPOSE_PROJECT_NAME and ports.
# shellcheck source=/dev/null
set -a
source "$ENV_FILE"
set +a

PROJECT="${COMPOSE_PROJECT_NAME:-auraboot-${SLUG}}"

cd "$REPO_ROOT"

if [ "$PURGE" -eq 1 ]; then
    info "stopping '${PROJECT}' and removing volumes (purge)"
    COMPOSE_PROJECT_NAME="$PROJECT" \
        docker compose \
            -f docker-compose.yml \
            -f docker-compose.isolated.yml \
            --env-file "$ENV_FILE" \
            --profile isolated-stack \
            --profile cache \
            down -v
    rm -f "$ENV_FILE"
    info "removed ${ENV_FILE}"
else
    info "stopping '${PROJECT}' (volumes preserved)"
    COMPOSE_PROJECT_NAME="$PROJECT" \
        docker compose \
            -f docker-compose.yml \
            -f docker-compose.isolated.yml \
            --env-file "$ENV_FILE" \
            --profile isolated-stack \
            --profile cache \
            down
fi

cat <<EOF

Stack '${PROJECT}' stopped.

  Slug:    ${SLUG}
  Purge:   $( [ "$PURGE" -eq 1 ] && echo "yes (volumes + env file removed)" || echo "no (volumes preserved; env file kept at ${ENV_FILE})" )

Restart with:
  ./scripts/dev/start-isolated.sh --slug=${SLUG}
EOF
