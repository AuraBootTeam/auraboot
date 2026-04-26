#!/usr/bin/env bash
# Tail logs from the GA Follow-up E2E stack.
#
# Usage:
#   ./scripts/docker-ga-e2e-logs.sh                # all services
#   ./scripts/docker-ga-e2e-logs.sh backend        # one service
#   ./scripts/docker-ga-e2e-logs.sh backend 200    # one service + tail count

set -euo pipefail

cd "$(dirname "$0")/.."

export COMPOSE_PROJECT_NAME=auraboot-ga-e2e

svc="${1:-}"
tail="${2:-100}"

if [ -n "$svc" ]; then
  exec docker compose \
    -f docker-compose.yml \
    -f docker-compose.ga-e2e.override.yml \
    --profile ga-e2e-stack \
    logs -f --tail="$tail" "$svc"
else
  exec docker compose \
    -f docker-compose.yml \
    -f docker-compose.ga-e2e.override.yml \
    --profile ga-e2e-stack \
    logs -f --tail="$tail"
fi
