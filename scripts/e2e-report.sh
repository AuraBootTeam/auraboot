#!/usr/bin/env bash
#
# e2e-report.sh — View E2E test run results by testRunId
#
# Usage:
#   ./scripts/e2e-report.sh <testRunId>
#   ./scripts/e2e-report.sh latest
#
# Looks in web-admin/test-results/ for matching run directory.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
RESULTS_DIR="$PROJECT_ROOT/web-admin/test-results"

# --- helpers ----------------------------------------------------------------

usage() {
  echo "Usage: $(basename "$0") <testRunId | latest>"
  echo ""
  echo "Examples:"
  echo "  $(basename "$0") web_1711012345_a2x9"
  echo "  $(basename "$0") latest"
  exit 1
}

die() { echo "ERROR: $1" >&2; exit 1; }

# --- argument handling ------------------------------------------------------

[[ $# -lt 1 ]] && usage

RUN_ID="$1"

if [[ ! -d "$RESULTS_DIR" ]]; then
  die "Test results directory not found: $RESULTS_DIR"
fi

# Resolve "latest" to most recent directory (by modification time)
if [[ "$RUN_ID" == "latest" ]]; then
  LATEST=$(ls -1td "$RESULTS_DIR"/*/ 2>/dev/null | head -1)
  if [[ -z "$LATEST" ]]; then
    die "No test run directories found in $RESULTS_DIR"
  fi
  RUN_ID=$(basename "$LATEST")
  echo "(resolved 'latest' -> $RUN_ID)"
  echo ""
fi

RUN_DIR="$RESULTS_DIR/$RUN_ID"

if [[ ! -d "$RUN_DIR" ]]; then
  die "Run directory not found: $RUN_DIR"
fi

# --- parse summary.json -----------------------------------------------------

SUMMARY="$RUN_DIR/summary.json"

if [[ -f "$SUMMARY" ]]; then
  # Extract fields with portable tools (python3 fallback to jq fallback to grep)
  if command -v python3 &>/dev/null; then
    read_json() {
      python3 -c "
import json, sys
d = json.load(open('$SUMMARY'))
print(d.get('runId', '$RUN_ID'))
print(d.get('date', 'unknown'))
print(d.get('duration', 'unknown'))
print(d.get('status', 'unknown'))
print(d.get('passed', 0))
print(d.get('failed', 0))
print(d.get('skipped', 0))
"
    }
  elif command -v jq &>/dev/null; then
    read_json() {
      jq -r "
        .runId // \"$RUN_ID\",
        .date // \"unknown\",
        .duration // \"unknown\",
        .status // \"unknown\",
        (.passed // 0 | tostring),
        (.failed // 0 | tostring),
        (.skipped // 0 | tostring)
      " "$SUMMARY"
    }
  else
    die "Neither python3 nor jq found. Install one to parse summary.json."
  fi

  mapfile -t VALS < <(read_json)
  S_RUN_ID="${VALS[0]}"
  S_DATE="${VALS[1]}"
  S_DURATION="${VALS[2]}"
  S_STATUS="${VALS[3]}"
  S_PASSED="${VALS[4]}"
  S_FAILED="${VALS[5]}"
  S_SKIPPED="${VALS[6]}"

  # Color the status
  if [[ "$S_STATUS" == "PASS" || "$S_STATUS" == "pass" ]]; then
    STATUS_DISPLAY="\033[32mPASS\033[0m"
  else
    STATUS_DISPLAY="\033[31mFAIL\033[0m"
  fi

  echo "=== Test Run Report ==="
  echo "Run ID:    $S_RUN_ID"
  echo "Date:      $S_DATE"
  echo "Duration:  $S_DURATION"
  echo -e "Status:    $STATUS_DISPLAY"
  echo ""
  echo "Results:"
  echo "  ✅ $S_PASSED passed"
  echo "  ❌ $S_FAILED failed"
  echo "  ⏭️  $S_SKIPPED skipped"
  echo ""

  # List artifacts
  echo "Artifacts:"
  [[ -d "$RUN_DIR/traces" ]]  && echo "  traces/  -> playwright traces"
  [[ -d "$RUN_DIR/report" ]]  && echo "  report/  -> HTML report"
  [[ -d "$RUN_DIR/videos" ]]  && echo "  videos/  -> test videos"

  # Check for HTML report and offer to open
  HTML_REPORT=$(find "$RUN_DIR" -name "index.html" -path "*/report/*" 2>/dev/null | head -1)
  if [[ -n "$HTML_REPORT" ]]; then
    echo ""
    echo "Open HTML report:"
    echo "  open $HTML_REPORT"
  fi

else
  # No summary.json — look for Playwright HTML report
  echo "=== Test Run: $RUN_ID ==="
  echo ""
  echo "No summary.json found."
  echo ""

  HTML_REPORT=$(find "$RUN_DIR" -name "index.html" 2>/dev/null | head -1)
  if [[ -n "$HTML_REPORT" ]]; then
    echo "Found Playwright HTML report: $HTML_REPORT"
    echo ""
    read -rp "Open in browser? [y/N] " REPLY
    if [[ "$REPLY" =~ ^[Yy]$ ]]; then
      open "$HTML_REPORT" 2>/dev/null || xdg-open "$HTML_REPORT" 2>/dev/null || echo "Run: open $HTML_REPORT"
    fi
  else
    echo "Contents of $RUN_DIR:"
    ls -la "$RUN_DIR"
  fi
fi
