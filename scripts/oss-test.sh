#!/usr/bin/env bash
# Run Playwright E2E tests restricted to the OSS scope defined in oss-scope.json.
#
# Usage:
#   ./scripts/oss-test.sh                    # run all OSS tests
#   ./scripts/oss-test.sh --smoke            # run with PW_PROFILE=smoke (tags @smoke as resolved by base playwright.config.ts profile)
#   ./scripts/oss-test.sh --bpm-regression   # run the aggregated spec-1 BPM regression suite
#                                            #   (all specs tagged @bpm-regression — see web-admin/tests/e2e/bpm/README.md)
#   ./scripts/oss-test.sh <glob>...          # run subset matching extra glob(s) that must already be in scope
#
# Reads:  oss-scope.json (at repo root)
# Invokes: web-admin/npx playwright test <paths...>
#
# Strategy:
#   - auth / regular / deep are all part of the OSS full gate
#   - regular and deep run sequentially, not in one mixed invocation
#   - deep correctness matters, but heavy specs must not contend with the main
#     suite for browser/dev-server resources
#   - this script assumes reset-and-init (or an equivalent manual start) has
#     already brought backend + web + bff online, so Playwright should reuse
#     the running services instead of starting another webServer per phase

set -euo pipefail

EXTRA_ARGS=()
SUITE_LABEL=""
CONFIG_OVERRIDE=""
for arg in "$@"; do
  case "$arg" in
    --bpm-regression)
      CONFIG_OVERRIDE="playwright.bpm-regression.config.ts"
      SUITE_LABEL="bpm-regression"
      ;;
    --smoke)
      export PW_PROFILE=smoke
      SUITE_LABEL="smoke"
      ;;
    *)
      EXTRA_ARGS+=("$arg")
      ;;
  esac
done

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

PW_CONFIG="${CONFIG_OVERRIDE:-playwright.oss.config.ts}"
export PW_SKIP_WEBSERVER="${PW_SKIP_WEBSERVER:-1}"

echo "=== AuraBoot OSS Test Runner ==="
echo "Scope file:    $SCOPE_FILE"
echo "Spec files:    $COUNT"
echo "Playwright config: $PW_CONFIG"
echo "Reuse running web server: PW_SKIP_WEBSERVER=$PW_SKIP_WEBSERVER"
if [[ -n "$SUITE_LABEL" ]]; then
  echo "Suite:         $SUITE_LABEL"
fi
echo ""

# Preflight: many specs depend on the internal test-fixtures plugin (e2et_*
# models / commands). It is not imported by default. Detect missing fixtures
# and warn loudly so the operator can rerun reset-and-init with
# IMPORT_TEST_FIXTURES=true (or AURA_ENV=test) before the suite drops dozens
# of unrelated red regressions on the floor.
FIXTURE_PROBE=$(curl -sS -o /dev/null -w "%{http_code}" \
  "http://localhost:6443/api/meta/commands?modelCode=e2et_order" 2>/dev/null || echo "000")
if [[ "$FIXTURE_PROBE" != "200" ]]; then
  echo "WARNING: test-fixtures plugin not detected (probe HTTP $FIXTURE_PROBE)."
  echo "         Many saved-view / list-ux / platform specs will fail with"
  echo "         'Command not found: e2et:create_order'."
  echo "         Fix:  IMPORT_TEST_FIXTURES=true ./scripts/oss-reset-and-init.sh"
  echo "         Or import directly via /api/plugins/import/import-directory-sync."
  echo ""
fi

LOG="/tmp/pw-oss-$(date +%Y%m%d-%H%M%S).log"
echo "Log: $LOG"
echo ""

run_phase() {
  local label="$1"
  shift

  echo "=== Phase: $label ===" | tee -a "$LOG"
  NO_PROXY=localhost npx playwright test -c "$PW_CONFIG" "$@" 2>&1 | tee -a "$LOG"
}

run_gate_phase() {
  local label="$1"
  shift

  echo "=== Phase: $label ===" | tee -a "$LOG"
  NO_PROXY=localhost npx playwright test -c "$PW_CONFIG" "$@" ${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"} 2>&1 | tee -a "$LOG"
}

if [[ "$PW_CONFIG" != "playwright.oss.config.ts" ]]; then
  run_gate_phase "suite"
  exit ${PIPESTATUS[0]}
fi

PROFILE="${PW_PROFILE:-fast}"
EXIT_CODE=0

run_phase "auth" --project=auth || EXIT_CODE=$?

case "$PROFILE" in
  fast|full)
    run_gate_phase "chromium" --project=chromium --no-deps || EXIT_CODE=$?
    if [[ "${OSS_TEST_SKIP_DEEP:-false}" == "true" ]]; then
      echo "=== Phase: chromium-deep skipped (OSS_TEST_SKIP_DEEP=true) ===" | tee -a "$LOG"
    else
      run_gate_phase "chromium-deep" --project=chromium-deep --no-deps --workers=1 || EXIT_CODE=$?
    fi
    if [[ "$PROFILE" == "full" ]]; then
      run_gate_phase "api" --project=api --no-deps || EXIT_CODE=$?
    fi
    ;;
  *)
    run_gate_phase "$PROFILE" || EXIT_CODE=$?
    ;;
esac

exit "$EXIT_CODE"
