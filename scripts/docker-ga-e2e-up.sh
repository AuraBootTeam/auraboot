#!/usr/bin/env bash
# Bring up the GA Follow-up E2E stack on isolated ports.
#
# Compose project: auraboot-ga-e2e
# Profile        : ga-e2e-stack (backend + postgres only; no frontend service)
#
# After up, wait for backend health before exiting.

set -euo pipefail

cd "$(dirname "$0")/.."

export COMPOSE_PROJECT_NAME=auraboot-ga-e2e

# Guard: gradle-wrapper.jar is in .gitignore, so a fresh worktree will be
# missing it and the backend Dockerfile build will fail with
# "ClassNotFoundException: org.gradle.wrapper.GradleWrapperMain".
# If it's missing, try to source it from the closest sibling worktree.
WRAPPER_JAR="platform/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$WRAPPER_JAR" ]; then
  echo "[ga-e2e] $WRAPPER_JAR missing — locating sibling copy..." >&2
  for candidate in \
    "../../platform/gradle/wrapper/gradle-wrapper.jar" \
    "$HOME/work/auraboot/auraboot/platform/gradle/wrapper/gradle-wrapper.jar"; do
    if [ -f "$candidate" ]; then
      cp "$candidate" "$WRAPPER_JAR"
      echo "[ga-e2e] copied wrapper jar from $candidate" >&2
      break
    fi
  done
  if [ ! -f "$WRAPPER_JAR" ]; then
    echo "[ga-e2e] cannot locate gradle-wrapper.jar — install gradle and run \`gradle wrapper\` in platform/" >&2
    exit 1
  fi
fi

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

echo "[ga-e2e] stack ready:"
echo "  backend  http://localhost:6444  (actuator/health)"
echo "  postgres localhost:5433  user=auraboot db=aura_boot"
echo
echo "[ga-e2e] frontend NOT in this stack — start the host vite/BFF dev"
echo "         server with SPRING_BOOT_URL=http://localhost:6444 to point"
echo "         it at this isolated backend."
