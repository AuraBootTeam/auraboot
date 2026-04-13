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

cd "$WEB_ADMIN_DIR"

# Count spec files in scope (for reporting only — the actual filter is applied
# by playwright.oss.config.ts which reads oss-scope.json and builds per-project
# testMatch regexes. CLI positional filtering is unreliable because the default
# config sets testDir + testMatch at the project level.)
COUNT=0
while IFS= read -r entry; do
  if [[ "$entry" == *.ts ]]; then
    [[ -f "$entry" ]] && COUNT=$((COUNT + 1))
  else
    dir="${entry%/\*\*}"
    [[ -d "$dir" ]] && COUNT=$((COUNT + $(find "$dir" -name "*.spec.ts" 2>/dev/null | wc -l)))
  fi
done < <(jq -r '.test_paths[]' "$SCOPE_FILE")

echo "=== AuraBoot OSS Test Runner ==="
echo "Scope file:    $SCOPE_FILE"
echo "Spec files:    $COUNT"
echo "Playwright config: playwright.oss.config.ts"
echo ""

LOG="/tmp/pw-oss-$(date +%Y%m%d-%H%M%S).log"
echo "Log: $LOG"
echo ""

NO_PROXY=localhost npx playwright test -c playwright.oss.config.ts "$@" 2>&1 | tee "$LOG"
