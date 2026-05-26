#!/usr/bin/env bash
# GA Follow-up E2E stack — thin wrapper over the converged isolated stack.
#
# As of the 2026-05-26 docker-stack convergence (DDR-2026-05-26), this is no
# longer a bespoke stack. start-isolated knows the `ga-e2e` slug (offset 0 →
# GA's historical ports: backend 6444 / vite 5174 / BFF 3501 / pg 5433) and
# builds the backend in-container against an isolated ~/.m2 cache, so concurrent
# worktrees no longer collide on host gradle / host ~/.m2.
#
# Seeding (admin + plugins + showcase) stays in docker-ga-e2e-bootstrap.sh,
# which targets these same ports unchanged. Run up → bootstrap as before.
#
# Pass-through args go to start-isolated (e.g. --rebuild, --e2e).
set -euo pipefail

cd "$(dirname "$0")/.."

exec scripts/dev/start-isolated.sh --slug=ga-e2e --wait "$@"
