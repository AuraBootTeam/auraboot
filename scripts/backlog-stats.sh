#!/usr/bin/env bash
# Backlog dashboard stats — counts GAP statuses across all backlog files
# Usage: ./scripts/backlog-stats.sh

set -euo pipefail

BACKLOG_DIR="$(cd "$(dirname "$0")/../docs/backlog" && pwd)"

echo "=== AuraBoot Backlog Dashboard ==="
echo ""

total_todo=0
total_ip=0
total_done=0
total_wont=0

for file in technical.md business.md go-to-market.md; do
  filepath="$BACKLOG_DIR/$file"
  if [[ ! -f "$filepath" ]]; then
    echo "WARN: $file not found"
    continue
  fi

  todo=$(grep -c '^\- \*\*Status\*\*: TODO' "$filepath" || true)
  ip=$(grep -c '^\- \*\*Status\*\*: IN_PROGRESS' "$filepath" || true)
  done_count=$(grep -c '^\- \*\*Status\*\*: DONE' "$filepath" || true)
  wont=$(grep -c '^\- \*\*Status\*\*: WONT_DO' "$filepath" || true)
  total=$((todo + ip + done_count + wont))

  printf "%-20s  TODO=%-3d  IN_PROGRESS=%-3d  DONE=%-3d  WONT_DO=%-3d  Total=%-3d\n" \
    "$file" "$todo" "$ip" "$done_count" "$wont" "$total"

  total_todo=$((total_todo + todo))
  total_ip=$((total_ip + ip))
  total_done=$((total_done + done_count))
  total_wont=$((total_wont + wont))
done

echo ""
grand=$((total_todo + total_ip + total_done + total_wont))
printf "%-20s  TODO=%-3d  IN_PROGRESS=%-3d  DONE=%-3d  WONT_DO=%-3d  Total=%-3d\n" \
  "TOTAL" "$total_todo" "$total_ip" "$total_done" "$total_wont" "$grand"

echo ""
echo "Next available GAP number: GAP-$(printf '%03d' $((grand + 1)))"
echo ""
echo "To update README.md dashboard, copy these numbers into docs/backlog/README.md"
