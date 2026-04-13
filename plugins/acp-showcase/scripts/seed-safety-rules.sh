#!/usr/bin/env bash
# ACP Showcase — Seed 7 Safety Valve Rules
# Run after plugin import: bash plugins/acp-showcase/scripts/seed-safety-rules.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:6443}"
EMAIL="${EMAIL:-admin@example.com}"
PASSWORD="${PASSWORD:-Test2026x}"

echo "=== ACP Showcase: Seeding Safety Rules ==="

# Login
TOKEN=$(NO_PROXY=localhost curl -sf -X POST "$BASE_URL/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['jwt'])")

if [ -z "$TOKEN" ]; then
  echo "ERROR: Login failed"
  exit 1
fi

echo "  Logged in as $EMAIL"

exec_cmd() {
  local CMD="$1"
  local PAYLOAD="$2"
  NO_PROXY=localhost curl -sf -X POST "$BASE_URL/api/meta/commands/execute/$CMD" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"payload\": $PAYLOAD}" > /dev/null
  echo "  Created: $CMD"
}

# Rule 1: Approval Gate (L3+)
exec_cmd "acs:create_safety_rule" '{
  "acs_rule_code": "GATE_L3_PLUS",
  "acs_rule_name": "Approval Gate (L3+ Risk)",
  "acs_rule_type": "approval_gate",
  "acs_rule_description": "Requires human approval for operations with risk level L3 or above. Covers delete, batch operations, and cross-model writes.",
  "acs_rule_trigger_condition": "{\"risk_level\": \">=L3\"}",
  "acs_rule_action": "require_approval",
  "acs_rule_severity": "error",
  "acs_rule_threshold": 3,
  "acs_rule_priority": 10
}'

# Rule 2: Cost Limit per Run
exec_cmd "acs:create_safety_rule" '{
  "acs_rule_code": "COST_PER_RUN",
  "acs_rule_name": "Cost Limit per Run ($1.00)",
  "acs_rule_type": "cost_limit",
  "acs_rule_description": "Pauses execution and notifies the operator when a single run accumulates more than $1.00 in LLM API costs.",
  "acs_rule_trigger_condition": "{\"max_cost_per_run\": 1.00}",
  "acs_rule_action": "pause_and_notify",
  "acs_rule_severity": "warn",
  "acs_rule_threshold": 1.00,
  "acs_rule_priority": 20
}'

# Rule 3: Hallucination Circuit Breaker
exec_cmd "acs:create_safety_rule" '{
  "acs_rule_code": "HALLUCIN_BREAKER",
  "acs_rule_name": "Hallucination Circuit Breaker",
  "acs_rule_type": "hallucination_breaker",
  "acs_rule_description": "Terminates execution immediately when the agent calls 3 non-existent tools consecutively, indicating hallucination.",
  "acs_rule_trigger_condition": "{\"max_hallucinated_tools\": 3}",
  "acs_rule_action": "terminate",
  "acs_rule_severity": "critical",
  "acs_rule_threshold": 3,
  "acs_rule_priority": 5
}'

# Rule 4: Iteration Limit
exec_cmd "acs:create_safety_rule" '{
  "acs_rule_code": "ITER_LIMIT_20",
  "acs_rule_name": "Tool Loop Iteration Limit (20)",
  "acs_rule_type": "iteration_limit",
  "acs_rule_description": "Terminates execution when the tool loop exceeds 20 iterations, preventing infinite loops.",
  "acs_rule_trigger_condition": "{\"max_iterations\": 20}",
  "acs_rule_action": "terminate",
  "acs_rule_severity": "error",
  "acs_rule_threshold": 20,
  "acs_rule_priority": 15
}'

# Rule 5: Deletion Scope Guard
exec_cmd "acs:create_safety_rule" '{
  "acs_rule_code": "DELETE_SCOPE_GUARD",
  "acs_rule_name": "Deletion Scope Guard (>10 records)",
  "acs_rule_type": "content_filter",
  "acs_rule_description": "Requires approval when a delete operation would affect more than 10 records, preventing accidental mass deletion.",
  "acs_rule_trigger_condition": "{\"intent\": \"delete\", \"affected_count_gt\": 10}",
  "acs_rule_action": "require_approval",
  "acs_rule_severity": "error",
  "acs_rule_threshold": 10,
  "acs_rule_priority": 12
}'

# Rule 6: Rate Limit
exec_cmd "acs:create_safety_rule" '{
  "acs_rule_code": "RATE_100_PER_HOUR",
  "acs_rule_name": "Rate Limit (100 req/hour)",
  "acs_rule_type": "rate_limit",
  "acs_rule_description": "Pauses and notifies when more than 100 AI requests are submitted within an hour, preventing runaway automation.",
  "acs_rule_trigger_condition": "{\"max_requests_per_hour\": 100}",
  "acs_rule_action": "pause_and_notify",
  "acs_rule_severity": "warn",
  "acs_rule_threshold": 100,
  "acs_rule_priority": 25
}'

# Rule 7: External API Monitor
exec_cmd "acs:create_safety_rule" '{
  "acs_rule_code": "EXT_API_MONITOR",
  "acs_rule_name": "External API Call Monitor",
  "acs_rule_type": "scope_restriction",
  "acs_rule_description": "Logs all external API calls made by AI agents for audit purposes. Does not block execution.",
  "acs_rule_trigger_condition": "{\"tool_type\": \"custom_api\"}",
  "acs_rule_action": "log_only",
  "acs_rule_severity": "info",
  "acs_rule_threshold": 0,
  "acs_rule_priority": 30
}'

echo ""
echo "=== Done: 7 safety rules seeded ==="
echo "  View at: ${BASE_URL}/p/acs_safety_rule"
