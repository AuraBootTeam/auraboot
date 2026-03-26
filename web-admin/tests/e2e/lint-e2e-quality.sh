#!/bin/bash
# E2E Test Quality Lint — enforces docs/e2e/07-跨平台E2E质量强化规范.md
#
# Usage:
#   ./tests/e2e/lint-e2e-quality.sh                    # Full scan (report only)
#   ./tests/e2e/lint-e2e-quality.sh --strict            # Exit 1 if violations found
#   ./tests/e2e/lint-e2e-quality.sh --file path.spec.ts # Scan single file
#
# Checks:
#   P0: .catch(() => false) + API fallback (silent degradation)
#   P1: waitForTimeout (fixed delays)
#   P2: test.skip in test body (masking missing features)
#   P3: toBeLessThan(500) (accepting 4xx errors)
#   P4: toBeDefined() without further assertions (weak check)
#   P5: API calls in test body (should be UI-driven)

set -euo pipefail

E2E_DIR="$(cd "$(dirname "$0")" && pwd)"
STRICT=false
TARGET=""
VIOLATIONS=0

for arg in "$@"; do
  case "$arg" in
    --strict) STRICT=true ;;
    --file) ;; # next arg is the file
    *) TARGET="$arg" ;;
  esac
done

if [[ -n "$TARGET" ]]; then
  FILES=("$TARGET")
else
  FILES=()
  while IFS= read -r f; do FILES+=("$f"); done < <(find "$E2E_DIR" -name "*.spec.ts" -not -path "*/node_modules/*" | sort)
fi

RED='\033[0;31m'
YELLOW='\033[0;33m'
GREEN='\033[0;32m'
NC='\033[0m'

check_pattern() {
  local label="$1"
  local severity="$2"
  local pattern="$3"
  local count=0

  for f in "${FILES[@]}"; do
    local matches
    matches=$(grep -cn "$pattern" "$f" 2>/dev/null || echo 0)
    if [[ "$matches" -gt 0 ]]; then
      count=$((count + matches))
      if [[ "$severity" == "P0" || "$severity" == "P1" ]]; then
        echo -e "  ${RED}[$severity]${NC} $f: $matches occurrences"
      else
        echo -e "  ${YELLOW}[$severity]${NC} $f: $matches occurrences"
      fi
    fi
  done

  VIOLATIONS=$((VIOLATIONS + count))
  return $count
}

echo "========================================="
echo "  E2E Test Quality Lint"
echo "  Scanning ${#FILES[@]} spec files"
echo "========================================="
echo ""

echo "--- P0: Silent fallback (.catch(() => false)) ---"
check_pattern "catch-false" "P0" 'catch(() => false)' || true
echo ""

echo "--- P1: Fixed delays (waitForTimeout) ---"
check_pattern "waitForTimeout" "P1" 'waitForTimeout' || true
echo ""

echo "--- P2: test.skip in test body (masking features) ---"
check_pattern "test-skip-body" "P2" 'test\.skip(true' || true
echo ""

echo "--- P3: Weak status check (toBeLessThan(500)) ---"
check_pattern "weak-status" "P3" 'toBeLessThan(500)' || true
echo ""

echo "--- P4: Weak assertion (toBeDefined() alone) ---"
check_pattern "toBeDefined-only" "P4" 'toBeDefined()' || true
echo ""

echo "--- P5: API in test body (page.request.post.*execute) ---"
# Only count page.request in test() body, not in beforeAll
for f in "${FILES[@]}"; do
  # Simple heuristic: count page.request lines NOT preceded by beforeAll
  api_in_body=$(grep -cn 'page\.request\.\(post\|put\|delete\)' "$f" 2>/dev/null || echo 0)
  api_in_body=$(echo "$api_in_body" | tr -d '[:space:]')
  if [[ "$api_in_body" -gt 3 ]]; then
    echo -e "  ${YELLOW}[P5]${NC} $f: $api_in_body API calls in test body (should be UI-driven)"
    VIOLATIONS=$((VIOLATIONS + api_in_body))
  fi
done
echo ""

echo "========================================="
if [[ "$VIOLATIONS" -gt 0 ]]; then
  echo -e "  ${RED}Total violations: $VIOLATIONS${NC}"
  echo "  See: docs/e2e/07-跨平台E2E质量强化规范.md"
else
  echo -e "  ${GREEN}No violations found!${NC}"
fi
echo "========================================="

if [[ "$STRICT" == true && "$VIOLATIONS" -gt 0 ]]; then
  exit 1
fi
