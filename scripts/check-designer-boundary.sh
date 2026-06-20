#!/usr/bin/env bash
# Pre-push designer boundary gate (wraps check-designer-boundary.mjs).
# Run before pushing changes under app/plugins/core-designer (or any *-designer
# surface): blocks if a designer surface is missing — or has an invalid —
# designer.family.json. B3a manifest-only; enforces the early-defense-line half
# of DDR-2026-06-18-designer-kernel-boundary (enterprise canonical).
# Covered by the "run check-*.sh before push" rule (AGENTS §18).
#   ./scripts/check-designer-boundary.sh            # block on error
#   ./scripts/check-designer-boundary.sh --strict   # also block on warnings
set -euo pipefail
cd "$(dirname "$0")/.."
exec node scripts/check-designer-boundary.mjs "$@"
