#!/usr/bin/env bash
# Run the GA community showcase E2E gate with isolated Playwright storage.
#
# Usage:
#   ./scripts/ga-showcase-e2e.sh auth
#   ./scripts/ga-showcase-e2e.sh chromium
#   ./scripts/ga-showcase-e2e.sh deep
#   ./scripts/ga-showcase-e2e.sh all
#
# The GA stack must already be running via docker-ga-e2e-up/bootstrap.

set -euo pipefail

PHASE="${1:-all}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
WEB_ADMIN_DIR="$REPO_ROOT/web-admin"

case "$PHASE" in
  auth|chromium|deep|all) ;;
  *)
    echo "Usage: $0 [auth|chromium|deep|all]" >&2
    exit 2
    ;;
esac

cd "$WEB_ADMIN_DIR"

if [[ "${GA_SHOWCASE_SKIP_RUNNER_GUARD:-0}" != "1" ]]; then
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
fi

export NO_PROXY="${NO_PROXY:-localhost,127.0.0.1}"
export PLAYWRIGHT_BASE_URL="${PLAYWRIGHT_BASE_URL:-http://127.0.0.1:5174}"
export BASE_URL="${BASE_URL:-$PLAYWRIGHT_BASE_URL}"
export BACKEND_URL="${BACKEND_URL:-http://localhost:6444}"
export PW_SKIP_WEBSERVER="${PW_SKIP_WEBSERVER:-1}"
export PW_STORAGE_DIR="${PW_STORAGE_DIR:-tests/storage/ga}"
export PW_ADMIN_STORAGE_STATE="${PW_ADMIN_STORAGE_STATE:-$PW_STORAGE_DIR/admin.json}"
export PW_OPERATOR_STORAGE_STATE="${PW_OPERATOR_STORAGE_STATE:-$PW_STORAGE_DIR/operator.json}"
export PW_VIEWER_STORAGE_STATE="${PW_VIEWER_STORAGE_STATE:-$PW_STORAGE_DIR/viewer.json}"

mkdir -p "$PW_STORAGE_DIR" test-results

find_competing_runners() {
  local marker="${1:-}"
  ps -Ao pid=,command= \
    | grep -E 'playwright.*test|test:agent:crm' \
    | grep -v 'grep -E' \
    | grep -v '@playwright/mcp' \
    | grep -v "$$" \
    | { if [[ -n "$marker" ]]; then grep -v -- "$marker"; else cat; fi; } \
    || true
}

run_guarded() {
  local marker="$1"
  local log="$2"
  shift 2

  if [[ "${GA_SHOWCASE_SKIP_RUNNER_GUARD:-0}" = "1" ]]; then
    "$@" 2>&1 | tee "$log"
    return "${PIPESTATUS[0]}"
  fi

  local status_file
  status_file="$(mktemp)"
  (
    set +e
    "$@" 2>&1 | tee "$log"
    echo "${PIPESTATUS[0]}" > "$status_file"
  ) &
  local runner_pid=$!

  while kill -0 "$runner_pid" 2>/dev/null; do
    local active
    active="$(find_competing_runners "$marker")"
    if [[ -n "$active" ]]; then
      echo "ERROR: another regular Playwright runner started while GA showcase gate was running." >&2
      echo "Aborting this run to avoid invalid E2E failures:" >&2
      echo "$active" >&2
      pkill -P "$runner_pid" 2>/dev/null || true
      kill "$runner_pid" 2>/dev/null || true
      wait "$runner_pid" 2>/dev/null || true
      rm -f "$status_file"
      return 90
    fi
    sleep 2
  done

  wait "$runner_pid" 2>/dev/null || true
  local status=1
  if [[ -f "$status_file" ]]; then
    status="$(cat "$status_file")"
    rm -f "$status_file"
  fi
  return "$status"
}

run_auth() {
  local output="test-results/ga-showcase-auth"
  local log="/tmp/pw-ga-showcase-auth-$(date +%Y%m%d-%H%M%S).log"
  echo "=== GA showcase auth ==="
  echo "Log: $log"
  run_guarded "$output" "$log" npx playwright test -c playwright.config.ts tests/auth.setup.ts \
    --project=auth --reporter=line --workers=1 --output="$output"
}

run_chromium() {
  local output="test-results/ga-showcase-chromium-no-deps"
  local log="/tmp/pw-ga-showcase-chromium-no-deps-$(date +%Y%m%d-%H%M%S).log"
  echo "=== GA showcase chromium --no-deps ==="
  echo "Log: $log"
  run_guarded "$output" "$log" npx playwright test -c playwright.config.ts tests/e2e/showcase/ \
    --project=chromium --no-deps --reporter=line --workers=2 --output="$output"
}

run_deep() {
  local output="test-results/ga-showcase-chromium-deep-no-deps"
  local log="/tmp/pw-ga-showcase-chromium-deep-no-deps-$(date +%Y%m%d-%H%M%S).log"
  echo "=== GA showcase chromium-deep --no-deps ==="
  echo "Log: $log"
  run_guarded "$output" "$log" npx playwright test -c playwright.config.ts tests/e2e/showcase/ \
    --project=chromium-deep --no-deps --reporter=line --workers=1 --output="$output"
}

case "$PHASE" in
  auth)
    run_auth
    ;;
  chromium)
    run_chromium
    ;;
  deep)
    run_deep
    ;;
  all)
    run_auth
    run_chromium
    run_deep
    ;;
esac
