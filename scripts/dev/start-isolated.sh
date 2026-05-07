#!/usr/bin/env bash
#
# start-isolated.sh — bring up a per-worktree isolated docker stack.
#
# Each worktree gets its own COMPOSE_PROJECT_NAME=auraboot-<slug> and a
# port offset so that two stacks can run in parallel on the same host
# without colliding on Postgres / backend / vite / BFF / Redis.
#
# Spec: docs/plans/2026-05/2026-05-07-docker-per-worktree-isolation-design.md
# (P0 #2)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

usage() {
    cat <<'EOF'
Usage: start-isolated.sh [--slug=<name>] [--offset=<N>] [--help]

Options:
  --slug=<name>    Override slug (default: derived from current branch).
                   Normalized to [a-z0-9-], truncated to 24 chars.
  --offset=<N>     Override port offset 1..89 (default: derived from
                   sha1(slug) % 89 + 1).
  --help           Show this message.

Environment:
  COMPOSE_PROJECT_NAME — overridden by this script to auraboot-<slug>.

Ports computed as:
  PG_PORT     = 5433 + offset
  BE_PORT     = 6444 + offset
  VITE_PORT   = 5174 + offset
  BFF_PORT    = 3501 + offset
  REDIS_PORT  = 6479 + offset

The script writes .aura-stack/<slug>.env (per-developer state, gitignored)
recording the port assignments and creation timestamp. Subsequent
stop-isolated.sh / list-isolated.sh read from this file.

Profiles activated: isolated-stack (postgres + backend + isolated-frontend),
cache (redis). The base file's `full` profile is intentionally NOT
activated because its `frontend` service has a hardcoded container_name
that would collide across stacks; isolated-frontend is the per-worktree
substitute.
EOF
}

err() {
    printf 'ERROR: %s\n' "$*" >&2
}

info() {
    printf 'INFO:  %s\n' "$*" >&2
}

normalize_slug() {
    # Lowercase; map / and _ to -; strip non-[a-z0-9-]; collapse runs of -;
    # trim leading/trailing -; truncate to 24 chars.
    local raw="$1"
    printf '%s' "$raw" \
        | tr '[:upper:]' '[:lower:]' \
        | tr '/_' '--' \
        | sed -E 's/[^a-z0-9-]//g; s/-+/-/g; s/^-//; s/-$//' \
        | cut -c1-24
}

derive_slug() {
    local branch
    branch="$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || echo "HEAD")"
    if [ "$branch" = "HEAD" ] || [ -z "$branch" ]; then
        # Detached HEAD — fall back to worktree path basename.
        branch="$(basename "$REPO_ROOT")"
    fi
    normalize_slug "$branch"
}

derive_offset() {
    local slug="$1"
    local hex
    hex="$(printf '%s' "$slug" | shasum | head -c 8)"
    # Convert hex to decimal then mod 89, +1 → range 1..89.
    local dec
    dec="$(printf '%d' "0x${hex}")"
    echo "$(( dec % 89 + 1 ))"
}

port_in_use() {
    local port="$1"
    if command -v lsof >/dev/null 2>&1; then
        lsof -ti ":${port}" -sTCP:LISTEN >/dev/null 2>&1
    elif command -v nc >/dev/null 2>&1; then
        nc -z -w 1 localhost "${port}" >/dev/null 2>&1
    else
        # No probe available — assume free, let docker fail loud later.
        return 1
    fi
}

# ─── Parse args ──────────────────────────────────────────────────────────
SLUG_OVERRIDE=""
OFFSET_OVERRIDE=""

for arg in "$@"; do
    case "$arg" in
        --slug=*)   SLUG_OVERRIDE="${arg#*=}" ;;
        --offset=*) OFFSET_OVERRIDE="${arg#*=}" ;;
        --help|-h)  usage; exit 0 ;;
        *)
            err "unknown argument: $arg"
            usage >&2
            exit 2
            ;;
    esac
done

# ─── Derive slug ─────────────────────────────────────────────────────────
if [ -n "$SLUG_OVERRIDE" ]; then
    SLUG="$(normalize_slug "$SLUG_OVERRIDE")"
else
    SLUG="$(derive_slug)"
fi

if [ -z "$SLUG" ]; then
    err "could not derive a non-empty slug — pass --slug=<name>."
    exit 2
fi

# ─── Derive offset ───────────────────────────────────────────────────────
if [ -n "$OFFSET_OVERRIDE" ]; then
    OFFSET="$OFFSET_OVERRIDE"
    if ! [[ "$OFFSET" =~ ^[0-9]+$ ]] || [ "$OFFSET" -lt 0 ] || [ "$OFFSET" -gt 89 ]; then
        err "offset must be an integer in 0..89, got: $OFFSET"
        exit 2
    fi
else
    OFFSET="$(derive_offset "$SLUG")"
fi

# ─── Compute ports ───────────────────────────────────────────────────────
PG_PORT=$(( 5433 + OFFSET ))
BE_PORT=$(( 6444 + OFFSET ))
VITE_PORT=$(( 5174 + OFFSET ))
BFF_PORT=$(( 3501 + OFFSET ))
REDIS_PORT=$(( 6479 + OFFSET ))

PROJECT="auraboot-${SLUG}"

# ─── Pre-flight: existing stack? ────────────────────────────────────────
if docker compose ls --filter "name=${PROJECT}" --format json 2>/dev/null \
        | grep -q "\"Name\":\"${PROJECT}\""; then
    err "compose stack '${PROJECT}' is already running."
    err "  slug=${SLUG} offset=${OFFSET}"
    err "  Stop it first: ./scripts/dev/stop-isolated.sh --slug=${SLUG}"
    exit 3
fi

# ─── Pre-flight: ports free? ────────────────────────────────────────────
declare -a held=()
for entry in "PG_PORT=${PG_PORT}" "BE_PORT=${BE_PORT}" "VITE_PORT=${VITE_PORT}" "BFF_PORT=${BFF_PORT}" "REDIS_PORT=${REDIS_PORT}"; do
    name="${entry%%=*}"
    port="${entry##*=}"
    if port_in_use "$port"; then
        held+=("${name}=${port}")
    fi
done

if [ "${#held[@]}" -gt 0 ]; then
    err "refusing to start — these ports are already in use:"
    for h in "${held[@]}"; do err "  ${h}"; done
    err "  slug=${SLUG} offset=${OFFSET}"
    err "  Pick a different offset: ./scripts/dev/start-isolated.sh --slug=${SLUG} --offset=<N>"
    exit 4
fi

# ─── Write env file ─────────────────────────────────────────────────────
ENV_DIR="${REPO_ROOT}/.aura-stack"
ENV_FILE="${ENV_DIR}/${SLUG}.env"
mkdir -p "$ENV_DIR"

CREATED_AT="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"

cat > "$ENV_FILE" <<EOF
# AuraBoot per-worktree isolated stack — generated by start-isolated.sh
# Slug:    ${SLUG}
# Offset:  ${OFFSET}
# Created: ${CREATED_AT}
# Project: ${PROJECT}
#
# Used as compose --env-file. Do not edit manually; regenerate via
# stop-isolated.sh --slug=${SLUG} --purge && start-isolated.sh --slug=${SLUG}.

COMPOSE_PROJECT_NAME=${PROJECT}
AURA_STACK_SLUG=${SLUG}
AURA_STACK_OFFSET=${OFFSET}
AURA_STACK_CREATED_AT=${CREATED_AT}

PG_PORT=${PG_PORT}
BE_PORT=${BE_PORT}
VITE_PORT=${VITE_PORT}
BFF_PORT=${BFF_PORT}
REDIS_PORT=${REDIS_PORT}
EOF

info "wrote ${ENV_FILE}"

# ─── Bring up the stack ─────────────────────────────────────────────────
info "starting stack '${PROJECT}' (offset=${OFFSET})"
info "  postgres ${PG_PORT}, backend ${BE_PORT}, vite ${VITE_PORT}, BFF ${BFF_PORT}, redis ${REDIS_PORT}"
info "first boot can take 5+ minutes (gradle build + pnpm install in container)"

cd "$REPO_ROOT"

COMPOSE_PROJECT_NAME="$PROJECT" \
    docker compose \
        -f docker-compose.yml \
        -f docker-compose.isolated.yml \
        --env-file "$ENV_FILE" \
        --profile isolated-stack \
        --profile cache \
        up -d

cat <<EOF

Stack '${PROJECT}' started.

  Slug:        ${SLUG}
  Offset:      ${OFFSET}
  Postgres:    localhost:${PG_PORT}
  Backend:     http://localhost:${BE_PORT}
  Vite:        http://localhost:${VITE_PORT}
  BFF:         http://localhost:${BFF_PORT}
  Redis:       localhost:${REDIS_PORT}
  Health:      curl -sf http://localhost:${BE_PORT}/actuator/health
  Logs:        COMPOSE_PROJECT_NAME=${PROJECT} docker compose -f docker-compose.yml -f docker-compose.isolated.yml --env-file ${ENV_FILE} logs -f
  Stop:        ./scripts/dev/stop-isolated.sh --slug=${SLUG}
  Stop+purge:  ./scripts/dev/stop-isolated.sh --slug=${SLUG} --purge
EOF
