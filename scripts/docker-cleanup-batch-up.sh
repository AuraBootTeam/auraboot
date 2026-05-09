#!/usr/bin/env bash
# Bring up the cleanup-batch isolated stack on shifted ports (postgres 6533 /
# backend 7544 / vite 6274 / BFF 4601). Forked from docker-ga-e2e-up.sh.

set -euo pipefail

cd "$(dirname "$0")/.."

export COMPOSE_PROJECT_NAME=auraboot-cleanup-batch

WRAPPER_JAR="platform/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$WRAPPER_JAR" ]; then
  candidates=(
    "../../auraboot/platform/gradle/wrapper/gradle-wrapper.jar"
    "$HOME/work/auraboot/auraboot/platform/gradle/wrapper/gradle-wrapper.jar"
  )
  found=""
  for c in "${candidates[@]}"; do
    [ -f "$c" ] && { found="$c"; break; }
  done
  if [ "${AURABOOT_AUTO_COPY_WRAPPER:-0}" = "1" ] && [ -n "$found" ]; then
    cp "$found" "$WRAPPER_JAR"
    echo "[cleanup-batch] copied wrapper jar from $found" >&2
  else
    echo "[cleanup-batch] $WRAPPER_JAR missing." >&2
    [ -n "$found" ] && echo "[cleanup-batch] candidate at: $found (re-run with AURABOOT_AUTO_COPY_WRAPPER=1)" >&2
    exit 1
  fi
fi

CLEANUP_BATCH_BACKEND_BUILD_MODE="${CLEANUP_BATCH_BACKEND_BUILD_MODE:-host-jar}"
case "$CLEANUP_BATCH_BACKEND_BUILD_MODE" in
  host-jar)
    echo "[cleanup-batch] building backend jar on host..."
    (cd platform && ./gradlew bootJar --no-daemon -x test)
    export CLEANUP_BATCH_BACKEND_DOCKERFILE="${CLEANUP_BATCH_BACKEND_DOCKERFILE:-Dockerfile.runtime}"
    ;;
  dockerfile)
    export CLEANUP_BATCH_BACKEND_DOCKERFILE="${CLEANUP_BATCH_BACKEND_DOCKERFILE:-Dockerfile}"
    ;;
  *)
    echo "[cleanup-batch] unsupported mode=$CLEANUP_BATCH_BACKEND_BUILD_MODE" >&2; exit 1;;
esac

echo "[cleanup-batch] starting stack (project=$COMPOSE_PROJECT_NAME)..."
docker compose \
  -f docker-compose.yml \
  -f docker-compose.cleanup-batch.override.yml \
  --profile cleanup-batch-stack \
  up -d --build

echo "[cleanup-batch] waiting for backend health (max 240s)..."
deadline=$(( $(date +%s) + 240 ))
while :; do
  if curl -fsS http://localhost:7544/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then
    echo "[cleanup-batch] backend UP on :7544"; break
  fi
  if [ "$(date +%s)" -ge "$deadline" ]; then
    echo "[cleanup-batch] backend health timed out" >&2
    docker compose -p "$COMPOSE_PROJECT_NAME" logs --tail=80 backend >&2
    exit 1
  fi
  sleep 3
done

echo "[cleanup-batch] waiting for frontend (vite+BFF), allow ~5min..."
deadline=$(( $(date +%s) + 360 ))
while :; do
  if curl -fsS -o /dev/null http://localhost:6274 2>/dev/null \
     && curl -fsS -o /dev/null http://localhost:4601/api/bootstrap/status 2>/dev/null; then
    echo "[cleanup-batch] frontend up: vite :6274, BFF :4601"; break
  fi
  if [ "$(date +%s)" -ge "$deadline" ]; then
    echo "[cleanup-batch] frontend health timed out" >&2
    docker compose -p "$COMPOSE_PROJECT_NAME" logs --tail=120 cleanup-batch-frontend >&2
    exit 1
  fi
  sleep 5
done

echo "[cleanup-batch] stack ready:"
echo "  backend  http://localhost:7544"
echo "  postgres localhost:6533  user=auraboot db=aura_boot"
echo "  vite     http://localhost:6274"
echo "  BFF      http://localhost:4601"
