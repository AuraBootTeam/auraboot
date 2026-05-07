#!/bin/bash
#
# Start a per-worktree isolated docker dev stack.
#
# Implements P0 #2 of docs/plans/2026-05/2026-05-07-docker-per-worktree-isolation-design.md.
#
# Each invocation:
#   1. Derives a slug (per-worktree namespace) from current branch name
#      (or --slug=<name> override). Normalized to docker-compose-safe form.
#   2. Derives a port offset (1-89) via SHA1 hash of the slug, then probes
#      the resulting host ports for availability. If any are taken, walks
#      forward through the offset space until a free run is found.
#   3. Persists the chosen port assignments to .aura-stack/${slug}.env so
#      stop-isolated.sh / list-isolated.sh see consistent values across
#      runs.
#   4. Brings the stack up with `docker compose --profile isolated --profile cache up -d`
#      under COMPOSE_PROJECT_NAME=auraboot-${slug}.
#
# Usage:
#   scripts/dev/start-isolated.sh                     # auto from current branch
#   scripts/dev/start-isolated.sh --slug=my-poc       # explicit slug
#   scripts/dev/start-isolated.sh --offset=5          # explicit offset (skips probe)
#   scripts/dev/start-isolated.sh --no-build          # reuse existing built backend image
#   scripts/dev/start-isolated.sh --dry-run           # print plan, don't start
#
# Exit codes:
#   0  stack started successfully (or --dry-run printed plan)
#   2  CLI usage error
#   3  failed to find a usable port offset within 5 attempts
#   4  docker compose up failed

set -euo pipefail

# ---------- locate repo root ----------

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
STACK_DIR="$PROJECT_ROOT/.aura-stack"

# ---------- argument parsing ----------

SLUG=""
EXPLICIT_OFFSET=""
COMPOSE_BUILD_FLAG="--build"
DRY_RUN=0

usage() {
    cat <<USAGE
Usage: $0 [--slug=<name>] [--offset=<N>] [--no-build] [--dry-run] [--help]

Options:
  --slug=<name>    Override auto-derived slug (lowercase / dash separated, ≤ 24 chars)
  --offset=<N>     Skip auto-probing; force port offset N (1-89)
  --no-build       Don't rebuild backend image (faster restart)
  --dry-run        Print resolved plan and exit without starting docker
  --help           Show this message
USAGE
}

for arg in "$@"; do
    case "$arg" in
        --slug=*) SLUG="${arg#--slug=}" ;;
        --offset=*) EXPLICIT_OFFSET="${arg#--offset=}" ;;
        --no-build) COMPOSE_BUILD_FLAG="" ;;
        --dry-run) DRY_RUN=1 ;;
        --help|-h) usage; exit 0 ;;
        *) echo "ERROR: unknown argument: $arg" >&2; usage; exit 2 ;;
    esac
done

# ---------- slug derivation ----------

normalize_slug() {
    local raw="$1"
    # lowercase, replace / and _ with -, strip non [a-z0-9-], collapse runs of -, trim
    printf '%s' "$raw" \
        | tr '[:upper:]' '[:lower:]' \
        | tr '/_' '--' \
        | sed -E 's/[^a-z0-9-]/-/g; s/-+/-/g; s/^-//; s/-$//' \
        | cut -c1-24
}

if [ -z "$SLUG" ]; then
    branch="$(git -C "$PROJECT_ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || echo HEAD)"
    if [ "$branch" = "HEAD" ]; then
        # detached: fall back to worktree path basename
        branch="$(basename "$PROJECT_ROOT")"
    fi
    SLUG="$(normalize_slug "$branch")"
    if [ -z "$SLUG" ]; then
        echo "ERROR: could not derive slug from branch '$branch' — supply --slug=<name>" >&2
        exit 2
    fi
fi

if [ -z "$SLUG" ] || ! [[ "$SLUG" =~ ^[a-z0-9][a-z0-9-]{0,23}$ ]]; then
    echo "ERROR: invalid slug '$SLUG' (must match ^[a-z0-9][a-z0-9-]{0,23}\$)" >&2
    exit 2
fi

PROJECT_NAME="auraboot-${SLUG}"

# ---------- offset / port computation ----------

# Base host ports (slug=ga-e2e takes offset 0 by historical convention).
BASE_PG=5433
BASE_BE=6444
BASE_VITE=5174
BASE_BFF=3501
BASE_REDIS=6479

compute_initial_offset() {
    if [ "$SLUG" = "ga-e2e" ]; then
        echo 0
        return
    fi
    if [ -n "$EXPLICIT_OFFSET" ]; then
        echo "$EXPLICIT_OFFSET"
        return
    fi
    # SHA1 of slug, hex → first 8 chars → int → mod 89 → +1 (1..89 range).
    local hex
    hex="$(printf '%s' "$SLUG" | shasum -a 1 | cut -c1-8)"
    # Convert hex to decimal portably.
    printf '%d\n' "$(( 16#$hex % 89 + 1 ))"
}

is_port_free() {
    # Returns 0 if no listener on TCP $1 on localhost.
    local port="$1"
    if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
        return 1
    fi
    return 0
}

# Try up to 5 successive offsets if the first collides with an existing listener.
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

    if is_port_free "$PG_PORT" \
        && is_port_free "$BE_PORT" \
        && is_port_free "$VITE_PORT" \
        && is_port_free "$BFF_PORT" \
        && is_port_free "$REDIS_PORT"; then
        OFFSET="$candidate_offset"
        break
    fi

    ATTEMPTS=$((ATTEMPTS + 1))
    candidate_offset=$(( (candidate_offset % 89) + 1 ))  # walk forward in [1..89]
done

if [ -z "$OFFSET" ]; then
    echo "ERROR: failed to find free ports after $MAX_ATTEMPTS attempts." >&2
    echo "       last try: PG=$PG_PORT BE=$BE_PORT VITE=$VITE_PORT BFF=$BFF_PORT REDIS=$REDIS_PORT" >&2
    echo "       run scripts/dev/list-isolated.sh to see which stacks are up." >&2
    exit 3
fi

# ---------- persist port assignments ----------

mkdir -p "$STACK_DIR"
STACK_ENV_FILE="$STACK_DIR/${SLUG}.env"
cat > "$STACK_ENV_FILE" <<ENV
# Generated by scripts/dev/start-isolated.sh on $(date -u +"%Y-%m-%dT%H:%M:%SZ")
# Do not edit manually; re-run start-isolated.sh to refresh.
COMPOSE_PROJECT_NAME=$PROJECT_NAME
SLUG=$SLUG
OFFSET=$OFFSET
PG_PORT=$PG_PORT
BE_PORT=$BE_PORT
VITE_PORT=$VITE_PORT
BFF_PORT=$BFF_PORT
REDIS_PORT=$REDIS_PORT
ENV

# ---------- summary ----------

cat <<SUMMARY
Isolated stack plan
  slug:           $SLUG
  project name:   $PROJECT_NAME
  offset:         $OFFSET (resolved after $ATTEMPTS retry/retries)
  postgres:       localhost:$PG_PORT
  backend:        localhost:$BE_PORT
  vite (browser): http://localhost:$VITE_PORT
  bff:            http://localhost:$BFF_PORT
  redis:          localhost:$REDIS_PORT
  env file:       $STACK_ENV_FILE

SUMMARY

if [ "$DRY_RUN" = "1" ]; then
    echo "(dry-run mode: not starting docker)"
    exit 0
fi

# ---------- compose up ----------

# Export so docker compose substitutes them into docker-compose.isolated.yml.
export COMPOSE_PROJECT_NAME="$PROJECT_NAME"
export PG_PORT BE_PORT VITE_PORT BFF_PORT REDIS_PORT
export AURABOOT_BFF_ALLOWED_PORTS="$BFF_PORT,$VITE_PORT"

cd "$PROJECT_ROOT"

echo "Bringing stack up …"
# `--profile cache` includes redis (in base file); `--profile isolated`
# includes the backend + isolated-frontend services from the override.
# shellcheck disable=SC2086
if ! docker compose \
        -f docker-compose.yml \
        -f docker-compose.isolated.yml \
        --profile isolated \
        --profile cache \
        up -d $COMPOSE_BUILD_FLAG; then
    echo "ERROR: docker compose up failed." >&2
    exit 4
fi

echo ""
echo "Stack '$PROJECT_NAME' starting. Use these to interact:"
echo "  logs:    docker compose -p $PROJECT_NAME logs -f"
echo "  stop:    scripts/dev/stop-isolated.sh --slug=$SLUG"
echo "  vite:    http://localhost:$VITE_PORT"
echo ""
