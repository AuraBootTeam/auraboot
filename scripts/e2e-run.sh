#!/usr/bin/env bash
# ============================================================================
# Unified E2E Test Runner — GAP-169
# ============================================================================
#
# Usage:
#   ./scripts/e2e-run.sh [scenario] [options]
#
# Scenarios:
#   smoke        — Smoke tests only (@smoke tag, fast ~2min)
#   critical     — Critical + smoke tests (@critical|@smoke)
#   crm          — CRM module tests
#   admin        — Admin/platform management tests
#   dashboard    — Dashboard tests
#   bpm          — BPM/approval workflow tests
#   designer     — Designer tests (page/report/etc)
#   inventory    — Inventory module tests
#   finance      — Finance module tests
#   sales        — Sales module tests
#   all          — Full E2E suite
#   <pattern>    — Custom grep pattern for test files
#
# Options:
#   --headed     — Run in headed mode (show browser)
#   --debug      — Debug mode (headed + slow motion + devtools)
#   --report     — Generate HTML report and open it
#   --ci         — CI mode (retries=2, JSON+HTML reporters)
#   --workers N  — Override worker count (default: 6)
#   --dry-run    — Print the command without executing
#   --help       — Show this help message
#
# Environment:
#   TEST_RUN_ID  — Override auto-generated test run ID
#   PW_WORKERS   — Override worker count (--workers takes precedence)
#
# Examples:
#   ./scripts/e2e-run.sh smoke
#   ./scripts/e2e-run.sh crm --headed
#   ./scripts/e2e-run.sh all --ci --report
#   ./scripts/e2e-run.sh "@smoke|@critical"
#   ./scripts/e2e-run.sh --help
#
# ============================================================================

set -euo pipefail

# --- Constants ---------------------------------------------------------------

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WEB_DIR="${ROOT_DIR}/web-admin"
RESULTS_BASE="${WEB_DIR}/test-results"
BACKEND_HEALTH_URL="http://localhost:6443/actuator/health"
FRONTEND_URL="http://localhost:5173"

# --- Colors ------------------------------------------------------------------

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
DIM='\033[2m'
RESET='\033[0m'

# --- Helper functions --------------------------------------------------------

info()  { echo -e "${BLUE}[INFO]${RESET}  $*"; }
ok()    { echo -e "${GREEN}[OK]${RESET}    $*"; }
warn()  { echo -e "${YELLOW}[WARN]${RESET}  $*"; }
err()   { echo -e "${RED}[ERROR]${RESET} $*"; }
header() {
  echo ""
  echo -e "${BOLD}${CYAN}━━━ $* ━━━${RESET}"
  echo ""
}

usage() {
  local count=0
  while IFS= read -r line; do
    if [[ "$line" =~ ^#\ =====+ ]]; then
      count=$(( count + 1 ))
      [[ $count -ge 3 ]] && break
      continue
    fi
    if [[ $count -ge 1 ]]; then
      # Strip leading "# " or "#"
      line="${line/#\# /}"
      line="${line/#\#/}"
      echo "$line"
    fi
  done < "$0"
  exit 0
}

# --- Generate TEST_RUN_ID (per contract: {platform}_{timestamp}_{random}) ----

generate_run_id() {
  local ts
  ts=$(date +%s)
  local rand
  rand=$(LC_ALL=C tr -dc 'a-f0-9' </dev/urandom | head -c 4)
  echo "web_${ts}_${rand}"
}

# --- Parse arguments ---------------------------------------------------------

SCENARIO=""
HEADED=false
DEBUG_MODE=false
REPORT=false
CI_MODE=false
DRY_RUN=false
WORKERS=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h)     usage ;;
    --headed)      HEADED=true; shift ;;
    --debug)       DEBUG_MODE=true; HEADED=true; shift ;;
    --report)      REPORT=true; shift ;;
    --ci)          CI_MODE=true; shift ;;
    --dry-run)     DRY_RUN=true; shift ;;
    --workers)     WORKERS="$2"; shift 2 ;;
    -*)            err "Unknown option: $1"; echo "Use --help for usage."; exit 1 ;;
    *)
      if [[ -z "$SCENARIO" ]]; then
        SCENARIO="$1"
      else
        err "Multiple scenarios not supported. Got: '$SCENARIO' and '$1'"
        exit 1
      fi
      shift
      ;;
  esac
done

# Default scenario
if [[ -z "$SCENARIO" ]]; then
  SCENARIO="smoke"
  warn "No scenario specified, defaulting to '${BOLD}smoke${RESET}'"
fi

# --- Generate run ID ---------------------------------------------------------

if [[ -z "${TEST_RUN_ID:-}" ]]; then
  TEST_RUN_ID=$(generate_run_id)
fi
export TEST_RUN_ID

# --- Map scenario to Playwright command fragments ----------------------------

PW_ARGS=()
PW_ENV=()
SCENARIO_LABEL=""

map_scenario() {
  local s="$1"
  case "$s" in
    smoke)
      SCENARIO_LABEL="Smoke Tests"
      PW_ENV+=("PW_PROFILE=smoke")
      ;;
    critical)
      SCENARIO_LABEL="Critical Tests"
      PW_ENV+=("PW_PROFILE=critical")
      ;;
    crm)
      SCENARIO_LABEL="CRM Module"
      PW_ARGS+=(tests/e2e/crm/)
      ;;
    admin)
      SCENARIO_LABEL="Admin / Platform"
      PW_ARGS+=(tests/e2e/admin/)
      ;;
    dashboard)
      SCENARIO_LABEL="Dashboard"
      PW_ARGS+=(tests/e2e/dashboard/)
      ;;
    bpm|approval)
      SCENARIO_LABEL="BPM / Approval"
      PW_ARGS+=(tests/e2e/bpm/ tests/e2e/approval/)
      ;;
    designer)
      SCENARIO_LABEL="Designers"
      PW_ARGS+=(tests/e2e/designer/)
      ;;
    inventory)
      SCENARIO_LABEL="Inventory"
      PW_ARGS+=(tests/e2e/inventory/)
      ;;
    finance)
      SCENARIO_LABEL="Finance"
      PW_ARGS+=(tests/e2e/finance/)
      ;;
    sales)
      SCENARIO_LABEL="Sales"
      PW_ARGS+=(tests/e2e/sales/)
      ;;
    plugin)
      SCENARIO_LABEL="Plugin System"
      PW_ARGS+=(tests/e2e/plugin/)
      ;;
    all)
      SCENARIO_LABEL="Full E2E Suite"
      PW_ENV+=("PW_PROFILE=full")
      ;;
    *)
      # Custom grep pattern
      SCENARIO_LABEL="Custom: ${s}"
      PW_ARGS+=(--grep "$s")
      ;;
  esac
}

map_scenario "$SCENARIO"

# --- Apply option flags ------------------------------------------------------

if $HEADED; then
  PW_ARGS+=(--headed)
fi

if $DEBUG_MODE; then
  # Slow down actions by 500ms for visual debugging
  PW_ENV+=("PWDEBUG=0")
  PW_ARGS+=(--timeout 60000)
fi

if $REPORT; then
  PW_ARGS+=(--reporter=html)
fi

if $CI_MODE; then
  PW_ARGS+=(--retries 2)
  PW_ENV+=("CI=1")
fi

if [[ -n "$WORKERS" ]]; then
  PW_ARGS+=(--workers "$WORKERS")
fi

# Always set NO_PROXY (project requirement)
PW_ENV+=("NO_PROXY=localhost,127.0.0.1")

# Forward the webserver skip if both frontend and BFF are already running
PW_ENV+=("PW_SKIP_WEBSERVER=1")

# --- Pre-flight checks -------------------------------------------------------

header "Pre-flight Checks"

# Check node_modules
if [[ ! -d "${WEB_DIR}/node_modules" ]]; then
  err "node_modules not found in web-admin/. Run: cd web-admin && pnpm install"
  exit 1
fi
ok "node_modules present"

# Check Playwright
if ! command -v npx &>/dev/null; then
  err "npx not found. Install Node.js."
  exit 1
fi
ok "npx available"

# Check backend health
if curl -sf "${BACKEND_HEALTH_URL}" -o /dev/null --connect-timeout 3 2>/dev/null; then
  ok "Backend healthy (port 6443)"
else
  warn "Backend not reachable at ${BACKEND_HEALTH_URL}"
  warn "Tests may fail if backend is required. Start with: cd platform && ./gradlew bootRun"
fi

# Check frontend
if curl -sf "${FRONTEND_URL}" -o /dev/null --connect-timeout 3 2>/dev/null; then
  ok "Frontend running (port 5173)"
else
  warn "Frontend not reachable at ${FRONTEND_URL}"
  info "Playwright webServer will attempt to start it automatically"
  # Don't skip webserver if frontend isn't running
  PW_ENV=("${PW_ENV[@]/PW_SKIP_WEBSERVER=1/}")
fi

# --- Build command -----------------------------------------------------------

header "Test Execution"

info "Scenario:    ${BOLD}${SCENARIO_LABEL}${RESET}"
info "Run ID:      ${BOLD}${TEST_RUN_ID}${RESET}"
info "Workers:     ${WORKERS:-6 (default)}"
info "Headed:      ${HEADED}"
info "CI mode:     ${CI_MODE}"

# Build the full command
CMD_PREFIX=""
for env_var in "${PW_ENV[@]}"; do
  # Skip empty entries (from array filtering)
  [[ -z "$env_var" ]] && continue
  CMD_PREFIX="${CMD_PREFIX}${env_var} "
done

CMD="cd ${WEB_DIR} && ${CMD_PREFIX}TEST_RUN_ID=${TEST_RUN_ID} npx playwright test ${PW_ARGS[*]:-}"

echo ""
echo -e "${DIM}$ ${CMD}${RESET}"
echo ""

if $DRY_RUN; then
  info "Dry run — exiting without executing."
  exit 0
fi

# --- Execute -----------------------------------------------------------------

START_TIME=$(date +%s)

set +e
(
  cd "${WEB_DIR}"
  env ${CMD_PREFIX} TEST_RUN_ID="${TEST_RUN_ID}" npx playwright test ${PW_ARGS[*]:-} 2>&1
)
EXIT_CODE=$?
set -e

END_TIME=$(date +%s)
DURATION=$(( END_TIME - START_TIME ))

# --- Results summary ---------------------------------------------------------

header "Results"

RESULTS_DIR="${RESULTS_BASE}/${TEST_RUN_ID}"
mkdir -p "${RESULTS_DIR}"

# Parse results from Playwright JSON output if available
RESULTS_JSON="${RESULTS_BASE}/results.json"
TOTAL=0
PASSED=0
FAILED=0
SKIPPED=0

if [[ -f "$RESULTS_JSON" ]]; then
  # Extract counts from Playwright JSON report
  if command -v python3 &>/dev/null; then
    eval "$(python3 -c "
import json, sys
try:
    with open('${RESULTS_JSON}') as f:
        data = json.load(f)
    suites = data.get('suites', [])
    total = passed = failed = skipped = 0
    def count_specs(suite):
        global total, passed, failed, skipped
        for spec in suite.get('specs', []):
            for test in spec.get('tests', []):
                total += 1
                status = test.get('status', '')
                if status == 'expected':
                    passed += 1
                elif status in ('unexpected', 'flaky'):
                    failed += 1
                elif status == 'skipped':
                    skipped += 1
        for sub in suite.get('suites', []):
            count_specs(sub)
    for s in suites:
        count_specs(s)
    print(f'TOTAL={total}')
    print(f'PASSED={passed}')
    print(f'FAILED={failed}')
    print(f'SKIPPED={skipped}')
except Exception as e:
    print(f'# Failed to parse results: {e}', file=sys.stderr)
" 2>/dev/null || true)"
  fi
fi

# Generate summary.json (per Test Session Contract)
TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
cat > "${RESULTS_DIR}/summary.json" <<SUMMARY_EOF
{
  "testRunId": "${TEST_RUN_ID}",
  "scenario": "${SCENARIO_LABEL}",
  "platforms": ["web"],
  "result": "$([ $EXIT_CODE -eq 0 ] && echo 'PASS' || echo 'FAIL')",
  "assertions": {
    "total": ${TOTAL},
    "passed": ${PASSED},
    "failed": ${FAILED},
    "skipped": ${SKIPPED}
  },
  "duration": $(( DURATION * 1000 )),
  "timestamp": "${TIMESTAMP}",
  "exitCode": ${EXIT_CODE}
}
SUMMARY_EOF

# Print summary
DURATION_MIN=$(( DURATION / 60 ))
DURATION_SEC=$(( DURATION % 60 ))

if [[ $EXIT_CODE -eq 0 ]]; then
  echo -e "  ${GREEN}${BOLD}PASSED${RESET}"
else
  echo -e "  ${RED}${BOLD}FAILED${RESET} (exit code: ${EXIT_CODE})"
fi

echo ""
echo -e "  ${BOLD}Scenario${RESET}:  ${SCENARIO_LABEL}"
echo -e "  ${BOLD}Run ID${RESET}:    ${TEST_RUN_ID}"
echo -e "  ${BOLD}Duration${RESET}:  ${DURATION_MIN}m ${DURATION_SEC}s"

if [[ $TOTAL -gt 0 ]]; then
  echo -e "  ${BOLD}Tests${RESET}:     ${TOTAL} total, ${GREEN}${PASSED} passed${RESET}, ${RED}${FAILED} failed${RESET}, ${DIM}${SKIPPED} skipped${RESET}"
fi

echo -e "  ${BOLD}Summary${RESET}:   ${RESULTS_DIR}/summary.json"

# Copy Playwright HTML report into run directory if it exists
PW_HTML_REPORT="${RESULTS_BASE}/html-report"
if [[ -d "$PW_HTML_REPORT" ]] && $CI_MODE; then
  cp -r "$PW_HTML_REPORT" "${RESULTS_DIR}/html-report" 2>/dev/null || true
fi

# Open HTML report if requested
if $REPORT && [[ -d "$PW_HTML_REPORT" ]]; then
  echo ""
  info "Opening HTML report..."
  (cd "${WEB_DIR}" && npx playwright show-report "${PW_HTML_REPORT}") &
fi

echo ""

exit $EXIT_CODE
