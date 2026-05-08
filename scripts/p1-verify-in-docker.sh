#!/usr/bin/env bash
#
# P1' ACP platformization — docker isolated stack verification.
#
# Run this from the P1 worktree root. The script brings up an isolated
# postgres + backend + frontend stack on ports 5433 / 6444 / 5174 / 3501
# (via docker-compose.ga-e2e.override.yml), then:
#   1. resets DB to a clean state
#   2. applies scripts/p1-ai-annotation-temp.sql
#   3. builds the workflow-demo backend jar and mounts it into the
#      backend container's plugins dir
#   4. imports the workflow-demo plugin (with the new ai-fill-banner block)
#   5. runs platform p1demo gradle tests in the backend container
#   6. runs the wd-leave-request-ai-lifecycle Playwright spec against the
#      isolated frontend
#
# This script is idempotent — safe to re-run. Use ./scripts/p1-verify-down.sh
# (or `docker compose --project-name auraboot-p1ai down -v`) to tear down.
#
# Required env:
#   ANTHROPIC_API_KEY  - real key for LLM grounding (AI-002 needs a real
#                        round-trip; mocking defeats the validation purpose)
#
# Optional env:
#   SKIP_E2E=1         - skip Playwright run (only run backend tests)
#   SKIP_BACKEND=1     - skip gradle tests (only run E2E)
#   COMPOSE_PROJECT_NAME (default auraboot-p1ai) - override stack name to
#                        avoid colliding with parallel verifications
#
# Exit codes:
#   0 = all checks passed
#   1 = pre-flight failure (missing env, missing tools)
#   2 = stack-up failure (postgres / backend health timeout)
#   3 = SQL / plugin import failure
#   4 = gradle test failure
#   5 = Playwright failure

set -euo pipefail

# -----------------------------------------------------------------------------
# 0. Pre-flight
# -----------------------------------------------------------------------------

cd "$(dirname "$0")/.."
WORKTREE_ROOT="$(pwd)"
BRANCH="$(git branch --show-current 2>/dev/null || echo '?')"
echo "==> P1' verify starting in $WORKTREE_ROOT (branch: $BRANCH)"

if [[ "${BRANCH}" != "feat/acp-platformization-p1-leave-ai" ]]; then
  echo "WARN: not on feat/acp-platformization-p1-leave-ai (current: $BRANCH)" >&2
  echo "      Continuing anyway, but verify the right code is checked out." >&2
fi

if [[ -z "${ANTHROPIC_API_KEY:-}" ]]; then
  echo "ERROR: ANTHROPIC_API_KEY env var is required for AI-002." >&2
  echo "       Export it before running, or set SKIP_E2E=1 to skip the LLM-dependent test." >&2
  if [[ "${SKIP_E2E:-0}" != "1" ]]; then
    exit 1
  fi
fi

for tool in docker psql node pnpm; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "ERROR: required tool not on PATH: $tool" >&2
    exit 1
  fi
done

export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-auraboot-p1ai}"
PG_PORT="${PG_PORT:-5433}"
BACKEND_PORT="${BACKEND_PORT:-6444}"
VITE_PORT="${VITE_PORT:-5174}"
BFF_PORT="${BFF_PORT:-3501}"

LOG_DIR="/tmp/p1-verify-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$LOG_DIR"
echo "==> Logs: $LOG_DIR"

# -----------------------------------------------------------------------------
# 1. Stack up
# -----------------------------------------------------------------------------

echo "==> [1/6] Bringing up isolated stack ($COMPOSE_PROJECT_NAME)"
# docker-ga-e2e-up.sh respects COMPOSE_PROJECT_NAME and starts pg + backend + fe.
# It also handles the gradle-wrapper.jar bootstrap (see its header doc).
./scripts/docker-ga-e2e-up.sh 2>&1 | tee "$LOG_DIR/01-stack-up.log" || {
  echo "ERROR: stack failed to come up. See $LOG_DIR/01-stack-up.log" >&2
  exit 2
}

# Wait for backend health (script already waits, but be defensive).
echo "==> Waiting for backend health on :$BACKEND_PORT"
for _ in {1..60}; do
  if curl -fsS "http://localhost:$BACKEND_PORT/actuator/health" >/dev/null 2>&1; then
    echo "    backend healthy"
    break
  fi
  sleep 2
done

# -----------------------------------------------------------------------------
# 2. Apply temp annotation table SQL
# -----------------------------------------------------------------------------

echo "==> [2/6] Applying scripts/p1-ai-annotation-temp.sql"
PGPASSWORD=auraboot psql -h localhost -p "$PG_PORT" -U auraboot -d auraboot \
  -f scripts/p1-ai-annotation-temp.sql \
  2>&1 | tee "$LOG_DIR/02-annotation-sql.log" || {
  echo "ERROR: annotation table SQL failed. See $LOG_DIR/02-annotation-sql.log" >&2
  exit 3
}

# -----------------------------------------------------------------------------
# 3. Build + mount workflow-demo backend jar
# -----------------------------------------------------------------------------

echo "==> [3/6] Building plugins/workflow-demo/backend"
( cd plugins/workflow-demo/backend && gradle build --no-daemon -q ) \
  2>&1 | tee "$LOG_DIR/03-plugin-build.log" || {
  echo "ERROR: workflow-demo backend build failed. See $LOG_DIR/03-plugin-build.log" >&2
  exit 3
}

JAR_SRC="plugins/workflow-demo/backend/build/libs/workflow-demo-plugin-1.0.0.jar"
if [[ ! -f "$JAR_SRC" ]]; then
  echo "ERROR: built jar not found at $JAR_SRC" >&2
  exit 3
fi

# Mount by docker cp into the plugins dir the backend reads at startup.
# The exact target path matches aura.plugins.dir; default in dev is
# /app/plugins on the backend container.
BACKEND_CONTAINER="${COMPOSE_PROJECT_NAME}-backend"
docker cp "$JAR_SRC" "$BACKEND_CONTAINER:/app/plugins/workflow-demo-plugin-1.0.0.jar" \
  2>&1 | tee "$LOG_DIR/03-jar-mount.log" || {
  echo "ERROR: failed to docker cp jar to $BACKEND_CONTAINER" >&2
  exit 3
}

# Restart backend so PF4J picks up the new jar.
docker restart "$BACKEND_CONTAINER" 2>&1 | tee -a "$LOG_DIR/03-jar-mount.log"
echo "==> waiting for backend to come back after restart"
for _ in {1..60}; do
  curl -fsS "http://localhost:$BACKEND_PORT/actuator/health" >/dev/null 2>&1 && break
  sleep 2
done

# -----------------------------------------------------------------------------
# 4. Import workflow-demo plugin (with new form.json)
# -----------------------------------------------------------------------------

echo "==> [4/6] Importing workflow-demo plugin"
# `aura plugin-import` auto-detects token via local config or env.
# We expect the operator to have logged into the isolated stack at
# http://localhost:$BACKEND_PORT once before running this script;
# AURA_TOKEN env var is also accepted.
node plugins/cli/dist/index.js plugin-import plugins/workflow-demo \
  --target "http://localhost:$BACKEND_PORT" \
  --conflict-strategy overwrite \
  --yes \
  2>&1 | tee "$LOG_DIR/04-plugin-import.log" || {
  echo "ERROR: plugin import failed. See $LOG_DIR/04-plugin-import.log" >&2
  echo "       If 'aura' CLI is not built, run: pnpm --filter aura-cli build" >&2
  exit 3
}

# -----------------------------------------------------------------------------
# 5. Backend p1demo tests
# -----------------------------------------------------------------------------

if [[ "${SKIP_BACKEND:-0}" != "1" ]]; then
  echo "==> [5/6] Running backend p1demo tests in container"
  docker exec "$BACKEND_CONTAINER" \
    bash -c 'cd /app/platform && ./gradlew test --tests "*p1demo*" --no-daemon' \
    2>&1 | tee "$LOG_DIR/05-backend-tests.log" || {
    echo "ERROR: gradle test failed. See $LOG_DIR/05-backend-tests.log" >&2
    exit 4
  }
else
  echo "==> [5/6] SKIP backend tests (SKIP_BACKEND=1)"
fi

# -----------------------------------------------------------------------------
# 6. Playwright E2E
# -----------------------------------------------------------------------------

if [[ "${SKIP_E2E:-0}" != "1" ]]; then
  echo "==> [6/6] Running Playwright E2E (web-admin/tests/e2e/p1demo/)"
  pushd web-admin >/dev/null
  PLAYWRIGHT_BASE_URL="http://localhost:$VITE_PORT" \
    npx playwright test tests/e2e/p1demo/ --reporter=list \
    2>&1 | tee "$LOG_DIR/06-e2e.log" || {
    popd >/dev/null
    echo "ERROR: Playwright failed. See $LOG_DIR/06-e2e.log" >&2
    exit 5
  }
  popd >/dev/null
else
  echo "==> [6/6] SKIP E2E (SKIP_E2E=1)"
fi

# -----------------------------------------------------------------------------
# Summary
# -----------------------------------------------------------------------------

cat <<EOF

==> P1' verify completed successfully

   Stack:        $COMPOSE_PROJECT_NAME (PG :$PG_PORT, backend :$BACKEND_PORT, vite :$VITE_PORT)
   Logs:         $LOG_DIR
   Manual check: open http://localhost:$VITE_PORT/p/wd_leave_request
                 click 新建 → expect "智能填写" banner above form
                 click 智能填写 → input "下周三家里有事请假 2 天"
                 → expect form fields auto-filled

   Tear down:    docker compose --project-name $COMPOSE_PROJECT_NAME down -v
EOF
