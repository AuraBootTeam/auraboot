#!/usr/bin/env bash
# GA Follow-up E2E stack teardown — thin wrapper over stop-isolated.
# (See DDR-2026-05-26 docker-stack-convergence.)
#
# Usage:
#   ./scripts/docker-ga-e2e-down.sh           # stop + remove containers, KEEP volumes
#   ./scripts/docker-ga-e2e-down.sh --purge   # also drop volumes (fresh DB next up)
set -euo pipefail

cd "$(dirname "$0")/.."

exec scripts/dev/stop-isolated.sh --slug=ga-e2e "$@"
