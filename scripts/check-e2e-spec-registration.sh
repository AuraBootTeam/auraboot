#!/usr/bin/env bash
# Pre-push gate: no E2E spec may exist without being selectable by some project.
# A name-allowlist testMatch fails open — an unregistered spec runs as
# "No tests found" + exit 0, so it looks like coverage while never running.
# Covered by the "run check-*.sh before push" rule (AGENTS §18).
#   ./scripts/check-e2e-spec-registration.sh
#   ./scripts/check-e2e-spec-registration.sh --json
set -euo pipefail
cd "$(dirname "$0")/.."
exec node scripts/check-e2e-spec-registration.mjs "$@"
