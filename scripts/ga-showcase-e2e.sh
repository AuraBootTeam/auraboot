#!/usr/bin/env bash
# Run the GA community showcase E2E gate with isolated Playwright storage.
#
# Usage:
#   ./scripts/ga-showcase-e2e.sh auth
#   ./scripts/ga-showcase-e2e.sh chromium
#   ./scripts/ga-showcase-e2e.sh all
#
# The GA stack must already be running via docker-ga-e2e-up/bootstrap.

set -euo pipefail

PHASE="${1:-all}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
WEB_ADMIN_DIR="$REPO_ROOT/web-admin"

case "$PHASE" in
  auth|chromium|all) ;;
  *)
    echo "Usage: $0 [auth|chromium|all]" >&2
    exit 2
    ;;
esac

cd "$WEB_ADMIN_DIR"

ACTIVE_RUNNERS="$(
  ps -Ao pid=,command= \
    | grep -E 'playwright.*test|test:agent:crm' \
    | grep -v 'grep -E' \
    | grep -v '@playwright/mcp' \
    | grep -v "$$" \
    || true
)"

if [[ -n "$ACTIVE_RUNNERS" ]]; then
  echo "ERROR: another regular Playwright runner is active in this user session." >&2
  echo "Stop it before running the GA showcase gate to avoid storage/output races:" >&2
  echo "$ACTIVE_RUNNERS" >&2
  exit 1
fi

export NO_PROXY="localhost,127.0.0.1"
export PLAYWRIGHT_BASE_URL="http://127.0.0.1:5174"
export BASE_URL="http://127.0.0.1:5174"
export BACKEND_URL="http://localhost:6444"
export PW_SKIP_WEBSERVER="1"
export PW_STORAGE_DIR="tests/storage/ga"
export PW_ADMIN_STORAGE_STATE="tests/storage/ga/admin.json"
export PW_OPERATOR_STORAGE_STATE="tests/storage/ga/operator.json"
export PW_VIEWER_STORAGE_STATE="tests/storage/ga/viewer.json"

mkdir -p "$PW_STORAGE_DIR" test-results

run_auth() {
  local output="test-results/ga-showcase-auth"
  local log="/tmp/pw-ga-showcase-auth-$(date +%Y%m%d-%H%M%S).log"
  echo "=== GA showcase auth ==="
  echo "Log: $log"
  npx playwright test -c playwright.config.ts tests/auth.setup.ts \
    --project=auth --reporter=line --workers=1 --output="$output" \
    2>&1 | tee "$log"
  return "${PIPESTATUS[0]}"
}

run_chromium() {
  local output="test-results/ga-showcase-chromium-no-deps"
  local log="/tmp/pw-ga-showcase-chromium-no-deps-$(date +%Y%m%d-%H%M%S).log"
  echo "=== GA showcase chromium --no-deps ==="
  echo "Log: $log"
  npx playwright test -c playwright.config.ts tests/e2e/showcase/ \
    --project=chromium --no-deps --reporter=line --workers=2 --output="$output" \
    2>&1 | tee "$log"
  return "${PIPESTATUS[0]}"
}

case "$PHASE" in
  auth)
    run_auth
    ;;
  chromium)
    run_chromium
    ;;
  all)
    run_auth
    run_chromium
    ;;
esac
