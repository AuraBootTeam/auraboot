#!/usr/bin/env bash
# Pre-push docs governance gate (wraps check-docs-governance.mjs --git).
# Run before pushing doc changes — blocks on frontmatter / precipitation
# violations in post-baseline docs. Covered by the "run check-*.sh before push" rule.
#   ./scripts/check-docs-governance.sh            # block on error (warnings allowed)
#   ./scripts/check-docs-governance.sh --strict   # also block on warnings
set -euo pipefail
cd "$(dirname "$0")/.."
exec node scripts/check-docs-governance.mjs --git "$@"
