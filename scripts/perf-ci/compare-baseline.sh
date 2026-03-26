#!/usr/bin/env bash
# compare-baseline.sh — Compare k6 summary JSON files against a baseline.
#
# Usage: ./compare-baseline.sh <baseline.json> <current.json> [label]
#
# Exit codes:
#   0 = pass       (all metrics within tolerance)
#   1 = warning    (one or more metrics exceed soft threshold)
#   2 = critical   (one or more metrics exceed hard threshold)
#
# Output: JSON report to stdout
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "Usage: $0 <baseline.json> <current.json> [label]" >&2
  exit 1
fi

BASELINE_FILE="$1"
CURRENT_FILE="$2"
LABEL="${3:-unnamed}"

if ! command -v jq &>/dev/null; then
  echo "ERROR: jq is required but not found on PATH" >&2
  exit 1
fi

if [[ ! -f "$BASELINE_FILE" ]]; then
  echo "ERROR: baseline file not found: $BASELINE_FILE" >&2
  exit 1
fi

if [[ ! -f "$CURRENT_FILE" ]]; then
  echo "ERROR: current file not found: $CURRENT_FILE" >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# Extract metric values with graceful fallback to 0 if missing
# ---------------------------------------------------------------------------
get_duration() {
  local file="$1" percentile="$2"
  # k6 --summary-export puts values directly under the metric (no .values wrapper)
  # Also try .values path for compatibility with median-computed summaries
  jq --arg p "$percentile" \
    '(.metrics.http_req_duration[$p] // .metrics.http_req_duration.values[$p]) // 0' \
    "$file"
}

get_error_rate() {
  local file="$1"
  # k6 --summary-export uses .value for rate metrics; computed summaries use .values.rate
  jq '(.metrics.http_req_failed.value // .metrics.http_req_failed.values.rate // .metrics.http_req_failed.rate) // 0' "$file"
}

# ---------------------------------------------------------------------------
# Thresholds
# ---------------------------------------------------------------------------
# Tolerance  = soft warning boundary  (ratio relative to baseline)
# Critical   = hard failure boundary  (ratio relative to baseline)
P50_TOL=1.15;  P50_CRIT=1.30
P95_TOL=1.20;  P95_CRIT=1.50
P99_TOL=1.35;  P99_CRIT=1.80
ERR_TOL=0.005  # absolute delta
ERR_CRIT=0.02  # absolute delta

# ---------------------------------------------------------------------------
# Compare a single latency percentile
# Returns JSON fragment: {"metric": "...", "baseline": N, "current": N,
#                         "ratio": N, "status": "pass|warning|critical"}
# ---------------------------------------------------------------------------
compare_percentile() {
  local name="$1" pkey="$2" tol="$3" crit="$4"
  local baseline current ratio status

  baseline=$(get_duration "$BASELINE_FILE" "$pkey")
  current=$(get_duration "$CURRENT_FILE"  "$pkey")

  # If both are 0 the metric is absent — skip gracefully
  if [[ "$baseline" == "0" && "$current" == "0" ]]; then
    echo "{\"metric\":\"$name\",\"baseline\":0,\"current\":0,\"ratio\":null,\"status\":\"skip\"}"
    return
  fi

  # Guard against zero baseline to avoid division-by-zero
  if [[ "$baseline" == "0" ]]; then
    echo "{\"metric\":\"$name\",\"baseline\":0,\"current\":$current,\"ratio\":null,\"status\":\"warning\",\"note\":\"baseline_zero\"}"
    return
  fi

  ratio=$(echo "$baseline $current" | awk "{printf \"%.4f\", \$2/\$1}")

  # Compare using awk (bc alternative — awk is always available)
  status=$(echo "$ratio $crit $tol" | awk '{
    if ($1 > $2)      print "critical"
    else if ($1 > $3) print "warning"
    else              print "pass"
  }')

  echo "{\"metric\":\"$name\",\"baseline\":$baseline,\"current\":$current,\"ratio\":$ratio,\"status\":\"$status\"}"
}

# ---------------------------------------------------------------------------
# Compare error rate (absolute delta, not ratio)
# ---------------------------------------------------------------------------
compare_error_rate() {
  local baseline current delta status

  baseline=$(get_error_rate "$BASELINE_FILE")
  current=$(get_error_rate "$CURRENT_FILE")

  delta=$(echo "$baseline $current" | awk '{printf "%.6f", $2 - $1}')

  status=$(echo "$delta $ERR_CRIT $ERR_TOL" | awk '{
    if ($1 > $2)      print "critical"
    else if ($1 > $3) print "warning"
    else              print "pass"
  }')

  echo "{\"metric\":\"error_rate\",\"baseline\":$baseline,\"current\":$current,\"delta\":$delta,\"status\":\"$status\"}"
}

# ---------------------------------------------------------------------------
# Run comparisons
# ---------------------------------------------------------------------------
r_p50=$(compare_percentile "p50" "med"   "$P50_TOL" "$P50_CRIT")
r_p95=$(compare_percentile "p95" "p(95)" "$P95_TOL" "$P95_CRIT")
r_p99=$(compare_percentile "p99" "p(99)" "$P99_TOL" "$P99_CRIT")
r_err=$(compare_error_rate)

# ---------------------------------------------------------------------------
# Determine overall status (worst wins)
# ---------------------------------------------------------------------------
statuses=$(echo "$r_p50 $r_p95 $r_p99 $r_err" | \
  jq -s '[.[].status] | if any(. == "critical") then "critical"
          elif any(. == "warning") then "warning"
          else "pass" end' --raw-output 2>/dev/null || \
  # Fallback if jq inline fails for some reason
  echo "pass")

# Rebuild overall via simple grep scan
overall="pass"
for r in "$r_p50" "$r_p95" "$r_p99" "$r_err"; do
  if echo "$r" | grep -q '"status":"critical"'; then
    overall="critical"
    break
  elif echo "$r" | grep -q '"status":"warning"'; then
    overall="warning"
  fi
done

# ---------------------------------------------------------------------------
# Emit JSON report to stdout
# ---------------------------------------------------------------------------
TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

jq -n \
  --arg label "$LABEL" \
  --arg ts "$TIMESTAMP" \
  --arg overall "$overall" \
  --argjson p50  "$r_p50" \
  --argjson p95  "$r_p95" \
  --argjson p99  "$r_p99" \
  --argjson err  "$r_err" \
  '{
    label:   $label,
    timestamp: $ts,
    overall: $overall,
    metrics: [$p50, $p95, $p99, $err]
  }'

# ---------------------------------------------------------------------------
# Exit code
# ---------------------------------------------------------------------------
case "$overall" in
  critical) exit 2 ;;
  warning)  exit 1 ;;
  *)        exit 0 ;;
esac
