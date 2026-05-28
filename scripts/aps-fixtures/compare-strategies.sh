#!/usr/bin/env bash
#
# Compare APS V2 scheduling strategies on the fixture data set.
#
# Prereqs (env vars):
#   BACKEND_URL   e.g. http://localhost:6443
#   AUTH_TOKEN    valid JWT bearer
#   PGURL         e.g. postgres://aura:aura@localhost:5432/aura_boot
#   TENANT_ID     numeric tenant id (default 1)
#
# Optional flags:
#   --horizon=N   scheduling horizon (default 30)
#   --skip-seed   assume fixtures already loaded
#   --keep        don't clean schedule_result rows between strategies
#
# Output: tab-separated table to stdout; CSV mirror to ./aps-comparison.csv

set -euo pipefail

# ---- args ----

HORIZON=30
SKIP_SEED=0
KEEP=0

for arg in "$@"; do
  case "$arg" in
    --horizon=*)  HORIZON="${arg#*=}" ;;
    --skip-seed)  SKIP_SEED=1 ;;
    --keep)       KEEP=1 ;;
    *) echo "unknown arg: $arg" >&2; exit 2 ;;
  esac
done

# ---- env check ----

: "${BACKEND_URL:?BACKEND_URL must be set, e.g. http://localhost:6443}"
: "${AUTH_TOKEN:?AUTH_TOKEN must be set (JWT bearer)}"
: "${PGURL:?PGURL must be set, e.g. postgres://aura:aura@localhost:5432/aura_boot}"
TENANT_ID="${TENANT_ID:-1}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Portable millisecond clock: GNU `date +%s%3N` works on Linux but not macOS BSD.
# Use Python for cross-platform support; fall back to gdate if python3 absent.
ms_now() {
  if command -v python3 >/dev/null 2>&1; then
    python3 -c 'import time; print(int(time.time()*1000))'
  elif command -v gdate >/dev/null 2>&1; then
    gdate +%s%3N
  else
    # last-resort: seconds × 1000 (no sub-second precision)
    echo "$(( $(date +%s) * 1000 ))"
  fi
}

# ---- step 1: seed fixtures (unless skipped) ----

if [[ $SKIP_SEED -eq 0 ]]; then
  echo "==> Seeding fixtures into tenant $TENANT_ID..."
  PGOPTIONS="-c search_path=public" psql "$PGURL" \
    -v ON_ERROR_STOP=1 \
    -v tenant_id="$TENANT_ID" \
    -f "$SCRIPT_DIR/seed.sql" \
    > /tmp/aps-seed-output.txt 2>&1 || {
    echo "FAILED to seed fixtures. See /tmp/aps-seed-output.txt" >&2
    exit 1
  }
  tail -8 /tmp/aps-seed-output.txt
fi

# ---- step 2: run each strategy ----

STRATEGIES=(forwardFifo forwardEdd backward bottleneckFirst genetic)
CSV_OUT="$SCRIPT_DIR/aps-comparison.csv"
echo "strategy,scheduledCount,conflictCount,makespan_min,runtime_ms,scheduleVersion" > "$CSV_OUT"

printf "\n%-18s %-10s %-10s %-12s %-12s\n" "strategy" "scheduled" "conflict" "makespan" "runtime"
printf "%-18s %-10s %-10s %-12s %-12s\n" "------" "------" "------" "------" "------"

for strategy in "${STRATEGIES[@]}"; do
  # Clear schedule_result rows for this fixture run unless --keep
  if [[ $KEEP -eq 0 ]]; then
    psql "$PGURL" -v ON_ERROR_STOP=1 -c "DELETE FROM mt_pe_schedule_result WHERE pe_sched_work_order_id LIKE 'APS_FIX_%';" > /dev/null
  fi

  # Call V2 endpoint and time it
  t0=$(ms_now)
  response=$(curl -sS -X POST \
    -H "Authorization: Bearer $AUTH_TOKEN" \
    -H "Content-Type: application/json" \
    "$BACKEND_URL/api/manufacturing/aps/schedule/v2?horizon=$HORIZON&strategy=$strategy")
  t1=$(ms_now)
  runtime_ms=$((t1 - t0))

  scheduled=$(echo "$response" | jq -r '.data.scheduledCount // 0')
  conflict=$(echo "$response" | jq -r '.data.conflictCount // 0')
  version=$(echo "$response" | jq -r '.data.scheduleVersion // 0')

  # Compute makespan in minutes from schedule_result rows
  makespan_min=$(psql "$PGURL" -tA -c "
    SELECT COALESCE(
      EXTRACT(EPOCH FROM (MAX(pe_sched_end_time) - MIN(pe_sched_start_time))) / 60,
      0
    )::int
    FROM mt_pe_schedule_result
    WHERE pe_sched_work_order_id LIKE 'APS_FIX_%'
      AND pe_sched_version = $version;
  " 2>/dev/null | tr -d ' ')

  makespan_min=${makespan_min:-0}
  makespan_h=$((makespan_min / 60))
  if [[ $makespan_h -ge 24 ]]; then
    makespan_disp="$((makespan_h / 24))d$((makespan_h % 24))h"
  elif [[ $makespan_h -ge 1 ]]; then
    makespan_disp="${makespan_h}h$((makespan_min % 60))m"
  else
    makespan_disp="${makespan_min}m"
  fi

  printf "%-18s %-10s %-10s %-12s %-12s\n" "$strategy" "$scheduled" "$conflict" "$makespan_disp" "${runtime_ms}ms"
  echo "$strategy,$scheduled,$conflict,$makespan_min,$runtime_ms,$version" >> "$CSV_OUT"
done

echo
echo "CSV written to $CSV_OUT"

# ---- step 3: highlight winners ----

echo
echo "==> Winners (by metric)"
echo "best_by_throughput:   $(sort -t, -k2 -nr "$CSV_OUT" | grep -v '^strategy' | head -1 | cut -d, -f1)"
echo "best_by_makespan:     $(awk -F, 'NR>1 && $4 != "0" { print }' "$CSV_OUT" | sort -t, -k4 -n | head -1 | cut -d, -f1)"
echo "best_by_runtime:      $(sort -t, -k5 -n "$CSV_OUT" | grep -v '^strategy' | head -1 | cut -d, -f1)"
echo "lowest_conflicts:     $(sort -t, -k3 -n "$CSV_OUT" | grep -v '^strategy' | head -1 | cut -d, -f1)"

echo
echo "Done. Set --keep to retain schedule_result rows for inspection."
