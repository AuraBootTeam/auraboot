#!/usr/bin/env bash
# Pre-push gate: every declared command needs a UI entry point.
# A command can be fully valid — handler, permission, input fields — and still
# be unreachable because no page DSL references it. Every other gate stays
# green; the only thing missing is a button.
# Covered by the "run check-*.sh before push" rule (AGENTS §18).
#   ./scripts/check-command-reachability.sh
#   ./scripts/check-command-reachability.sh --plugin-root ../plugins
set -euo pipefail
cd "$(dirname "$0")/.."
exec node scripts/check-command-reachability.mjs "$@"
