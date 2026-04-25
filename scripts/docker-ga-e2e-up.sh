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
