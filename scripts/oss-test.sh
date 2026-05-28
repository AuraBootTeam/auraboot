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
      # Some @smoke-tagged specs reference e2et_* fixtures (e.g.
      # tests/e2e/e2et-order/e2et-query-operators.spec.ts), so smoke runs
      # need fixtures too. PW_PROFILE=smoke isn't in the setup project's
      # gate list, so signal IMPORT_TEST_FIXTURES instead.
      export IMPORT_TEST_FIXTURES=true
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

API_COUNT=$(jq '[.test_paths[] | select(startswith("tests/api/"))] | length' "$SCOPE_FILE")

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

# Default PW_PROFILE=oss so the Playwright setup project
# (tests/api/setup/03-import-test-fixtures.spec.ts) auto-imports the internal
# `test-fixtures` plugin. Without this, 60+ specs that depend on e2et_* models
# fail with `Command not found: e2et:create_order`. --smoke already exported
# PW_PROFILE=smoke above; ${PW_PROFILE:-oss} preserves any caller override.
export PW_PROFILE="${PW_PROFILE:-oss}"

echo "=== AuraBoot OSS Test Runner ==="
echo "Scope file:    $SCOPE_FILE"
echo "Spec files:    $COUNT"
echo "Playwright config: $PW_CONFIG"
echo "PW_PROFILE:    $PW_PROFILE"
echo "Reuse running web server: PW_SKIP_WEBSERVER=$PW_SKIP_WEBSERVER"
if [[ -n "$SUITE_LABEL" ]]; then
  echo "Suite:         $SUITE_LABEL"
fi
echo ""

# Preflight (red line §4.1 — fail fast if env can't satisfy fixture import):
# The Playwright setup project at tests/api/setup/03-import-test-fixtures.spec.ts
# auto-imports the internal `test-fixtures` plugin when ANY of these gates are
# set: AURA_ENV=test, IMPORT_TEST_FIXTURES=true, PW_PROFILE=oss, PW_PROFILE=full.
# If a caller has explicitly cleared all four (e.g. PW_PROFILE=core override),
# fixtures won't import and ~60 specs reference e2et_* models — refuse to run.
FIXTURE_GATE=""
if [[ "${AURA_ENV:-}" == "test" ]]; then FIXTURE_GATE="AURA_ENV=test"
elif [[ "${IMPORT_TEST_FIXTURES:-}" == "true" || "${IMPORT_TEST_FIXTURES:-}" == "TRUE" ]]; then FIXTURE_GATE="IMPORT_TEST_FIXTURES=true"
elif [[ "$PW_PROFILE" == "oss" || "$PW_PROFILE" == "full" ]]; then FIXTURE_GATE="PW_PROFILE=$PW_PROFILE"
fi
if [[ -n "$FIXTURE_GATE" ]]; then
  echo "test-fixtures auto-import: enabled via $FIXTURE_GATE (setup project will import)"
  echo ""
elif [[ "${ALLOW_MISSING_FIXTURES:-}" == "1" ]]; then
  echo "WARNING: ALLOW_MISSING_FIXTURES=1 set — proceeding without test-fixtures."
  echo "         Specs that reference e2et_* models will fail."
  echo ""
else
  echo "ERROR: no test-fixtures auto-import gate is set." >&2
  echo "       Caller has cleared all of AURA_ENV / IMPORT_TEST_FIXTURES /" >&2
  echo "       PW_PROFILE=oss|full. ~60 specs reference e2et_* models and will" >&2
  echo "       drop unrelated red regressions on the floor." >&2
  echo "" >&2
  echo "Fix one of:" >&2
  echo "  - Drop your PW_PROFILE override (default is now 'oss')" >&2
  echo "  - export IMPORT_TEST_FIXTURES=true" >&2
  echo "  - export AURA_ENV=test" >&2
  echo "" >&2
  echo "Escape hatch (only if you know your scope excludes e2et_*):" >&2
  echo "  ALLOW_MISSING_FIXTURES=1 ./scripts/oss-test.sh ..." >&2
  exit 78  # EX_CONFIG
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
      if [[ "$API_COUNT" -gt 0 ]]; then
        run_gate_phase "api" --project=api --no-deps || EXIT_CODE=$?
      else
        echo "=== Phase: api skipped (no OSS-scoped tests/api entries) ===" | tee -a "$LOG"
      fi
    fi
    ;;
  *)
    run_gate_phase "$PROFILE" || EXIT_CODE=$?
    ;;
esac

exit "$EXIT_CODE"
