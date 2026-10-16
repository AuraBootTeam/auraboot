#!/usr/bin/env bash
# Pre-push controller-authz fail-open gate (wraps check-controller-authz.mjs).
# Run before pushing Controller changes — blocks when a NEW write/sensitive-read
# controller appears without @RequirePermission/@AuthenticatedAccess/@RequirePlatformAdmin.
# Covered by the "run check-*.sh before push" rule.
#
# The GitHub Actions workflow (.github/workflows/controller-authz.yml) stopped running on
# 2026-09-11 when repo-level GitHub Actions was disabled (actions/permissions enabled=false,
# permanent per workspace policy) — this wrapper keeps the gate runnable locally.
#   ./scripts/check-controller-authz.sh                  # block on new drift
#   ./scripts/check-controller-authz.sh --write-baseline # regenerate the baseline
set -euo pipefail
cd "$(dirname "$0")/.."
exec node scripts/check-controller-authz.mjs "$@"
