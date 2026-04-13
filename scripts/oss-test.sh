#!/usr/bin/env bash
# Run Playwright E2E tests restricted to the OSS scope defined in oss-scope.json.
#
# Usage:
#   ./scripts/oss-test.sh              # run all OSS tests
#   ./scripts/oss-test.sh --smoke      # run with PW_PROFILE=smoke
#   ./scripts/oss-test.sh <glob>...    # run subset matching extra glob(s) that must already be in scope
#
# Reads:  oss-scope.json (at repo root)
# Invokes: web-admin/npx playwright test <paths...>

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
SCOPE_FILE="$REPO_ROOT/oss-scope.json"
WEB_ADMIN_DIR="$REPO_ROOT/web-admin"

if [[ ! -f "$SCOPE_FILE" ]]; then
  echo "ERROR: oss-scope.json not found at $SCOPE_FILE" >&2
  exit 1
fi

if ! command -v jq >/dev/null; then
  echo "ERROR: jq is required. brew install jq" >&2
  exit 1
fi

# Extract test_paths from scope file
PATHS=()
while IFS= read -r path; do
  PATHS+=("$path")
done < <(jq -r '.test_paths[]' "$SCOPE_FILE")

echo "=== AuraBoot OSS Test Runner ==="
echo "Scope file: $SCOPE_FILE"
echo "Paths in scope: ${#PATHS[@]}"
echo ""

LOG="/tmp/pw-oss-$(date +%Y%m%d-%H%M%S).log"
echo "Log: $LOG"
echo ""

cd "$WEB_ADMIN_DIR"
NO_PROXY=localhost npx playwright test "${PATHS[@]}" "$@" 2>&1 | tee "$LOG"
