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
#   scripts/dev/start-isolated.sh --rebuild           # force rebuild of backend image
#   scripts/dev/start-isolated.sh --no-build          # (deprecated alias for default; kept for back-compat)
#   scripts/dev/start-isolated.sh --quiet-build       # rebuild backend with quieter build output
#   scripts/dev/start-isolated.sh --wait              # wait for backend + frontend health before returning
#   scripts/dev/start-isolated.sh --skip-pull         # skip pre-pulling third-party images
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
# 2026-05-08 §1C: default is now --no-build (faster). Pass --rebuild to opt
# in to a rebuild when you've changed Dockerfile / build.gradle / source.
COMPOSE_BUILD_FLAG=""
QUIET_BUILD=0
WAIT_FOR_HEALTH=0
SKIP_PULL=0
DRY_RUN=0
IMAGE_PULL_TIMEOUT_SECONDS="${AURA_IMAGE_PULL_TIMEOUT_SECONDS:-900}"
MIN_DOCKER_FREE_MB="${AURA_MIN_DOCKER_FREE_MB:-2048}"
SKIP_DOCKER_DISK_CHECK="${AURA_SKIP_DOCKER_DISK_CHECK:-0}"
AURA_CACHE_ROOT="${AURA_CACHE_ROOT:-$HOME/.cache/auraboot}"
AURA_CONTAINER_CACHE_KEY="${AURA_CONTAINER_CACHE_KEY:-linux}"
AURA_CONTAINER_CACHE_ROOT="${AURA_CONTAINER_CACHE_ROOT:-$AURA_CACHE_ROOT/container-$AURA_CONTAINER_CACHE_KEY}"

Default behaviour (since 2026-05-08): --no-build. Full stacks share host-backed
dependency caches under AURA_CACHE_ROOT, keeping warm cache out of the Docker
VM. Pass --rebuild to force a fresh image when Dockerfile, build.gradle, or
backend code changed.
USAGE
}

for arg in "$@"; do
    case "$arg" in
        --slug=*) SLUG="${arg#--slug=}" ;;
        --offset=*) EXPLICIT_OFFSET="${arg#--offset=}" ;;
        --rebuild) COMPOSE_BUILD_FLAG="--build" ;;
        --no-build) COMPOSE_BUILD_FLAG="" ;;  # deprecated alias for default
        --quiet-build) QUIET_BUILD=1 ;;
        --wait) WAIT_FOR_HEALTH=1 ;;
        --skip-pull) SKIP_PULL=1 ;;
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
BASE_PROD_FRONTEND=3001

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
    PROD_FRONTEND_PORT=$((BASE_PROD_FRONTEND + candidate_offset))

    if is_port_free "$PG_PORT" \
        && is_port_free "$BE_PORT" \
        && is_port_free "$VITE_PORT" \
        && is_port_free "$BFF_PORT" \
        && is_port_free "$REDIS_PORT" \
        && is_port_free "$PROD_FRONTEND_PORT"; then
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

# ---------- enterprise plugin mount ----------

resolve_enterprise_plugins_dir() {
    if [ -n "${ENTERPRISE_PLUGINS_DIR:-}" ]; then
        if [ -d "$ENTERPRISE_PLUGINS_DIR" ]; then
            (cd "$ENTERPRISE_PLUGINS_DIR" && pwd)
            return
        fi
        echo "ERROR: ENTERPRISE_PLUGINS_DIR is set but not a directory: $ENTERPRISE_PLUGINS_DIR" >&2
        exit 2
    fi

    mkdir -p "$STACK_DIR/empty-enterprise-plugins"
    (cd "$STACK_DIR/empty-enterprise-plugins" && pwd)
}

ENTERPRISE_PLUGINS_DIR="$(resolve_enterprise_plugins_dir)"

resolve_enterprise_plugin_jars_dir() {
    if [ -n "${ENTERPRISE_PLUGIN_JARS_DIR:-}" ]; then
        if [ -d "$ENTERPRISE_PLUGIN_JARS_DIR" ]; then
            (cd "$ENTERPRISE_PLUGIN_JARS_DIR" && pwd)
            return
        fi
        echo "ERROR: ENTERPRISE_PLUGIN_JARS_DIR is set but not a directory: $ENTERPRISE_PLUGIN_JARS_DIR" >&2
        exit 2
    fi

    mkdir -p "$STACK_DIR/empty-enterprise-plugin-jars"
    (cd "$STACK_DIR/empty-enterprise-plugin-jars" && pwd)
}

ENTERPRISE_PLUGIN_JARS_DIR="$(resolve_enterprise_plugin_jars_dir)"

STACK_ENV_FILE="$STACK_DIR/${SLUG}.env"
PW_E2E_RUN_ID="${PW_E2E_RUN_ID:-$(date -u +"%Y%m%dT%H%M%SZ")}"
PW_E2E_RUN_ROOT="${PW_E2E_RUN_ROOT:-test-results/runs/$SLUG/$PW_E2E_RUN_ID}"
PW_ARTIFACT_DIR="${PW_ARTIFACT_DIR:-$PW_E2E_RUN_ROOT/artifacts}"
PW_REPORT_DIR="${PW_REPORT_DIR:-$PW_E2E_RUN_ROOT/html-report}"
PW_RESULTS_JSON="${PW_RESULTS_JSON:-$PW_E2E_RUN_ROOT/results.json}"
PW_STORAGE_DIR="${PW_STORAGE_DIR:-tests/storage/$SLUG/$PW_E2E_RUN_ID}"

write_stack_env() {
    mkdir -p "$STACK_DIR"
    cat > "$STACK_ENV_FILE" <<ENV
# Generated by scripts/dev/start-isolated.sh on $(date -u +"%Y-%m-%dT%H:%M:%SZ")
# Do not edit manually; re-run start-isolated.sh to refresh.
COMPOSE_PROJECT_NAME=$PROJECT_NAME
STACK_MODE=full
SLUG=$SLUG
OFFSET=$OFFSET
PG_PORT=$PG_PORT
BE_PORT=$BE_PORT
VITE_PORT=$VITE_PORT
BFF_PORT=$BFF_PORT
PROD_FRONTEND_PORT=$PROD_FRONTEND_PORT
REDIS_PORT=$REDIS_PORT
MINIO_API_PORT=$((9002 + OFFSET))
MINIO_CONSOLE_PORT=$((9102 + OFFSET))
ENTERPRISE_PLUGINS_DIR=$ENTERPRISE_PLUGINS_DIR
ENTERPRISE_PLUGIN_JARS_DIR=$ENTERPRISE_PLUGIN_JARS_DIR
AURA_CACHE_ROOT=$AURA_CACHE_ROOT
AURA_CONTAINER_CACHE_KEY=$AURA_CONTAINER_CACHE_KEY
AURA_CONTAINER_CACHE_ROOT=$AURA_CONTAINER_CACHE_ROOT
PW_E2E_RUN_ID=$PW_E2E_RUN_ID
PW_E2E_RUN_ROOT=$PW_E2E_RUN_ROOT
PW_ARTIFACT_DIR=$PW_ARTIFACT_DIR
PW_REPORT_DIR=$PW_REPORT_DIR
PW_RESULTS_JSON=$PW_RESULTS_JSON
PW_STORAGE_DIR=$PW_STORAGE_DIR
ENV
}

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
  prod frontend:  http://localhost:$PROD_FRONTEND_PORT
  redis:          localhost:$REDIS_PORT
  enterprise:     $ENTERPRISE_PLUGINS_DIR
  plugin jars:    $ENTERPRISE_PLUGIN_JARS_DIR
  e2e artifacts:  web-admin/$PW_E2E_RUN_ROOT
  env file:       $STACK_ENV_FILE

SUMMARY

if [ "$DRY_RUN" = "1" ]; then
    echo "(dry-run mode: not starting docker)"
    exit 0
fi

# ---------- compose up ----------

cd "$PROJECT_ROOT"

docker_vm_free_mb() {
    local image="${AURA_DOCKER_DF_IMAGE:-redis:7-alpine}"
    if ! docker image inspect "$image" >/dev/null 2>&1; then
        return 2
    fi
    docker run --rm --pull=never "$image" sh -c "df -Pm /data 2>/dev/null || df -Pm /" \
        | awk 'NR == 2 { print $4 }'
}

check_docker_disk_space() {
    if [ "$SKIP_DOCKER_DISK_CHECK" = "1" ]; then
        echo "WARN: skipping Docker VM disk preflight because AURA_SKIP_DOCKER_DISK_CHECK=1" >&2
        return 0
    fi
    if ! [[ "$MIN_DOCKER_FREE_MB" =~ ^[0-9]+$ ]]; then
        echo "ERROR: AURA_MIN_DOCKER_FREE_MB must be an integer, got '$MIN_DOCKER_FREE_MB'" >&2
        exit 2
    fi

    local free_mb=""
    if ! free_mb="$(docker_vm_free_mb 2>/dev/null)"; then
        echo "WARN: unable to inspect Docker VM free space; continuing without disk preflight." >&2
        echo "      Run scripts/dev/doctor-disk.sh if Docker startup fails." >&2
        return 0
    fi
    if [ -z "$free_mb" ] || ! [[ "$free_mb" =~ ^[0-9]+$ ]]; then
        echo "WARN: unable to parse Docker VM free space; continuing without disk preflight." >&2
        echo "      Run scripts/dev/doctor-disk.sh if Docker startup fails." >&2
        return 0
    fi

    if [ "$free_mb" -lt "$MIN_DOCKER_FREE_MB" ]; then
        cat >&2 <<ERROR
ERROR: Docker VM free space is ${free_mb} MB, below required ${MIN_DOCKER_FREE_MB} MB.
       Full isolated stacks create per-stack node_modules, Postgres data, and
       often a backend image. Free space first, then retry.

Suggested checks:
  scripts/dev/doctor-disk.sh
  scripts/dev/cleanup-stack.sh --slug=<stale-slug> --volumes --images
  scripts/dev/cleanup-stack.sh --slug=<stale-slug> --volumes --images --apply

Override only when you intentionally accept the risk:
  AURA_SKIP_DOCKER_DISK_CHECK=1 $0 --slug=$SLUG
ERROR
        exit 4
    fi
    echo "Docker VM disk preflight: ${free_mb} MB free (threshold ${MIN_DOCKER_FREE_MB} MB)"
}

check_docker_disk_space

prepare_host_cache_dirs() {
    mkdir -p "$AURA_CACHE_ROOT"
    AURA_CACHE_ROOT="$(cd "$AURA_CACHE_ROOT" && pwd)"
    mkdir -p "$AURA_CONTAINER_CACHE_ROOT/gradle" \
        "$AURA_CONTAINER_CACHE_ROOT/m2" \
        "$AURA_CONTAINER_CACHE_ROOT/pnpm-store" \
        "$AURA_CONTAINER_CACHE_ROOT/ms-playwright"
    AURA_CONTAINER_CACHE_ROOT="$(cd "$AURA_CONTAINER_CACHE_ROOT" && pwd)"
}

prepare_host_cache_dirs

write_stack_env

# Export so docker compose substitutes them into docker-compose.isolated.yml.
export COMPOSE_PROJECT_NAME="$PROJECT_NAME"
export PG_PORT BE_PORT VITE_PORT BFF_PORT PROD_FRONTEND_PORT REDIS_PORT
export ENTERPRISE_PLUGINS_DIR
export ENTERPRISE_PLUGIN_JARS_DIR
export AURABOOT_BFF_ALLOWED_PORTS="$BFF_PORT,$VITE_PORT"
export AURA_CACHE_ROOT
export AURA_CONTAINER_CACHE_KEY
export AURA_CONTAINER_CACHE_ROOT
export SLUG
export PW_E2E_RUN_ID PW_E2E_RUN_ROOT PW_ARTIFACT_DIR PW_REPORT_DIR PW_RESULTS_JSON PW_STORAGE_DIR

pre_pull_image() {
    local image="$1"
    if docker image inspect "$image" >/dev/null 2>&1; then
        echo "Image cached: $image"
        return 0
    fi
    echo "Pulling image: $image"
    docker pull "$image" &
    local pull_pid=$!
    local elapsed=0
    while kill -0 "$pull_pid" >/dev/null 2>&1; do
        if [ "$IMAGE_PULL_TIMEOUT_SECONDS" -gt 0 ] && [ "$elapsed" -ge "$IMAGE_PULL_TIMEOUT_SECONDS" ]; then
            echo "ERROR: timed out pulling image after ${IMAGE_PULL_TIMEOUT_SECONDS}s: $image" >&2
            kill -TERM "$pull_pid" >/dev/null 2>&1 || true
            wait "$pull_pid" >/dev/null 2>&1 || true
            return 124
        fi
        sleep 5
        elapsed=$((elapsed + 5))
    done
    wait "$pull_pid"
}

if [ "$SKIP_PULL" != "1" ]; then
    pre_pull_image "pgvector/pgvector:pg16"
    pre_pull_image "redis:7-alpine"
    pre_pull_image "${ISOLATED_FRONTEND_IMAGE:-node:22-bookworm-slim}"
fi

if [ -z "$COMPOSE_BUILD_FLAG" ]; then
    echo "Bringing stack up (reusing cached image; pass --rebuild if Dockerfile / build.gradle / src changed) …"
else
    echo "Bringing stack up (--rebuild → forcing backend image rebuild) …"
fi

if [ -n "$COMPOSE_BUILD_FLAG" ] && [ "$QUIET_BUILD" = "1" ]; then
    echo "Quietly rebuilding backend image before detached startup …"
    if ! docker compose \
            -f docker-compose.yml \
            -f docker-compose.isolated.yml \
            --profile isolated \
            --profile cache \
            build --quiet backend; then
        echo "ERROR: docker compose build failed." >&2
        exit 4
    fi
    COMPOSE_BUILD_FLAG="--no-build"
fi

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

wait_for_http() {
    local name="$1"
    local url="$2"
    local attempts="$3"
    local delay="$4"
    local i
    echo "Waiting for $name: $url"
    for i in $(seq 1 "$attempts"); do
        if curl -fsS "$url" >/dev/null 2>&1; then
            echo "  $name healthy after ${i}×${delay}s"
            return 0
        fi
        sleep "$delay"
    done
    echo "ERROR: $name did not become healthy: $url" >&2
    docker compose -p "$PROJECT_NAME" \
        -f docker-compose.yml \
        -f docker-compose.isolated.yml ps || true
    docker compose -p "$PROJECT_NAME" \
        -f docker-compose.yml \
        -f docker-compose.isolated.yml logs --tail 160 backend || true
    docker compose -p "$PROJECT_NAME" \
        -f docker-compose.yml \
        -f docker-compose.isolated.yml logs --tail 160 isolated-frontend || true
    exit 4
}

if [ "$WAIT_FOR_HEALTH" = "1" ]; then
    wait_for_http "backend" "http://localhost:$BE_PORT/actuator/health" 120 5
    wait_for_http "frontend" "http://localhost:$VITE_PORT/" 120 5
fi

echo ""
echo "Stack '$PROJECT_NAME' starting. Use these to interact:"
echo "  logs:    docker compose -p $PROJECT_NAME logs -f"
echo "  stop:    scripts/dev/stop-isolated.sh --slug=$SLUG"
echo "  vite:    http://localhost:$VITE_PORT"
echo ""
