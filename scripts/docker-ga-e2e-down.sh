#!/usr/bin/env bash
# Tear down the GA Follow-up E2E stack and (optionally) its volumes.
#
# Usage:
#   ./scripts/docker-ga-e2e-down.sh           # stop + remove containers, KEEP volumes
#   ./scripts/docker-ga-e2e-down.sh --purge   # also remove postgres/backend volumes (fresh DB next up)

set -euo pipefail

cd "$(dirname "$0")/.."

export COMPOSE_PROJECT_NAME=auraboot-ga-e2e

if [ "${1:-}" = "--purge" ]; then
  echo "[ga-e2e] tearing down stack + volumes..."
  docker compose \
    -f docker-compose.yml \
    -f docker-compose.ga-e2e.override.yml \
    --profile ga-e2e-stack \
    --profile ga-e2e-runner \
    down -v
else
  echo "[ga-e2e] tearing down stack (volumes preserved)..."
  docker compose \
    -f docker-compose.yml \
    -f docker-compose.ga-e2e.override.yml \
    --profile ga-e2e-stack \
    --profile ga-e2e-runner \
    down
fi
