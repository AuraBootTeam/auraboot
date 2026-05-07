#!/usr/bin/env bash
# ACP Showcase — Seed AI Business Demo Requests
# Run after plugin import: bash plugins/acp-showcase/scripts/seed-demo-requests.sh
#
# Uses Aura CLI (`aura exec`) — first authenticate with `aura login` if needed.

set -euo pipefail

if ! command -v aura >/dev/null 2>&1; then
  echo "ERROR: aura CLI not found on PATH. Install: cd plugins/cli && npm link" >&2
  exit 1
fi

echo "=== ACP Showcase: Seeding AI Demo Requests ==="

create_request() {
  local DESC="$1"
  local PAYLOAD="$2"
  local PID
  PID=$(printf '%s' "$PAYLOAD" \
    | aura exec acs:create_demo_request --stdin --format json --agent-mode \
    | python3 -c "import sys,json; print(json.load(sys.stdin).get('data',{}).get('recordId',''))")
  if [ -z "$PID" ]; then
    echo "  FAILED: $DESC" >&2
    return 1
  fi
  echo "  Created (draft): $DESC ($PID)" >&2
  printf '%s' "$PID"
}

submit_request() {
  local PID="$1"
  aura exec acs:submit_request --target "$PID" --format json --agent-mode > /dev/null
  echo "  Submitted:       $PID" >&2
}

PID1=$(create_request "Q3 sales by region" '{
  "acs_req_title": "Query Q3 sales by region",
  "acs_req_nl_input": "Show me total sales for Q3 2025 broken down by region, sorted descending.",
  "acs_req_category": "data_query",
  "acs_req_priority": "medium"
}')

PID2=$(create_request "New contract for Acme Corp" '{
  "acs_req_title": "Create new contract for Acme Corp",
  "acs_req_nl_input": "Create a new sales contract for customer Acme Corp with amount $50,000, start date today, duration 12 months.",
  "acs_req_category": "record_create",
  "acs_req_priority": "high"
}')

PID3=$(create_request "Bulk update overdue invoices" '{
  "acs_req_title": "Bulk update overdue invoices to past_due",
  "acs_req_nl_input": "Mark all unpaid invoices older than 30 days as past_due and trigger reminder email.",
  "acs_req_category": "batch_operation",
  "acs_req_priority": "high"
}')

PID4=$(create_request "Fetch external WMS stock" '{
  "acs_req_title": "Fetch stock level from external WMS",
  "acs_req_nl_input": "Call the external warehouse API for SKU=ABC-001 and refresh our inventory snapshot.",
  "acs_req_category": "external_api",
  "acs_req_priority": "low"
}')

PID5=$(create_request "Approve leave request #42" '{
  "acs_req_title": "Approve leave request for employee #42",
  "acs_req_nl_input": "Approve the pending leave request submitted by employee 42 last Friday.",
  "acs_req_category": "state_transition",
  "acs_req_priority": "medium"
}')

PID6=$(create_request "Daily reconciliation automation" '{
  "acs_req_title": "Daily reconciliation automation",
  "acs_req_nl_input": "Run the end-of-day reconciliation pipeline: match bank deposits with order payments and flag mismatches.",
  "acs_req_category": "automation",
  "acs_req_priority": "critical"
}')

# Promote two requests to "submitted" so the In-Progress tab is non-empty
[ -n "$PID1" ] && submit_request "$PID1"
[ -n "$PID5" ] && submit_request "$PID5"

# Suppress "set but not used" warnings for the remaining draft PIDs
: "$PID2" "$PID3" "$PID4" "$PID6"

echo ""
echo "=== Done: 6 demo requests seeded (4 draft + 2 submitted) ==="
echo "  View at: http://localhost:5173/p/acs_demo_request"
