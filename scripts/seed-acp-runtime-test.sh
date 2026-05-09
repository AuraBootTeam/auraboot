#!/usr/bin/env bash
set -euo pipefail

# ACP Runtime Test Seed Script
BASE_URL="${BASE_URL:-http://localhost:6443}"

echo "=== ACP Runtime Seed ==="

# 1. Get JWT token
echo "[1/4] Getting test JWT..."
SEED_RESPONSE=$(NO_PROXY=localhost curl -s -X POST "$BASE_URL/api/test/seed" -H "Content-Type: application/json")
TOKEN=$(echo "$SEED_RESPONSE" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('token',''))" 2>/dev/null || echo "")

if [ -z "$TOKEN" ]; then
    echo "ERROR: Failed to get JWT. Is the server running with test profile?"
    echo "Response: $SEED_RESPONSE"
    exit 1
fi
echo "  Token: ${TOKEN:0:20}..."

AUTH="Authorization: Bearer $TOKEN"

# 2. List runtime tool registry (canonical source — replaces legacy /tools/sync no-op)
echo "[2/4] Listing agent tool registry..."
REGISTRY=$(NO_PROXY=localhost curl -s "$BASE_URL/api/agent/tools/registry" -H "$AUTH")
echo "  $REGISTRY"

# 3. Derive contracts
echo "[3/4] Deriving contracts..."
DERIVE=$(NO_PROXY=localhost curl -s -X POST "$BASE_URL/api/agent/tools/derive-contracts" -H "$AUTH")
echo "  $DERIVE"

# 4. Check status
echo "[4/4] Agent status..."
STATUS=$(NO_PROXY=localhost curl -s "$BASE_URL/api/agent/status" -H "$AUTH")
echo "  $STATUS"

echo ""
echo "=== Seed Complete ==="
echo "export ACP_TOKEN=$TOKEN"
