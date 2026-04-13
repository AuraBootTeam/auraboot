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

# Expand manifest test_paths (single file or dir/**) into concrete .spec.ts files.
# Playwright CLI positional args are regex-matched against test file paths, which
# over-matches when using directory names that appear as substrings elsewhere.
# Pass explicit file paths to avoid cross-plugin leakage.
FILES=()
while IFS= read -r entry; do
  if [[ "$entry" == *.ts ]]; then
    [[ -f "$entry" ]] && FILES+=("$entry")
  else
    dir="${entry%/\*\*}"
    while IFS= read -r f; do
      FILES+=("$f")
    done < <(find "$dir" -name "*.spec.ts" 2>/dev/null)
  fi
done < <(jq -r '.test_paths[]' "$SCOPE_FILE")

echo "=== AuraBoot OSS Test Runner ==="
echo "Scope file:    $SCOPE_FILE"
echo "Spec files:    ${#FILES[@]}"
echo ""

LOG="/tmp/pw-oss-$(date +%Y%m%d-%H%M%S).log"
echo "Log: $LOG"
echo ""

NO_PROXY=localhost npx playwright test "${FILES[@]}" "$@" 2>&1 | tee "$LOG"
