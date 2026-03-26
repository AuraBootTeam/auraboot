#!/usr/bin/env bash
# run-perf-regression.sh — Orchestrate performance regression checks.
#
# Usage:
#   ./run-perf-regression.sh [--profile smoke|full]
#
# Environment variables:
#   BASE_URL            Target server (default: http://localhost:6443)
#   PERF_BASELINE_DIR   Where baseline JSON files live (default: scripts/perf-ci/baseline/)
#   PERF_ALERT_WEBHOOK  Optional Slack webhook URL (forwarded to notify.sh)
#   USERNAME            Login email (default: admin@auraboot.test)
#   PASSWORD            Login password (default: Test2026x)
#
# Exit codes:
#   0 = all tests passed
#   1 = at least one warning (no critical)
#   2 = at least one critical failure
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
K6_DIR="$(cd "$SCRIPT_DIR/../../tests/load/k6" && pwd)"
COMPARE_SCRIPT="$SCRIPT_DIR/compare-baseline.sh"
NOTIFY_SCRIPT="$SCRIPT_DIR/notify.sh"

# ---------------------------------------------------------------------------
# Defaults
# ---------------------------------------------------------------------------
PROFILE="smoke"
BASE_URL="${BASE_URL:-http://localhost:6443}"
PERF_BASELINE_DIR="${PERF_BASELINE_DIR:-$SCRIPT_DIR/baseline}"
USERNAME="${USERNAME:-admin@auraboot.test}"
PASSWORD="${PASSWORD:-Test2026x}"

# ---------------------------------------------------------------------------
# Parse arguments
# ---------------------------------------------------------------------------
while [[ $# -gt 0 ]]; do
  case "$1" in
    --profile)
      PROFILE="$2"
      shift 2
      ;;
    --profile=*)
      PROFILE="${1#*=}"
      shift
      ;;
    *)
      echo "Unknown argument: $1" >&2
      echo "Usage: $0 [--profile smoke|full]" >&2
      exit 1
      ;;
  esac
done

if [[ "$PROFILE" != "smoke" && "$PROFILE" != "full" ]]; then
  echo "ERROR: --profile must be 'smoke' or 'full', got: $PROFILE" >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# Dependency checks
# ---------------------------------------------------------------------------
for cmd in k6 jq curl; do
  if ! command -v "$cmd" &>/dev/null; then
    echo "ERROR: required command not found: $cmd" >&2
    exit 1
  fi
done

if [[ ! -d "$PERF_BASELINE_DIR" ]]; then
  echo "ERROR: baseline directory not found: $PERF_BASELINE_DIR" >&2
  echo "       Copy baseline JSON files from tests/load/results/baseline/ to that directory." >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# Test definitions
# Format: "label:k6_script_relative_to_K6_DIR"
# ---------------------------------------------------------------------------
TEST_CASES=(
  "auth:auth-baseline.js"
  "list:list-query.js"
  "command:command-execution.js"
)

# ---------------------------------------------------------------------------
# Temp directory for this run
# ---------------------------------------------------------------------------
RUN_DIR=$(mktemp -d /tmp/perf-ci-XXXXXX)
trap 'rm -rf "$RUN_DIR"' EXIT

echo "======================================================"
echo "  AuraBoot Performance Regression — profile: $PROFILE"
echo "  BASE_URL:      $BASE_URL"
echo "  BASELINE_DIR:  $PERF_BASELINE_DIR"
echo "  Timestamp:     $(date -u +"%Y-%m-%dT%H:%M:%SZ")"
echo "======================================================"

# ---------------------------------------------------------------------------
# Helper: run k6 once and write summary JSON to output_file
# ---------------------------------------------------------------------------
run_k6_once() {
  local script="$1"
  local output_file="$2"
  local extra_args="${3:-}"

  k6 run \
    --env BASE_URL="$BASE_URL" \
    --env USERNAME="$USERNAME" \
    --env PASSWORD="$PASSWORD" \
    --summary-export="$output_file" \
    --quiet \
    $extra_args \
    "$script" \
    2>&1 || true  # k6 exits non-zero on threshold failure; we evaluate ourselves
}

# ---------------------------------------------------------------------------
# Helper: compute median of three summary JSONs for http_req_duration
# Produces a merged summary JSON with median values.
# ---------------------------------------------------------------------------
compute_median_summary() {
  local f1="$1" f2="$2" f3="$3" output="$4"

  jq -n \
    --slurpfile a "$f1" \
    --slurpfile b "$f2" \
    --slurpfile c "$f3" \
    '
    def median3(x; y; z):
      [x, y, z] | sort | .[1];

    def dur_metric(a; b; c):
      {
        type: "trend",
        contains: "time",
        values: {
          avg: median3(a.metrics.http_req_duration.values.avg;
                       b.metrics.http_req_duration.values.avg;
                       c.metrics.http_req_duration.values.avg),
          min: median3(a.metrics.http_req_duration.values.min;
                       b.metrics.http_req_duration.values.min;
                       c.metrics.http_req_duration.values.min),
          med: median3(a.metrics.http_req_duration.values.med;
                       b.metrics.http_req_duration.values.med;
                       c.metrics.http_req_duration.values.med),
          max: median3(a.metrics.http_req_duration.values.max;
                       b.metrics.http_req_duration.values.max;
                       c.metrics.http_req_duration.values.max),
          "p(90)": median3(a.metrics.http_req_duration.values["p(90)"];
                           b.metrics.http_req_duration.values["p(90)"];
                           c.metrics.http_req_duration.values["p(90)"]),
          "p(95)": median3(a.metrics.http_req_duration.values["p(95)"];
                           b.metrics.http_req_duration.values["p(95)"];
                           c.metrics.http_req_duration.values["p(95)"]),
          "p(99)": median3(a.metrics.http_req_duration.values["p(99)"];
                           b.metrics.http_req_duration.values["p(99)"];
                           c.metrics.http_req_duration.values["p(99)"])
        }
      };

    def err_metric(a; b; c):
      {
        type: "rate",
        contains: "default",
        values: {
          rate: median3(a.metrics.http_req_failed.values.rate;
                        b.metrics.http_req_failed.values.rate;
                        c.metrics.http_req_failed.values.rate),
          passes: median3(a.metrics.http_req_failed.values.passes;
                          b.metrics.http_req_failed.values.passes;
                          c.metrics.http_req_failed.values.passes),
          fails:  median3(a.metrics.http_req_failed.values.fails;
                          b.metrics.http_req_failed.values.fails;
                          c.metrics.http_req_failed.values.fails)
        }
      };

    {
      metrics: {
        http_req_duration: dur_metric($a[0]; $b[0]; $c[0]),
        http_req_failed:   err_metric($a[0]; $b[0]; $c[0])
      }
    }
    ' > "$output"
}

# ---------------------------------------------------------------------------
# Run all test cases
# ---------------------------------------------------------------------------
OVERALL_EXIT=0
RESULTS_SUMMARY=()

for test_def in "${TEST_CASES[@]}"; do
  LABEL="${test_def%%:*}"
  SCRIPT_REL="${test_def##*:}"
  K6_SCRIPT="$K6_DIR/$SCRIPT_REL"
  BASELINE_FILE="$PERF_BASELINE_DIR/${LABEL}-baseline.json"

  echo ""
  echo "--- Test: $LABEL ($SCRIPT_REL) ---"

  if [[ ! -f "$K6_SCRIPT" ]]; then
    echo "  SKIP: k6 script not found at $K6_SCRIPT"
    RESULTS_SUMMARY+=("$LABEL: SKIP (script missing)")
    continue
  fi

  if [[ ! -f "$BASELINE_FILE" ]]; then
    echo "  SKIP: baseline not found at $BASELINE_FILE"
    RESULTS_SUMMARY+=("$LABEL: SKIP (no baseline)")
    continue
  fi

  # -----------------------------------------------------------------------
  # Run k6 — smoke: 1 run; full: 3 runs then compute median
  # -----------------------------------------------------------------------
  if [[ "$PROFILE" == "smoke" ]]; then
    CURRENT_FILE="$RUN_DIR/${LABEL}-current.json"
    echo "  Running smoke (10 VUs, 30s)..."
    run_k6_once "$K6_SCRIPT" "$CURRENT_FILE" \
      "--vus 10 --duration 30s"
  else
    echo "  Running full (3 runs, taking median)..."
    run_k6_once "$K6_SCRIPT" "$RUN_DIR/${LABEL}-run1.json"
    run_k6_once "$K6_SCRIPT" "$RUN_DIR/${LABEL}-run2.json"
    run_k6_once "$K6_SCRIPT" "$RUN_DIR/${LABEL}-run3.json"

    CURRENT_FILE="$RUN_DIR/${LABEL}-current.json"
    compute_median_summary \
      "$RUN_DIR/${LABEL}-run1.json" \
      "$RUN_DIR/${LABEL}-run2.json" \
      "$RUN_DIR/${LABEL}-run3.json" \
      "$CURRENT_FILE"
    echo "  Median summary computed."
  fi

  # -----------------------------------------------------------------------
  # Compare against baseline
  # -----------------------------------------------------------------------
  COMPARE_OUTPUT="$RUN_DIR/${LABEL}-compare.json"
  set +e
  "$COMPARE_SCRIPT" "$BASELINE_FILE" "$CURRENT_FILE" "$LABEL" \
    > "$COMPARE_OUTPUT"
  COMPARE_EXIT=$?
  set -e

  # Pretty-print comparison result
  jq '.' "$COMPARE_OUTPUT" >&2 || true

  TEST_STATUS=$(jq -r '.overall' "$COMPARE_OUTPUT" 2>/dev/null || echo "error")
  RESULTS_SUMMARY+=("$LABEL: $TEST_STATUS")

  if [[ $COMPARE_EXIT -eq 2 ]]; then
    OVERALL_EXIT=2
  elif [[ $COMPARE_EXIT -eq 1 && $OVERALL_EXIT -lt 2 ]]; then
    OVERALL_EXIT=1
  fi
done

# ---------------------------------------------------------------------------
# Build final summary text
# ---------------------------------------------------------------------------
SUMMARY_TEXT="profile=$PROFILE"
for r in "${RESULTS_SUMMARY[@]}"; do
  SUMMARY_TEXT="$SUMMARY_TEXT | $r"
done

case "$OVERALL_EXIT" in
  0) OVERALL_STATUS="pass" ;;
  1) OVERALL_STATUS="warning" ;;
  2) OVERALL_STATUS="critical" ;;
  *) OVERALL_STATUS="error" ;;
esac

echo ""
echo "======================================================"
echo "  Overall result: $OVERALL_STATUS"
for r in "${RESULTS_SUMMARY[@]}"; do
  echo "    $r"
done
echo "======================================================"

# ---------------------------------------------------------------------------
# Notify
# ---------------------------------------------------------------------------
"$NOTIFY_SCRIPT" "$OVERALL_STATUS" "$SUMMARY_TEXT"

exit "$OVERALL_EXIT"
