#!/usr/bin/env bash
# docker-bootstrap.sh — Import plugins into an already-running Docker E2E backend.
#
# Prerequisites:
#   - Docker backend container healthy at BACKEND_URL (default: http://localhost:16443)
#   - Admin user already bootstrapped through /api/bootstrap/setup
#   - plugins/ directory mounted into the container at /app/plugins
#     → the OSS docker-compose.e2e.override.yml provides this mount.
#
# Usage:
#   ./scripts/docker-bootstrap.sh
#
# Env vars:
#   BACKEND_URL           default: http://localhost:16443
#   ADMIN_EMAIL           default: admin@auraboot.com
#   ADMIN_PASSWORD        default: Test2026x
#   IMPORT_TEST_FIXTURES  default: false  (set to "true" to also import test-fixtures)

set -euo pipefail

# ── Configuration ─────────────────────────────────────────────────────────────
BACKEND_URL="${BACKEND_URL:-http://localhost:16443}"
ADMIN_EMAIL="${ADMIN_EMAIL:-admin@auraboot.com}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-Test2026x}"
IMPORT_TEST_FIXTURES="${IMPORT_TEST_FIXTURES:-false}"

# The container-internal path where plugins are mounted (matches override file).
CONTAINER_PLUGINS_PATH="/app/plugins"

# Plugins are imported in dependency order: base first, then dependent.
PLUGINS_TO_IMPORT=(
  core-meta
  core-bpm
  core-aurabot
  page-manager
  platform-admin
  org-management
  crm-starter
  showcase
  agent-control-plane
  workflow-demo
)

if [[ "${IMPORT_TEST_FIXTURES}" == "true" ]]; then
  PLUGINS_TO_IMPORT+=(test-fixtures)
fi

# ── Colors ────────────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# ── Step 0: Verify backend health ─────────────────────────────────────────────
echo -e "${BLUE}=== AuraBoot Docker E2E Bootstrap ===${NC}"
echo "Backend URL: ${BACKEND_URL}"
echo ""

echo -e "${YELLOW}[0] Verifying backend health...${NC}"
HEALTH=$(NO_PROXY=localhost curl -s "${BACKEND_URL}/actuator/health" 2>/dev/null || echo '{}')
STATUS=$(echo "$HEALTH" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status',''))" 2>/dev/null || echo "")
if [[ "$STATUS" != "UP" ]]; then
  echo -e "${RED}FAIL: Backend not healthy (status='${STATUS}'). Ensure the container is running.${NC}"
  echo "      Expected: GET ${BACKEND_URL}/actuator/health → {\"status\":\"UP\"}"
  exit 1
fi
echo -e "${GREEN}   Backend is UP${NC}"

# ── Step 1: Verify plugins volume mount ───────────────────────────────────────
echo ""
echo -e "${YELLOW}[1] Checking plugins volume mount inside container...${NC}"

BACKEND_CONTAINER=$(docker ps --filter "publish=16443" --format "{{.Names}}" | head -1)
if [[ -z "${BACKEND_CONTAINER}" ]]; then
  echo -e "${RED}FAIL: Cannot find a container exposing port 16443.${NC}"
  exit 1
fi
echo "    Container: ${BACKEND_CONTAINER}"

# Check that /app/plugins is non-empty (i.e., the volume mount is present)
PLUGIN_COUNT=$(docker exec "${BACKEND_CONTAINER}" sh -c "ls ${CONTAINER_PLUGINS_PATH} 2>/dev/null | wc -l" 2>/dev/null || echo "0")
if [[ "${PLUGIN_COUNT}" -lt 1 ]]; then
  echo -e "${RED}FAIL: ${CONTAINER_PLUGINS_PATH} is empty inside container '${BACKEND_CONTAINER}'.${NC}"
  echo ""
  echo "  The plugins directory is not mounted. Fix:"
  echo ""
  echo "  1. Ensure docker-compose.e2e.override.yml exists in the OSS repo root with:"
  echo "       services:"
  echo "         backend:"
  echo "           volumes:"
  echo "             - ./plugins:/app/plugins:ro"
  echo ""
  echo "  2. Restart the backend with the override:"
  echo "       cd /Users/ghj/work/auraboot/auraboot"
  echo "       docker compose -f docker-compose.yml -f docker-compose.e2e.override.yml \\"
  echo "         --profile full up -d backend"
  echo ""
  exit 1
fi
echo -e "${GREEN}   Mount OK (${PLUGIN_COUNT} entries in ${CONTAINER_PLUGINS_PATH})${NC}"

# ── Step 2: Login and obtain JWT ──────────────────────────────────────────────
echo ""
echo -e "${YELLOW}[2] Logging in as ${ADMIN_EMAIL}...${NC}"

LOGIN_RESP=$(NO_PROXY=localhost curl -s -X POST "${BACKEND_URL}/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"${ADMIN_EMAIL}\",\"password\":\"${ADMIN_PASSWORD}\"}" 2>/dev/null)

JWT=$(echo "$LOGIN_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('data',{}).get('jwt',''))" 2>/dev/null || echo "")

if [[ -z "${JWT}" || "${JWT}" == "None" ]]; then
  echo -e "${RED}FAIL: Login returned no JWT.${NC}"
  echo "      Response: ${LOGIN_RESP}"
  exit 1
fi

echo -e "${GREEN}   JWT obtained${NC}"

# If the user belongs to multiple tenants, select the business tenant.
TENANT_ID=$(echo "$LOGIN_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('data',{}).get('tenantId',''))" 2>/dev/null || echo "")
if [[ -z "${TENANT_ID}" || "${TENANT_ID}" == "None" ]]; then
  echo "   Multi-tenant login — selecting business tenant..."
  SPACES=$(NO_PROXY=localhost curl -s "${BACKEND_URL}/api/tenant-selection/my-spaces" \
    -H "Authorization: Bearer ${JWT}" 2>/dev/null)
  BIZ_TENANT=$(echo "$SPACES" | python3 -c "
import sys,json
spaces=json.load(sys.stdin).get('data',[])
for s in spaces:
    if s.get('spaceType')=='business':
        print(s.get('tenantId','')); break
" 2>/dev/null || echo "")

  if [[ -z "${BIZ_TENANT}" || "${BIZ_TENANT}" == "None" ]]; then
    echo -e "${RED}FAIL: No business tenant found for ${ADMIN_EMAIL}.${NC}"
    exit 1
  fi

  SELECT=$(NO_PROXY=localhost curl -s -X POST "${BACKEND_URL}/api/tenant-selection/process" \
    -H "Authorization: Bearer ${JWT}" -H "Content-Type: application/json" \
    -d "{\"action\":\"select\",\"tenantId\":${BIZ_TENANT}}" 2>/dev/null)
  NEW_JWT=$(echo "$SELECT" | python3 -c "import sys,json; print(json.load(sys.stdin).get('data',{}).get('jwt',''))" 2>/dev/null || echo "")
  if [[ -n "${NEW_JWT}" && "${NEW_JWT}" != "None" ]]; then
    JWT="${NEW_JWT}"
    echo -e "${GREEN}   Business tenant selected (tenantId=${BIZ_TENANT})${NC}"
  else
    echo -e "${RED}FAIL: Tenant selection failed: ${SELECT}${NC}"
    exit 1
  fi
fi

AUTH_HEADER="Authorization: Bearer ${JWT}"

# ── Step 3: Import plugins ────────────────────────────────────────────────────
echo ""
echo -e "${YELLOW}[3] Importing plugins (${#PLUGINS_TO_IMPORT[@]} total)...${NC}"

FAILED=0
SUCCEEDED=0

import_plugin() {
  local plugin="$1"
  local container_path="${CONTAINER_PLUGINS_PATH}/${plugin}"

  # Quick sanity-check on host side
  local host_path
  host_path="$(cd "$(dirname "${BASH_SOURCE[0]}")" && cd .. && pwd)/plugins/${plugin}"
  if [[ ! -d "${host_path}" ]]; then
    echo -e "   ${YELLOW}SKIP${NC} ${plugin} (not found at ${host_path})"
    return
  fi

  local resp
  resp=$(NO_PROXY=localhost curl -s -X POST \
    "${BACKEND_URL}/api/plugins/import/import-directory-sync" \
    -H "${AUTH_HEADER}" \
    -H "Content-Type: application/json" \
    -d "{\"path\":\"${container_path}\",\"conflictStrategy\":\"OVERWRITE\"}" \
    2>/dev/null)

  local success
  success=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin).get('success', False))" 2>/dev/null || echo "False")

  if [[ "${success}" == "True" ]]; then
    echo -e "   ${GREEN}✓${NC} ${plugin} imported"
    SUCCEEDED=$((SUCCEEDED + 1))
  else
    local err
    err=$(echo "$resp" | python3 -c "
import sys,json
d=json.load(sys.stdin)
print(d.get('errorMessage') or d.get('message') or str(d))
" 2>/dev/null || echo "${resp}")
    echo -e "   ${RED}✗${NC} ${plugin} failed: ${err}"
    FAILED=$((FAILED + 1))
  fi
}

for plugin in "${PLUGINS_TO_IMPORT[@]}"; do
  import_plugin "${plugin}"
done

echo ""
echo "   Imported: ${SUCCEEDED} / $((SUCCEEDED + FAILED)) plugins"

if [[ "${FAILED}" -gt 0 ]]; then
  echo -e "${RED}FAIL: ${FAILED} plugin(s) failed to import.${NC}"
  exit 1
fi
echo -e "${GREEN}   All plugins imported successfully${NC}"

# ── Step 4: Verify BPM menu is present ───────────────────────────────────────
echo ""
echo -e "${YELLOW}[4] Verifying BPM/流程 menu is visible...${NC}"

# The menu endpoint is /api/menu/user (singular), not /api/menus.
MENUS_RESP=$(NO_PROXY=localhost curl -s "${BACKEND_URL}/api/menu/user" \
  -H "${AUTH_HEADER}" 2>/dev/null)

MENU_CHECK=$(echo "$MENUS_RESP" | python3 -c "
import sys,json,re
raw=sys.stdin.read()
if re.search(r'BPM|bpm|流程|designer|workflow', raw, re.IGNORECASE):
    print('FOUND')
else:
    print('NOT_FOUND')
" 2>/dev/null || echo "NOT_FOUND")

if [[ "${MENU_CHECK}" == "FOUND" ]]; then
  echo -e "${GREEN}   OK — BPM/流程 menu entry is present in /api/menus${NC}"
else
  echo -e "${RED}   FAIL — no BPM/流程 menu found in /api/menus response${NC}"
  echo "   Raw response snippet: $(echo "$MENUS_RESP" | python3 -c "import sys; s=sys.stdin.read(); print(s[:500])" 2>/dev/null)"
  exit 1
fi

echo ""
echo -e "${BLUE}=== Bootstrap complete ===${NC}"
echo -e "${GREEN}Plugins imported, BPM menu verified. E2E tests can now run.${NC}"
echo ""
echo "  Backend:  ${BACKEND_URL}"
echo "  Frontend: http://localhost:5174"
