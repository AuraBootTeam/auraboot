#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:6443}"
PASS=0
FAIL=0

# Get token
SEED=$(NO_PROXY=localhost curl -s -X POST "$BASE_URL/api/test/seed" -H "Content-Type: application/json")
TOKEN=$(echo "$SEED" | python3 -c "import sys,json; print(json.load(sys.stdin).get('data',{}).get('token',''))" 2>/dev/null)
AUTH="Authorization: Bearer $TOKEN"

if [ -z "$TOKEN" ]; then
    echo "FATAL: Cannot get JWT token"
    exit 1
fi

check() {
    local name="$1" url="$2" expected="$3" method="${4:-GET}"
    local body="${5:-}"
    local response
    if [ "$method" = "POST" ]; then
        response=$(NO_PROXY=localhost curl -s -X POST "$BASE_URL$url" -H "$AUTH" -H "Content-Type: application/json" ${body:+-d "$body"})
    else
        response=$(NO_PROXY=localhost curl -s "$BASE_URL$url" -H "$AUTH")
    fi

    if echo "$response" | grep -q "$expected"; then
        echo "  ✓ $name"
        PASS=$((PASS + 1))
    else
        echo "  ✗ $name (expected '$expected')"
        echo "    Got: ${response:0:200}"
        FAIL=$((FAIL + 1))
    fi
}

echo "=== ACP Runtime E2E Tests ==="

echo "[1] Health & Status"
check "Agent status" "/api/agent/status" "enabled"
check "Providers" "/api/agent/providers" "["
check "Configured providers" "/api/agent/providers/configured" "["

echo "[2] Tool Operations"
check "Sync tools" "/api/agent/tools/sync" "created" "POST"
check "Derive contracts" "/api/agent/tools/derive-contracts" "derived" "POST"
check "Dry-run missing toolCode" "/api/agent/tools/dry-run" "toolCode" "POST" '{"input":{}}'
check "Dry-run-plan empty steps" "/api/agent/tools/dry-run-plan" "steps" "POST" '{"steps":[]}'

echo "[3] Capabilities & Approvals"
check "Capabilities list" "/api/agent/capabilities" "["
check "Pending approvals" "/api/agent/approvals/pending" "["

echo "[4] Schedule"
check "Reload schedules" "/api/agent/schedules/reload" "reload" "POST"

echo "[5] Dispatch Validation"
check "Dispatch no taskPid" "/api/agent/dispatch" "required" "POST" '{"agentCode":"test"}'
check "Dispatch no agentCode" "/api/agent/dispatch" "required" "POST" '{"taskPid":"test"}'

echo ""
echo "=== Results: $PASS passed, $FAIL failed ==="
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
