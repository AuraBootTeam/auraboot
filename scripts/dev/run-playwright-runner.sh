#!/bin/bash
#
# Run the optional Linux Playwright runner for an isolated stack.
#
# The Playwright image is intentionally not pulled implicitly: it is large and
# belongs only to CI-like parity runs, not normal frontend/BFF development.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
STACK_DIR="$PROJECT_ROOT/.aura-stack"

SLUG=""
ALLOW_PULL=0
DRY_RUN=0
RUNNER_COMMAND=""
PLAYWRIGHT_RUNNER_IMAGE="${PLAYWRIGHT_RUNNER_IMAGE:-mcr.microsoft.com/playwright:v1.59.1-noble}"

usage() {
    cat <<USAGE
Usage: $0 --slug=<name> [--command=<cmd>] [--allow-pull] [--dry-run] [--help]

Options:
  --slug=<name>    Existing isolated stack slug.
  --command=<cmd>  Command executed inside playwright-runner.
                  Default: pnpm exec playwright test -c playwright.noweb.config.ts --reporter=line
  --allow-pull     Permit pulling PLAYWRIGHT_RUNNER_IMAGE if it is not cached.
  --dry-run        Print checks and compose command without running Docker.
  --help           Show this message.

Default image:
  $PLAYWRIGHT_RUNNER_IMAGE
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
        --command=*) RUNNER_COMMAND="${arg#--command=}" ;;
        --allow-pull) ALLOW_PULL=1 ;;
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

PROJECT_NAME="${COMPOSE_PROJECT_NAME:-auraboot-${SLUG}}"
RUNNER_COMMAND="${RUNNER_COMMAND:-pnpm exec playwright test -c playwright.noweb.config.ts --reporter=line}"

if ! docker image inspect "$PLAYWRIGHT_RUNNER_IMAGE" >/dev/null 2>&1; then
    cat >&2 <<WARN
WARN: Playwright runner image is not cached:
  $PLAYWRIGHT_RUNNER_IMAGE

This image is large and should only be pulled for Linux-browser parity runs.
Check Docker VM space first:
  scripts/dev/doctor-disk.sh

To pull and run anyway:
  $0 --slug=$SLUG --allow-pull
WARN
    if [ "$ALLOW_PULL" != "1" ]; then
        exit 4
    fi
fi

CMD=(
    docker compose
    -p "$PROJECT_NAME"
    -f docker-compose.yml
    -f docker-compose.isolated.yml
    --profile isolated
    --profile cache
    --profile playwright-runner
    run --rm
    playwright-runner
)

echo "Playwright runner plan"
echo "  slug:        $SLUG"
echo "  project:     $PROJECT_NAME"
echo "  image:       $PLAYWRIGHT_RUNNER_IMAGE"
echo "  allow-pull:  $([ "$ALLOW_PULL" = "1" ] && echo yes || echo no)"
echo "  command:     $RUNNER_COMMAND"
echo ""
echo "+ PLAYWRIGHT_RUNNER_COMMAND=\"$RUNNER_COMMAND\" ${CMD[*]}"

if [ "$DRY_RUN" = "1" ]; then
    echo "(dry-run mode: not running playwright-runner)"
    exit 0
fi

cd "$PROJECT_ROOT"
export COMPOSE_PROJECT_NAME="$PROJECT_NAME"
export PG_PORT BE_PORT VITE_PORT BFF_PORT PROD_FRONTEND_PORT REDIS_PORT
export AURA_CONTAINER_CACHE_ROOT="${AURA_CONTAINER_CACHE_ROOT:-$HOME/.cache/auraboot/container-linux}"
export PLAYWRIGHT_RUNNER_IMAGE
export PLAYWRIGHT_RUNNER_COMMAND="$RUNNER_COMMAND"
export PW_E2E_RUN_ID PW_E2E_RUN_ROOT PW_ARTIFACT_DIR PW_REPORT_DIR PW_RESULTS_JSON PW_STORAGE_DIR

"${CMD[@]}"
