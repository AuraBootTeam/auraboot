#!/usr/bin/env bash
# ACP Showcase — Seed AI Business Demo Requests
# Run after plugin import: bash plugins/acp-showcase/scripts/seed-demo-requests.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:6443}"
EMAIL="${EMAIL:-admin@example.com}"
PASSWORD="${PASSWORD:-Test2026x}"

echo "=== ACP Showcase: Seeding AI Demo Requests ==="

TOKEN=$(NO_PROXY=localhost curl -sf -X POST "$BASE_URL/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['jwt'])")

if [ -z "$TOKEN" ]; then
  echo "ERROR: Login failed"
  exit 1
fi

echo "  Logged in as $EMAIL"

create_request() {
  local PAYLOAD="$1"
  NO_PROXY=localhost curl -sf -X POST "$BASE_URL/api/meta/commands/execute/acs:create_demo_request" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"payload\": $PAYLOAD}" \
    | python3 -c "import sys,json; r=json.load(sys.stdin); print(r.get('data',{}).get('recordId') or r.get('data',{}).get('pid') or '', end='')"
}

submit_request() {
  local PID="$1"
  NO_PROXY=localhost curl -sf -X POST "$BASE_URL/api/meta/commands/execute/acs:submit_request" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"payload\":{},\"targetRecordId\":\"$PID\"}" > /dev/null
}

# 6 demo requests covering diverse categories
PID1=$(create_request '{
  "acs_req_title": "Query Q3 sales by region",
  "acs_req_nl_input": "Show me total sales for Q3 2025 broken down by region, sorted descending.",
  "acs_req_category": "data_query",
  "acs_req_priority": "medium"
}')
echo "  Created (draft, query):       $PID1"

PID2=$(create_request '{
  "acs_req_title": "Create new contract for Acme Corp",
  "acs_req_nl_input": "Create a new sales contract for customer Acme Corp with amount $50,000, start date today, duration 12 months.",
  "acs_req_category": "record_create",
  "acs_req_priority": "high"
}')
echo "  Created (draft, create):      $PID2"

PID3=$(create_request '{
  "acs_req_title": "Bulk update overdue invoices to past_due",
  "acs_req_nl_input": "Mark all unpaid invoices older than 30 days as past_due and trigger reminder email.",
  "acs_req_category": "batch_operation",
  "acs_req_priority": "high"
}')
echo "  Created (draft, batch):       $PID3"

PID4=$(create_request '{
  "acs_req_title": "Fetch stock level from external WMS",
  "acs_req_nl_input": "Call the external warehouse API for SKU=ABC-001 and refresh our inventory snapshot.",
  "acs_req_category": "external_api",
  "acs_req_priority": "low"
}')
echo "  Created (draft, ext-api):     $PID4"

PID5=$(create_request '{
  "acs_req_title": "Approve leave request for employee #42",
  "acs_req_nl_input": "Approve the pending leave request submitted by employee 42 last Friday.",
  "acs_req_category": "state_transition",
  "acs_req_priority": "medium"
}')
echo "  Created (draft, state):       $PID5"

PID6=$(create_request '{
  "acs_req_title": "Daily reconciliation automation",
  "acs_req_nl_input": "Run the end-of-day reconciliation pipeline: match bank deposits with order payments and flag mismatches.",
  "acs_req_category": "automation",
  "acs_req_priority": "critical"
}')
echo "  Created (draft, automation):  $PID6"

# Promote two requests to "submitted" so the In-Progress tab is non-empty
if [ -n "$PID1" ]; then submit_request "$PID1"; echo "  Submitted:                    $PID1"; fi
if [ -n "$PID5" ]; then submit_request "$PID5"; echo "  Submitted:                    $PID5"; fi

echo ""
echo "=== Done: 6 demo requests seeded (4 draft + 2 submitted) ==="
echo "  View at: ${BASE_URL%:*}:5173/p/acs_demo_request"
