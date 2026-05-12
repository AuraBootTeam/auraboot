#!/bin/bash
#
# run-p0-e2e-docker.sh — fully self-contained docker-only validation of the
# 4 P0 menu-coverage E2E specs (commit b98269e3 / docs/backlog/2026-05-08-e2e-p0-followups.md).
#
# What it does:
#   1. Starts (or reuses) a per-worktree isolated docker stack via
#      scripts/dev/start-isolated.sh.
#   2. Polls until backend (6443 in container) and frontend ports respond.
#   3. Logs into the docker backend and imports the 4 plugins required for
#      the P0 specs in dependency order:
#        core-bpm → platform-admin → acp-showcase → test-fixtures
#      (Bootstrap only auto-imports org-management; everything else is
#      manual because platform-admin's `bpm_management` parent menu lives
#      in core-bpm.)
#   4. Refreshes web-admin/tests/storage/admin.json against the docker BFF
#      (the refresh script reads AURA_FRONTEND_PORT env from this script).
#   5. Runs the 4 P0 specs with PLAYWRIGHT_BASE_URL pointed at the docker
#      vite + workers=1 (serial avoids cross-spec contention on shared DB).
#
# Zero host-backend dependency: even if `localhost:6443` is down or
# completely absent, this script runs end-to-end against its own stack.
#
# Usage:
#   scripts/dev/run-p0-e2e-docker.sh                 # auto slug from branch
#   scripts/dev/run-p0-e2e-docker.sh --slug=my-poc   # explicit slug
#   scripts/dev/run-p0-e2e-docker.sh --keep-stack    # don't tear down on exit
#
# Exit codes:
#   0  all passed (or only known fixme test cases skipped)
#   1  setup error (stack failed to come up, login failed, import failed)
#   2  Playwright reported failures
#   3  CLI usage error

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# ---------- argument parsing ----------

SLUG=""
KEEP_STACK=0

usage() {
    cat <<USAGE
Usage: $0 [--slug=<name>] [--keep-stack] [--help]

Options:
  --slug=<name>    Override auto-derived slug (default: branch-based, like start-isolated.sh)
  --keep-stack     Leave the docker stack running after specs finish
  --help           Show this message
USAGE
}

for arg in "$@"; do
    case "$arg" in
        --slug=*) SLUG="${arg#--slug=}" ;;
        --keep-stack) KEEP_STACK=1 ;;
        --help|-h) usage; exit 0 ;;
        *) echo "ERROR: unknown argument: $arg" >&2; usage; exit 3 ;;
    esac
done

# Default slug from branch
if [[ -z "$SLUG" ]]; then
    BRANCH="$(git -C "$PROJECT_ROOT" branch --show-current 2>/dev/null || echo "p0-e2e")"
    SLUG="$(echo "$BRANCH" | tr '[:upper:]' '[:lower:]' | sed -E 's/[^a-z0-9-]/-/g; s/-+/-/g; s/^-//; s/-$//' | cut -c1-24)"
    [[ -z "$SLUG" ]] && SLUG="p0-e2e"
fi

echo "▶ Slug: $SLUG"

# ---------- 1. start (or reuse) isolated stack ----------

ENV_FILE="$PROJECT_ROOT/.aura-stack/${SLUG}.env"

if [[ -f "$ENV_FILE" ]] && docker ps --format '{{.Names}}' | grep -q "auraboot-${SLUG}-backend"; then
    echo "▶ Reusing running stack auraboot-${SLUG}"
else
    echo "▶ Starting isolated stack (this may take ~5min on first build)..."
    "$SCRIPT_DIR/start-isolated.sh" --slug="$SLUG"
fi

# Source the env file produced by start-isolated.sh
# shellcheck disable=SC1090
source "$ENV_FILE"

BACKEND_URL="http://localhost:${BE_PORT}"
FRONTEND_URL="http://localhost:${VITE_PORT}"
echo "▶ Backend: $BACKEND_URL"
echo "▶ Frontend: $FRONTEND_URL"

# ---------- 2. wait for backend + frontend ----------

echo "▶ Waiting for backend health..."
for i in {1..60}; do
    if curl -sf "$BACKEND_URL/actuator/health" >/dev/null 2>&1; then
        echo "  backend up"
        break
    fi
    sleep 5
    [[ $i -eq 60 ]] && { echo "ERROR: backend did not come up in 5min" >&2; exit 1; }
done

echo "▶ Waiting for frontend..."
for i in {1..30}; do
    code=$(curl -s -o /dev/null -w '%{http_code}' "$FRONTEND_URL/" 2>&1 || true)
    if [[ "$code" == "200" || "$code" == "302" ]]; then
        echo "  frontend up (HTTP $code)"
        break
    fi
    sleep 5
    [[ $i -eq 30 ]] && { echo "ERROR: frontend did not come up in 2.5min" >&2; exit 1; }
done

# ---------- 3. login + plugin imports ----------

echo "▶ Authenticating against docker backend..."
LOGIN_RESP=$(curl -sf -X POST "$BACKEND_URL/api/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"email":"admin@auraboot.com","password":"Test2026x"}')

JWT=$(echo "$LOGIN_RESP" | jq -r '.data.jwt // empty')
if [[ -z "$JWT" ]]; then
    echo "ERROR: login failed: $LOGIN_RESP" >&2
    exit 1
fi
echo "  authenticated (jwt len=${#JWT})"

# Plugin import order matters: core-bpm provides `bpm_management` parent
# menu that platform-admin references.
#
# Each entry encodes "name:required" so this works on macOS bash 3.2 (no
# associative arrays). Optional plugins skip silently if their plugin.json
# is absent (e.g. acp-showcase was removed in commit 59050eed per the
# platformization design P0', so the ACS specs become orphans).
PLUGIN_SPEC=(
    "core-bpm:required"
    "platform-admin:required"
    "acp-showcase:optional"
    "test-fixtures:required"
)

for entry in "${PLUGIN_SPEC[@]}"; do
    p="${entry%%:*}"
    req="${entry##*:}"
    if [[ ! -f "$PROJECT_ROOT/plugins/$p/plugin.json" ]]; then
        if [[ "$req" == "required" ]]; then
            echo "ERROR: required plugin $p missing plugin.json at $PROJECT_ROOT/plugins/$p" >&2
            exit 1
        else
            echo "▶ Skipping optional plugin (not in repo): $p"
            continue
        fi
    fi
    echo "▶ Importing plugin: $p"
    if ! AURA_TOKEN="$JWT" aura plugin import "$PROJECT_ROOT/plugins/$p" \
        --target "$BACKEND_URL" --yes 2>&1 | tail -10; then
        echo "ERROR: plugin import failed for $p" >&2
        exit 1
    fi
done

# ---------- 4. refresh admin storage state against docker BFF ----------

echo "▶ Refreshing admin storage state..."
cd "$PROJECT_ROOT/web-admin"
AURA_FRONTEND_PORT="$VITE_PORT" node tests/refresh-admin-session.mjs

# ---------- 5. run P0 specs ----------

echo "▶ Running P0 specs against docker (workers=1)..."
LOG="/tmp/pw-p0-docker-${SLUG}-$(date +%Y%m%d-%H%M%S).log"
echo "  log: $LOG"

# Spec selection adapts to which plugins were imported. ACS specs require
# acp-showcase, which is no longer in the repo — those specs become
# orphans and would fail at sidebar nav. Only run them if the plugin was
# imported (presence of plugin.json gates this same as the import loop).
SPECS=(
    tests/e2e/admin/scheduled-task-lifecycle.spec.ts
    tests/e2e/e2et-order/e2et-order-dashboard-lifecycle.spec.ts
)
if [[ -f "$PROJECT_ROOT/plugins/acp-showcase/plugin.json" ]]; then
    SPECS+=(
        tests/e2e/agent-control-plane/acs-demo-request-lifecycle.spec.ts
        tests/e2e/agent-control-plane/acs-safety-rule-lifecycle.spec.ts
    )
fi
echo "  specs: ${#SPECS[@]}"

set +e
PLAYWRIGHT_BASE_URL="$FRONTEND_URL" PW_SKIP_WEBSERVER=1 NO_PROXY=localhost \
    npx playwright test --project=chromium "${SPECS[@]}" \
    --reporter=line --workers=1 2>&1 | tee "$LOG"
PW_EXIT=${PIPESTATUS[0]}
set -e

# ---------- 6. teardown (unless --keep-stack) ----------

if [[ $KEEP_STACK -eq 0 ]]; then
    echo "▶ Stopping stack (use --keep-stack to skip)..."
    "$SCRIPT_DIR/stop-isolated.sh" --slug="$SLUG" || true
else
    echo "▶ Stack kept running. Stop with: scripts/dev/stop-isolated.sh --slug=$SLUG"
fi

echo
echo "▶ Summary log: $LOG"
exit $PW_EXIT
