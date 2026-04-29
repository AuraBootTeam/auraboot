#!/usr/bin/env bash
# Bring up the GA Follow-up E2E stack on isolated ports.
#
# Compose project: auraboot-ga-e2e
# Profile        : ga-e2e-stack (postgres + backend + GA frontend service)
#
# After up, wait for backend health before exiting.

set -euo pipefail

cd "$(dirname "$0")/.."

export COMPOSE_PROJECT_NAME=auraboot-ga-e2e

# Guard: gradle-wrapper.jar is in .gitignore, so a fresh worktree will be
# missing it and the backend Dockerfile build will fail with
# "ClassNotFoundException: org.gradle.wrapper.GradleWrapperMain".
#
# This script CAN auto-copy the jar from a sibling worktree, but only
# when the operator opts in via AURABOOT_AUTO_COPY_WRAPPER=1. Silent
# auto-copy is rejected because:
#   - It can pull a wrong-version jar from an unrelated branch and the
#     resulting build failure is one indirection further from the cause.
#   - It blurs the "禁止自愈" red line in AGENTS.md; even tooling-side
#     auto-recovery should be visible to the operator.
# Without the env var the script fails loud with the source candidates
# it considered, and the operator decides whether to copy or
# regenerate via `gradle wrapper`.
WRAPPER_JAR="platform/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$WRAPPER_JAR" ]; then
  candidates=(
    "../../platform/gradle/wrapper/gradle-wrapper.jar"
    "$HOME/work/auraboot/auraboot/platform/gradle/wrapper/gradle-wrapper.jar"
  )
  found=""
  for c in "${candidates[@]}"; do
    [ -f "$c" ] && { found="$c"; break; }
  done

  if [ "${AURABOOT_AUTO_COPY_WRAPPER:-0}" = "1" ] && [ -n "$found" ]; then
    src_sha=$(shasum -a 256 "$found" | awk '{print $1}')
    cp "$found" "$WRAPPER_JAR"
    echo "[ga-e2e] copied wrapper jar from $found (sha256=$src_sha)" >&2
  else
    echo "[ga-e2e] $WRAPPER_JAR missing." >&2
    if [ -n "$found" ]; then
      src_sha=$(shasum -a 256 "$found" | awk '{print $1}')
      echo "[ga-e2e] candidate found at: $found (sha256=$src_sha)" >&2
      echo "[ga-e2e] re-run with AURABOOT_AUTO_COPY_WRAPPER=1 to copy it," >&2
      echo "[ga-e2e] or run \`(cd platform && gradle wrapper)\` to regenerate." >&2
    else
      echo "[ga-e2e] no sibling candidate located. Install gradle and run" >&2
      echo "[ga-e2e]   (cd platform && gradle wrapper --gradle-version <ver>)" >&2
      echo "[ga-e2e] using the version pinned in platform/gradle/wrapper/gradle-wrapper.properties." >&2
    fi
    exit 1
  fi
fi

GA_E2E_BACKEND_BUILD_MODE="${GA_E2E_BACKEND_BUILD_MODE:-host-jar}"
case "$GA_E2E_BACKEND_BUILD_MODE" in
  host-jar)
    echo "[ga-e2e] building backend jar on host with Gradle cache..."
    (cd platform && ./gradlew bootJar --no-daemon -x test)
    export GA_E2E_BACKEND_DOCKERFILE="${GA_E2E_BACKEND_DOCKERFILE:-Dockerfile.runtime}"
    ;;
  dockerfile)
    export GA_E2E_BACKEND_DOCKERFILE="${GA_E2E_BACKEND_DOCKERFILE:-Dockerfile}"
    ;;
  *)
    echo "[ga-e2e] unsupported GA_E2E_BACKEND_BUILD_MODE=$GA_E2E_BACKEND_BUILD_MODE" >&2
    echo "[ga-e2e] supported values: host-jar, dockerfile" >&2
    exit 1
    ;;
esac

echo "[ga-e2e] starting stack (project=$COMPOSE_PROJECT_NAME)..."
docker compose \
  -f docker-compose.yml \
  -f docker-compose.ga-e2e.override.yml \
  --profile ga-e2e-stack \
  up -d --build

echo "[ga-e2e] waiting for backend health (max 180s)..."
deadline=$(( $(date +%s) + 180 ))
while :; do
  if curl -fsS http://localhost:6444/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then
    echo "[ga-e2e] backend UP on :6444"
    break
  fi
  if [ "$(date +%s)" -ge "$deadline" ]; then
    echo "[ga-e2e] backend health check timed out" >&2
    docker compose -p "$COMPOSE_PROJECT_NAME" logs --tail=80 backend >&2
    exit 1
  fi
  sleep 3
done

echo "[ga-e2e] waiting for frontend (vite+BFF) — first run installs deps, allow ~5min..."
deadline=$(( $(date +%s) + 360 ))
while :; do
  # vite first, then BFF via a real health-adjacent API. BFF root returns 404.
  if curl -fsS -o /dev/null http://localhost:5174 2>/dev/null \
     && curl -fsS -o /dev/null http://localhost:3501/api/bootstrap/status 2>/dev/null; then
    echo "[ga-e2e] frontend up: vite :5174, BFF :3501"
    break
  fi
  if [ "$(date +%s)" -ge "$deadline" ]; then
    echo "[ga-e2e] frontend health check timed out" >&2
    docker compose -p "$COMPOSE_PROJECT_NAME" logs --tail=120 ga-e2e-frontend >&2
    exit 1
  fi
  sleep 5
done

echo "[ga-e2e] stack ready:"
echo "  backend  http://localhost:6444  (actuator/health)"
echo "  postgres localhost:5433  user=auraboot db=aura_boot"
echo "  vite     http://localhost:5174"
echo "  BFF      http://localhost:3501"
echo
echo "[ga-e2e] run Playwright against this stack with:"
echo "  PLAYWRIGHT_BASE_URL=http://localhost:5174 PW_SKIP_WEBSERVER=1 \\"
echo "    npx playwright test ..."
