#!/bin/bash
#
# Stop a per-worktree Docker dev stack.
#
# Implements P0 #3 of docs/plans/2026-05/2026-05-07-docker-per-worktree-isolation-design.md.
#
# Works for both full isolated stacks and infra-only daily-dev stacks.
# By default just stops containers (volumes preserved so the next start is fast);
# pass --purge to also drop named volumes (force-clean for slug reuse).
#
# Usage:
#   scripts/dev/stop-isolated.sh                       # auto-derive slug from current branch
#   scripts/dev/stop-isolated.sh --slug=my-poc         # explicit slug
#   scripts/dev/stop-isolated.sh --slug=my-poc --purge # also remove volumes
#
# Exit codes:
#   0  stack stopped
#   2  CLI usage error / slug not found / no running stack
#   3  docker compose down failed

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
STACK_DIR="$PROJECT_ROOT/.aura-stack"

SLUG=""
PURGE=0

usage() {
    cat <<USAGE
Usage: $0 [--slug=<name>] [--purge] [--help]

Options:
  --slug=<name>  Stack slug to stop (defaults to slug derived from current branch)
  --purge        Also drop named volumes (postgres data, node_modules cache)
  --help         Show this message
USAGE
}

for arg in "$@"; do
    case "$arg" in
        --slug=*) SLUG="${arg#--slug=}" ;;
        --purge) PURGE=1 ;;
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

if [ -z "$SLUG" ]; then
    echo "ERROR: could not derive slug — supply --slug=<name>" >&2
    exit 2
fi

PROJECT_NAME="auraboot-${SLUG}"

# If we have a stack env file, source it for completeness (mostly for visibility
# in logs; docker compose uses COMPOSE_PROJECT_NAME alone for matching).
STACK_ENV_FILE="$STACK_DIR/${SLUG}.env"
if [ -f "$STACK_ENV_FILE" ]; then
    # shellcheck disable=SC1090
    source "$STACK_ENV_FILE"
fi

cd "$PROJECT_ROOT"

# Verify the stack actually exists before attempting down (clearer error).
if ! docker compose -p "$PROJECT_NAME" ps --quiet 2>/dev/null | grep -q .; then
    echo "INFO: no running stack found for project '$PROJECT_NAME' (slug=$SLUG)." >&2
    if [ "$PURGE" = "1" ]; then
        # Even if no containers are up, we may still need to drop volumes.
        echo "INFO: --purge requested; attempting volume cleanup anyway." >&2
    else
        echo "      use 'scripts/dev/list-isolated.sh' to see what's running." >&2
        exit 2
    fi
fi

echo "Stopping stack '$PROJECT_NAME' (slug=$SLUG) …"

# `down` semantics:
#   - default: stop + remove containers + network (volumes preserved)
#   - --volumes: also drop named volumes the project owns
DOWN_FLAGS=""
if [ "$PURGE" = "1" ]; then
    DOWN_FLAGS="--volumes"
    echo "(--purge: named volumes will be removed)"
fi

# shellcheck disable=SC2086
if ! docker compose \
        -p "$PROJECT_NAME" \
        -f docker-compose.yml \
        -f docker-compose.isolated.yml \
        --profile isolated \
        --profile cache \
        --profile storage \
        --profile playwright-runner \
        --profile production-like \
        down $DOWN_FLAGS; then
    echo "ERROR: docker compose down failed." >&2
    exit 3
fi

# Stack env file no longer represents a live stack; remove for cleanliness.
# (Keep on non-purge so next start can re-use the same offset.)
if [ "$PURGE" = "1" ] && [ -f "$STACK_ENV_FILE" ]; then
    rm -f "$STACK_ENV_FILE"
    echo "Removed $STACK_ENV_FILE"
fi

echo "Stack '$PROJECT_NAME' stopped."
