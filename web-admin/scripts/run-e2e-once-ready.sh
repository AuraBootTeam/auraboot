#!/usr/bin/env bash
set -euo pipefail

# Avoid Node warning: "NO_COLOR is ignored due to FORCE_COLOR being set"
unset NO_COLOR

# Run Playwright only when local services are reachable.
# If unreachable, wait 60s and retry health checks.
#
# Usage:
#   ./scripts/run-e2e-once-ready.sh
#   ./scripts/run-e2e-once-ready.sh tests/e2e/auth/
#   WAIT_SECONDS=60 MAX_ROUNDS=10 ./scripts/run-e2e-once-ready.sh --project=chromium

WAIT_SECONDS="${WAIT_SECONDS:-60}"
MAX_ROUNDS="${MAX_ROUNDS:-10}"
BACKEND_HEALTH_URL="${BACKEND_HEALTH_URL:-http://localhost:6443/actuator/health}"
FRONTEND_URL="${FRONTEND_URL:-http://localhost:5173/login}"

check_http_200() {
  local url="$1"
  local code
  code="$(NO_PROXY=localhost curl -s -o /dev/null -w "%{http_code}" "$url" || true)"
  [[ "$code" == "200" ]]
}

round=1
while (( round <= MAX_ROUNDS )); do
  backend_ok=0
  frontend_ok=0

  if check_http_200 "$BACKEND_HEALTH_URL"; then
    backend_ok=1
  fi

  if check_http_200 "$FRONTEND_URL"; then
    frontend_ok=1
  fi

  if (( backend_ok == 1 && frontend_ok == 1 )); then
    echo "[run-e2e-once-ready] services ready (backend+frontend). starting playwright..."
    NO_PROXY=localhost npx playwright test "$@"
    exit $?
  fi

  echo "[run-e2e-once-ready] round ${round}/${MAX_ROUNDS} not ready: backend=${backend_ok}, frontend=${frontend_ok}. wait ${WAIT_SECONDS}s..."
  sleep "$WAIT_SECONDS"
  ((round++))
done

echo "[run-e2e-once-ready] services still unavailable after ${MAX_ROUNDS} rounds."
exit 1
