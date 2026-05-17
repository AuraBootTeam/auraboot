#!/bin/bash
#
# Fresh isolated Docker gate for the canonical agent runtime chain.
#
# Default gate:
#   1. Purge/recreate an isolated stack.
#   2. Wait for backend + frontend health.
#   3. Run auth setup, AuraBot pending/resume API E2E, and Admin Agent Runs UI replay.
#
# Optional:
#   --include-oss-full also runs OSS chromium + chromium-deep phases serially.
#   --host-runner runs Playwright from the host against isolated stack ports.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# shellcheck source=../lib/reset-init-common.sh
source "$PROJECT_ROOT/scripts/lib/reset-init-common.sh"

SLUG=""
KEEP_STACK=0
FRESH=1
REBUILD=0
SKIP_PULL=0
INCLUDE_OSS_FULL=0
HOST_RUNNER=0
FRONTEND_IMAGE=""
STARTED=0
AGENT_RUNTIME_PLUGIN_IMPORT_PROFILE="${AGENT_RUNTIME_PLUGIN_IMPORT_PROFILE:-e2e}"

usage() {
    cat <<USAGE
Usage: $0 [--slug=<name>] [--reuse-stack] [--keep-stack] [--rebuild] [--skip-pull] [--host-runner] [--frontend-image=<image>] [--include-oss-full]

Options:
  --slug=<name>       Isolated stack slug (default: branch-derived)
  --reuse-stack       Reuse an existing stack instead of purge/recreate
  --keep-stack        Do not stop the stack after the gate
  --rebuild           Force backend image rebuild through start-isolated.sh
  --skip-pull         Skip third-party image pre-pull
  --host-runner       Run Playwright on the host against isolated stack ports
  --frontend-image    Override isolated frontend service image
  --include-oss-full  After agent runtime target phases, run OSS chromium and chromium-deep
  --help              Show this message
USAGE
}

for arg in "$@"; do
    case "$arg" in
        --slug=*) SLUG="${arg#--slug=}" ;;
        --reuse-stack) FRESH=0 ;;
        --fresh) FRESH=1 ;;
        --keep-stack) KEEP_STACK=1 ;;
        --rebuild) REBUILD=1 ;;
        --skip-pull) SKIP_PULL=1 ;;
        --host-runner) HOST_RUNNER=1 ;;
        --frontend-image=*) FRONTEND_IMAGE="${arg#--frontend-image=}" ;;
        --include-oss-full) INCLUDE_OSS_FULL=1 ;;
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
    branch="$(git -C "$PROJECT_ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || echo agent-runtime-gate)"
    if [ "$branch" = "HEAD" ]; then
        branch="$(basename "$PROJECT_ROOT")"
    fi
    SLUG="$(normalize_slug "$branch")"
fi

if [ -z "$SLUG" ]; then
    echo "ERROR: could not derive slug; pass --slug=<name>" >&2
    exit 2
fi

LOG_DIR="${AGENT_RUNTIME_GATE_LOG_DIR:-/tmp/aura-agent-runtime-gate}"
mkdir -p "$LOG_DIR"
RUN_STAMP="$(date +%Y%m%d-%H%M%S)"

teardown() {
    local exit_code=$?
    if [ "$STARTED" = "1" ] && [ "$KEEP_STACK" != "1" ]; then
        "$SCRIPT_DIR/stop-isolated.sh" --slug="$SLUG" || true
    elif [ "$STARTED" = "1" ]; then
        echo "Stack kept running. Stop with: scripts/dev/stop-isolated.sh --slug=$SLUG"
    fi
    exit "$exit_code"
}
trap teardown EXIT

ENV_FILE="$PROJECT_ROOT/.aura-stack/${SLUG}.env"
PROJECT_NAME="auraboot-${SLUG}"

if [ "$FRESH" = "1" ]; then
    echo "==> Purging existing isolated stack: $SLUG"
    "$SCRIPT_DIR/stop-isolated.sh" --slug="$SLUG" --purge >/dev/null 2>&1 || true
fi

if [ "$FRESH" != "1" ] \
    && [ -f "$ENV_FILE" ] \
    && docker ps --format '{{.Names}}' | grep -qx "${PROJECT_NAME}-backend"; then
    echo "==> Reusing isolated stack: $SLUG"
    STARTED=1
else
    START_ARGS=("--slug=$SLUG" "--wait" "--quiet-build")
    if [ "$REBUILD" = "1" ]; then
        START_ARGS+=("--rebuild")
    fi
    if [ "$SKIP_PULL" = "1" ]; then
        START_ARGS+=("--skip-pull")
    fi

    echo "==> Starting isolated stack: $SLUG"
    if [ -n "$FRONTEND_IMAGE" ]; then
        ISOLATED_FRONTEND_IMAGE="$FRONTEND_IMAGE" \
            AGENT_LLM_STUB_MODE=true "$SCRIPT_DIR/start-isolated.sh" "${START_ARGS[@]}"
    else
        AGENT_LLM_STUB_MODE=true "$SCRIPT_DIR/start-isolated.sh" "${START_ARGS[@]}"
    fi
    STARTED=1
fi

if [ ! -f "$ENV_FILE" ]; then
    echo "ERROR: missing isolated stack env file: $ENV_FILE" >&2
    exit 1
fi
# shellcheck disable=SC1090
source "$ENV_FILE"

FRONTEND_CONTAINER="${COMPOSE_PROJECT_NAME}-frontend"
API_BASE="http://localhost:${BE_PORT}"
echo "==> Frontend container: $FRONTEND_CONTAINER"
if [ "$HOST_RUNNER" = "1" ]; then
    echo "==> Runner: host Playwright -> isolated ports"
else
    echo "==> Runner: isolated Playwright runner container"
fi
echo "==> Plugin import profile: $AGENT_RUNTIME_PLUGIN_IMPORT_PROFILE"
echo "==> Logs: $LOG_DIR"

capture_stack_logs() {
    local phase="$1"
    local prefix="$LOG_DIR/${RUN_STAMP}-${SLUG}-${phase}"

    echo "==> Capturing isolated stack diagnostics for failed phase: $phase" >&2
    docker compose \
        -p "$COMPOSE_PROJECT_NAME" \
        -f docker-compose.yml \
        -f docker-compose.isolated.yml \
        --profile isolated \
        --profile cache \
        --profile playwright-runner \
        ps > "${prefix}-docker-ps.log" 2>&1 || true

    docker compose \
        -p "$COMPOSE_PROJECT_NAME" \
        -f docker-compose.yml \
        -f docker-compose.isolated.yml \
        --profile isolated \
        --profile cache \
        --profile playwright-runner \
        logs --no-color --timestamps --tail=500 \
        backend frontend playwright-runner postgres redis \
        > "${prefix}-docker-logs.log" 2>&1 || true
}

run_frontend_phase() {
    local phase="$1"
    local command="$2"
    local log="$LOG_DIR/${RUN_STAMP}-${SLUG}-${phase}.log"
    echo
    echo "==> Phase: $phase"
    echo "    log: $log"
    set +e
    if [ "$HOST_RUNNER" = "1" ]; then
        (
            cd "$PROJECT_ROOT/web-admin"
            BACKEND_URL="http://localhost:$BE_PORT" \
                BE_PORT="$BE_PORT" \
                SPRING_BOOT_URL="http://localhost:$BE_PORT" \
                PLAYWRIGHT_BASE_URL="http://localhost:$VITE_PORT" \
                BFF_URL="http://localhost:$BFF_PORT" \
                PW_SKIP_WEBSERVER=1 \
                PW_PROFILE=full \
                PW_WORKERS=1 \
                NO_PROXY=localhost,127.0.0.1 \
                PGHOST=localhost \
                PGPORT="$PG_PORT" \
                PGUSER=auraboot \
                PGPASSWORD=auraboot_dev \
                PGDATABASE=aura_boot \
                PG_HOST=localhost \
                PG_PORT="$PG_PORT" \
                PG_USER=auraboot \
                PG_PASSWORD=auraboot_dev \
                PG_DB=aura_boot \
                OSS_PLUGIN_ROOT="$PROJECT_ROOT/plugins" \
                ENTERPRISE_PLUGIN_ROOT="$ENTERPRISE_PLUGINS_DIR" \
                bash -lc "$command"
        ) 2>&1 | tee "$log"
    else
        PLAYWRIGHT_RUNNER_COMMAND="$command" \
            docker compose \
            -p "$COMPOSE_PROJECT_NAME" \
            -f docker-compose.yml \
            -f docker-compose.isolated.yml \
            --profile isolated \
            --profile cache \
            --profile playwright-runner \
            run --rm --no-deps playwright-runner 2>&1 | tee "$log"
    fi
    local exit_code=${PIPESTATUS[0]}
    set -e
    if [ "$exit_code" -ne 0 ]; then
        capture_stack_logs "$phase"
        echo "ERROR: phase failed: $phase (exit=$exit_code, log=$log)" >&2
        exit "$exit_code"
    fi
}

import_agent_runtime_plugins() {
    echo
    echo "==> Importing plugins for agent runtime gate"
    "$PROJECT_ROOT/scripts/import-plugins.sh" \
        --slug="$SLUG" \
        --profile="$AGENT_RUNTIME_PLUGIN_IMPORT_PROFILE" \
        --edition=oss
}

echo
echo "==> Ensuring minimal bootstrap is initialized"
aura_bootstrap_setup_if_needed \
    "$API_BASE" \
    "AuraBoot Dev" \
    "admin@auraboot.com" \
    "Test2026x" \
    "Admin User" \
    "single" \
    "[agent-runtime-gate]"
import_agent_runtime_plugins

run_frontend_phase "auth" \
    "pnpm exec playwright test -c playwright.noweb.config.ts tests/auth.setup.ts --project=auth --reporter=line --output=test-results/agent-runtime-gate-auth"

run_frontend_phase "api-resume" \
    "pnpm exec playwright test -c playwright.noweb.config.ts tests/api/agent/aurabot-skill-resume-runtime.spec.ts --project=api --no-deps --workers=1 --reporter=line --output=test-results/agent-runtime-gate-api"

run_frontend_phase "admin-agent-runs" \
    "pnpm exec playwright test -c playwright.noweb.config.ts tests/e2e/aurabot/admin-agent-runs.spec.ts --project=chromium --no-deps --workers=1 --reporter=line --output=test-results/agent-runtime-gate-admin-runs"

if [ "$INCLUDE_OSS_FULL" = "1" ]; then
    run_frontend_phase "oss-chromium" \
        "pnpm exec playwright test -c playwright.oss.config.ts --project=chromium --no-deps --workers=1 --reporter=line --output=test-results/agent-runtime-gate-oss-chromium"
    run_frontend_phase "oss-chromium-deep" \
        "pnpm exec playwright test -c playwright.oss.config.ts --project=chromium-deep --no-deps --workers=1 --reporter=line --output=test-results/agent-runtime-gate-oss-deep"
fi

echo
echo "==> Agent runtime isolated gate passed"
