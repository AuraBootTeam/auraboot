#!/bin/bash

# AuraBoot OSS Environment Reset and Initialization Script
#
# Responsibility: reset environment (DB + services) and optionally import data.
#                 NEVER auto-compensate missing data — that masks bootstrap failures.
#
# Default flow (all 8 steps):
# 1-2. Stop backend + frontend services
# 3.   Reset database (drop + recreate)
# 4.   Start backend + wait for health check
# 4.5  Bootstrap via /api/bootstrap/setup API (creates admin + System Tenant +
#      Business Tenant + platform_admin/tenant_admin role assignments)
# 5.   Start frontend + wait for ready
# 6.   Seed test pages, dashboard, multi-role users (pure API)
# 7.   Verify bootstrap data
# 7.5  Import plugins via CLI
# 7.6-7.8. Backfill + marketplace seed + CS Agent seed
# 8.   (Optional) Seed showcase demo data via Playwright
#
# --no-bootstrap flow (steps 1-5 only):
# 1-2. Stop services
# 3.   Reset DB
# 4.   Start backend (uninitialized)
# 5.   Start frontend
#      → browser shows bootstrap banner; user drives /setup manually
#
# Usage: ./scripts/oss-reset-and-init.sh [--no-bootstrap]
# Skip seed data: SKIP_SEED=1 ./scripts/oss-reset-and-init.sh

set -e

# Parse arguments
NO_BOOTSTRAP=0
for arg in "$@"; do
    case "$arg" in
        --no-bootstrap)
            NO_BOOTSTRAP=1
            ;;
        -h|--help)
            echo "Usage: $0 [--no-bootstrap]"
            echo ""
            echo "  (default)       Reset DB, start services, bootstrap system, import plugins, seed demo data"
            echo "  --no-bootstrap  Reset DB and start services only; system stays uninitialized"
            echo "                  (visit http://localhost:5173/setup to bootstrap via the web wizard)"
            echo ""
            echo "Env vars:"
            echo "  SKIP_SEED=1     Skip Playwright showcase seed (step 8)"
            echo "  AURA_ENV=test   Also import test-fixtures plugin"
            exit 0
            ;;
    esac
done

# Avoid Node warning: "NO_COLOR is ignored due to FORCE_COLOR being set"
unset NO_COLOR

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
PLATFORM_DIR="$PROJECT_ROOT/platform"
WEB_ADMIN_DIR="$PROJECT_ROOT/web-admin"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}=== AuraBoot Environment Reset & Initialization ===${NC}"
echo ""

check_http() {
    local name="$1"
    local url="$2"
    local expected="$3"
    local code
    code=$(NO_PROXY=localhost curl -s -o /dev/null -w "%{http_code}" "$url" 2>/dev/null || echo "000")
    if [[ "$expected" == *"$code"* ]]; then
        echo -e "${GREEN}   ${name} is ready (HTTP ${code})${NC}"
        return 0
    fi
    echo -e "${RED}   ${name} is not ready (HTTP ${code}, expected: ${expected})${NC}"
    return 1
}

# Step 1: Stop backend service
echo -e "${YELLOW}Step 1: Stopping backend service...${NC}"
pkill -f "com.auraboot.framework.application.MetaApplication" 2>/dev/null || true
sleep 2
echo -e "${GREEN}   Backend service stopped${NC}"

# Step 2: Stop BFF server if running
echo -e "${YELLOW}Step 2: Stopping BFF server...${NC}"
pkill -f "concurrently" 2>/dev/null || true
pkill -f "pnpm dev:web" 2>/dev/null || true
pkill -f "pnpm dev:bff" 2>/dev/null || true
pkill -f "pnpm dev" 2>/dev/null || true
pkill -f "bff.server" 2>/dev/null || true
pkill -f "react-router dev" 2>/dev/null || true
pkill -f "vite" 2>/dev/null || true
sleep 1
echo -e "${GREEN}   Frontend/BFF dev processes stopped${NC}"

# Step 3: Reset database
echo -e "${YELLOW}Step 3: Resetting database...${NC}"
echo "y" | "$SCRIPT_DIR/reset-db.sh"
echo -e "${GREEN}   Database reset complete${NC}"

# Step 4: Start backend service
echo -e "${YELLOW}Step 4: Starting backend service...${NC}"
cd "$PLATFORM_DIR"

# Disable AdminBootstrapRunner so this script is the sole authority for
# bootstrap. Otherwise it races the explicit /api/bootstrap/setup call below
# (auto-creates admin → BootstrapEngineService then fails to recreate it).
# AdminBootstrapRunner exists for Docker first-run convenience (no script);
# here the script drives, so disable it in BOTH modes.
export AURABOOT_BOOTSTRAP_ENABLED=false
echo "   AURABOOT_BOOTSTRAP_ENABLED=false (script-driven bootstrap, no auto-runner)"

# Start backend in background as a single long-running process
nohup ./gradlew bootRun > /tmp/aura-backend.log 2>&1 &
BACKEND_PID=$!

echo "   Backend starting (PID: $BACKEND_PID)..."
echo "   Waiting for backend to be ready..."

# Wait for backend to be ready (max 120 seconds)
MAX_WAIT=120
WAITED=0
while [ $WAITED -lt $MAX_WAIT ]; do
    HTTP_CODE=$(NO_PROXY=localhost curl -s -o /dev/null -w "%{http_code}" http://localhost:6443/actuator/health 2>/dev/null || echo "000")

    if [ "$HTTP_CODE" = "200" ]; then
        echo -e "${GREEN}   Backend is ready (took ${WAITED}s)${NC}"
        break
    fi

    sleep 3
    WAITED=$((WAITED + 3))
    echo "   Still waiting... (${WAITED}s)"
done

if [ $WAITED -ge $MAX_WAIT ]; then
    echo -e "${RED}   Backend failed to start within ${MAX_WAIT} seconds${NC}"
    echo "   Check logs at /tmp/aura-backend.log"
    exit 1
fi

# Step 4.5: Bootstrap system (create admin + System Tenant + Business Tenant via API)
#
# Only the standard /api/bootstrap/setup flow is used. If that flow fails, the
# script exits so the real error surfaces — never auto-compensate by writing
# system_config or INSERTing tenants directly.
if [ "$NO_BOOTSTRAP" = "1" ]; then
    echo -e "${YELLOW}Step 4.5: Skipping bootstrap (--no-bootstrap mode).${NC}"
    echo "   System will remain uninitialized. Visit http://localhost:5173/setup to bootstrap."
else
    echo -e "${YELLOW}Step 4.5: Bootstrapping system...${NC}"

    BOOTSTRAP_STATUS=$(NO_PROXY=localhost curl -s http://localhost:6443/api/bootstrap/status 2>/dev/null || echo '{}')
    IS_INITIALIZED=$(echo "$BOOTSTRAP_STATUS" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('initialized',False))" 2>/dev/null || echo "False")

    if [ "$IS_INITIALIZED" = "True" ]; then
        echo -e "${GREEN}   System already initialized, skipping bootstrap${NC}"
    else
        echo "   Calling /api/bootstrap/setup..."
        BOOTSTRAP_RESP=$(NO_PROXY=localhost curl -s -w "\n%{http_code}" -X POST http://localhost:6443/api/bootstrap/setup \
            -H "Content-Type: application/json" \
            -d '{
                "companyName": "AuraBoot Dev",
                "adminEmail": "admin@example.com",
                "adminPassword": "Test2026x",
                "adminDisplayName": "Admin User",
                "systemMode": "single",
                "seedDemoData": false
            }' 2>/dev/null)

        BOOTSTRAP_BODY=$(echo "$BOOTSTRAP_RESP" | sed '$d')
        BOOTSTRAP_HTTP=$(echo "$BOOTSTRAP_RESP" | tail -1)

        if [ "$BOOTSTRAP_HTTP" != "200" ]; then
            echo -e "${RED}   Bootstrap failed (HTTP $BOOTSTRAP_HTTP): $BOOTSTRAP_BODY${NC}"
            exit 1
        fi
        BOOTSTRAP_CODE=$(echo "$BOOTSTRAP_BODY" | python3 -c "import sys,json; print(json.load(sys.stdin).get('code',''))" 2>/dev/null || echo "")
        if [ "$BOOTSTRAP_CODE" != "0" ]; then
            echo -e "${RED}   Bootstrap returned error: $BOOTSTRAP_BODY${NC}"
            exit 1
        fi
        BOOTSTRAP_TENANT=$(echo "$BOOTSTRAP_BODY" | python3 -c "import sys,json; print(json.load(sys.stdin).get('data',{}).get('tenantId',''))" 2>/dev/null || echo "")
        echo -e "${GREEN}   Bootstrap successful (tenantId=$BOOTSTRAP_TENANT)${NC}"
    fi
fi

if [ "$NO_BOOTSTRAP" != "1" ]; then
    # Verify login works
    echo "   Verifying admin login..."
    LOGIN_RESP=$(NO_PROXY=localhost curl -s -X POST http://localhost:6443/api/auth/login \
        -H "Content-Type: application/json" \
        -d '{"email":"admin@example.com","password":"Test2026x"}' 2>/dev/null)

    LOGIN_JWT=$(echo "$LOGIN_RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('jwt',''))" 2>/dev/null || echo "")
    LOGIN_TENANT=$(echo "$LOGIN_RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('tenantId',''))" 2>/dev/null || echo "")

    if [ -z "$LOGIN_JWT" ] || [ "$LOGIN_JWT" = "None" ]; then
        echo -e "${RED}   Login verification failed: no JWT returned${NC}"
        echo "   Response: $LOGIN_RESP"
        exit 1
    fi

    # When user belongs to multiple tenants (System + Default), the login flow
    # may fail to auto-resolve the default tenant. Use the tenantId from bootstrap.
    if [ -z "$LOGIN_TENANT" ] || [ "$LOGIN_TENANT" = "None" ] || [ "$LOGIN_TENANT" = "" ]; then
        echo "   Login returned no tenantId, selecting space via API..."
        # Use /api/tenant-selection/my-spaces to find the business tenant, then select it
        SPACES_RESP=$(NO_PROXY=localhost curl -s http://localhost:6443/api/tenant-selection/my-spaces \
            -H "Authorization: Bearer $LOGIN_JWT" 2>/dev/null)
        BIZ_TENANT_ID=$(echo "$SPACES_RESP" | python3 -c "
import sys,json
d=json.load(sys.stdin)
spaces=d.get('data',[])
for s in spaces:
    if s.get('spaceType')=='business':
        print(s.get('tenantId','')); break
" 2>/dev/null || echo "")

        if [ -z "$BIZ_TENANT_ID" ] || [ "$BIZ_TENANT_ID" = "None" ]; then
            echo -e "${RED}   No business space found for admin user${NC}"
            exit 1
        fi

        # Select the business space to get a JWT with tenantId
        SELECT_RESP=$(NO_PROXY=localhost curl -s -X POST http://localhost:6443/api/tenant-selection/process \
            -H "Authorization: Bearer $LOGIN_JWT" -H "Content-Type: application/json" \
            -d "{\"action\":\"select\",\"tenantId\":$BIZ_TENANT_ID}" 2>/dev/null)
        NEW_JWT=$(echo "$SELECT_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('data',{}).get('jwt',''))" 2>/dev/null || echo "")

        if [ -n "$NEW_JWT" ] && [ "$NEW_JWT" != "None" ]; then
            LOGIN_JWT="$NEW_JWT"
            LOGIN_TENANT="$BIZ_TENANT_ID"
            echo -e "${GREEN}   Space selected: tenantId=$BIZ_TENANT_ID${NC}"
        else
            echo -e "${RED}   Space selection failed: $SELECT_RESP${NC}"
            exit 1
        fi
    fi

    echo -e "${GREEN}   Admin login verified: tenantId=$LOGIN_TENANT${NC}"
fi

# Step 5: Start frontend
echo -e "${YELLOW}Step 5: Starting frontend...${NC}"
cd "$WEB_ADMIN_DIR"

echo "   Running plugin sync before starting dev servers..."
pnpm sync-plugins > /tmp/aura-sync-plugins.log 2>&1

nohup pnpm dev:web > /tmp/aura-web.log 2>&1 &
WEB_PID=$!
nohup pnpm dev:bff > /tmp/aura-bff.log 2>&1 &
BFF_PID=$!

echo "   Frontend starting (web PID: $WEB_PID, bff PID: $BFF_PID)..."
echo "   Waiting for frontend and BFF to be ready..."

# Wait for frontend and BFF to be ready
MAX_WAIT_FE=30
WAITED_FE=0
while [ $WAITED_FE -lt $MAX_WAIT_FE ]; do
    FRONTEND_HTTP_CODE=$(NO_PROXY=localhost curl -s -o /dev/null -w "%{http_code}" http://localhost:5173 2>/dev/null || echo "000")
    BFF_HTTP_CODE=$(NO_PROXY=localhost curl -s -o /dev/null -w "%{http_code}" http://localhost:3500/health 2>/dev/null || echo "000")

    if { [ "$FRONTEND_HTTP_CODE" = "200" ] || [ "$FRONTEND_HTTP_CODE" = "302" ] || [ "$FRONTEND_HTTP_CODE" = "304" ]; } && [ "$BFF_HTTP_CODE" = "200" ]; then
        echo -e "${GREEN}   Frontend+BFF are ready (took ${WAITED_FE}s)${NC}"
        break
    fi

    sleep 2
    WAITED_FE=$((WAITED_FE + 2))
    echo "   Still waiting... (frontend=${FRONTEND_HTTP_CODE}, bff=${BFF_HTTP_CODE}, ${WAITED_FE}s)"
done

if [ $WAITED_FE -ge $MAX_WAIT_FE ]; then
    echo -e "${RED}   Frontend/BFF failed to start within ${MAX_WAIT_FE} seconds${NC}"
    echo "   Check logs at /tmp/aura-web.log and /tmp/aura-bff.log"
    exit 1
fi

if [ "$NO_BOOTSTRAP" != "1" ]; then
    # Step 6: Seed test pages, dashboard, and multi-role users (pure API calls, no Playwright)
    echo -e "${YELLOW}Step 6: Seeding test pages & dashboard...${NC}"

    AUTH_HEADER="Authorization: Bearer $LOGIN_JWT"
    API_BASE="http://localhost:6443"

    # Helper: POST JSON with auth
    api_post() {
        NO_PROXY=localhost curl -s -X POST "$API_BASE$1" \
            -H "$AUTH_HEADER" -H "Content-Type: application/json" \
            -d "$2" 2>/dev/null
    }

    # Helper: PUT JSON with auth
    api_put() {
        NO_PROXY=localhost curl -s -X PUT "$API_BASE$1" \
            -H "$AUTH_HEADER" -H "Content-Type: application/json" \
            -d "$2" 2>/dev/null
    }

    # Helper: GET with auth
    api_get() {
        NO_PROXY=localhost curl -s "$API_BASE$1" -H "$AUTH_HEADER" 2>/dev/null
    }

    # 6a: Create test pages for Page Designer (idempotent — skip if exists)
    create_test_page() {
        local page_key="$1"
        local payload="$2"
        local existing
        existing=$(api_get "/api/pages/key/$page_key" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('pid',''))" 2>/dev/null || echo "")
        if [ -n "$existing" ] && [ "$existing" != "None" ] && [ "$existing" != "" ]; then
            echo "   $page_key already exists, skipping"
            return
        fi
        local resp
        local pid
        local message
        resp=$(api_post "/api/pages" "$payload")
        pid=$(echo "$resp" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('pid',''))" 2>/dev/null || echo "")
        message=$(echo "$resp" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('errorMessage') or d.get('message') or '')" 2>/dev/null || echo "")
        if [ -n "$pid" ] && [ "$pid" != "None" ] && [ "$pid" != "" ]; then
            api_post "/api/pages/$pid/publish" "{}" > /dev/null
            echo "   Created & published: $page_key"
        else
            echo -e "${YELLOW}   Failed to create $page_key: ${message:-$resp}${NC}"
        fi
    }

    create_test_page "e2e_test_dashboard" '{
        "pageKey":"e2e_test_dashboard","name":"E2E Test Dashboard","title":"E2E Test Dashboard","modelCode":"page_schema",
        "description":"Overview-style list fixture for Page Designer E2E tests",
        "kind":"list",
        "layout":{"type":"grid","cols":12},
        "blocks":[
            {"id":"block_overview_stats","blockType":"stat-card","layout":{"colSpan":12,"rowSpan":1},"title":"Overview","cards":[{"label":"Total","value":"1234"},{"label":"Today","value":"56"}]},
            {"id":"block_overview_table","blockType":"table","layout":{"colSpan":12,"rowSpan":1},"columns":[{"field":"name","title":"Name","width":200},{"field":"page_key","title":"Page Key","width":220},{"field":"status","title":"Status","width":120},{"field":"updated_at","title":"Updated At","width":180}]}
        ]
    }'

    create_test_page "e2e_test_form" '{
        "pageKey":"e2e_test_form","name":"E2E Test Form","title":"E2E Test Form","modelCode":"page_schema",
        "description":"Form fixture for Page Designer E2E tests",
        "kind":"form",
        "layout":{"type":"grid","cols":12,"gap":12},
        "blocks":[
            {"id":"block_form_main","blockType":"form-section","title":"Basic Information","layout":{"colSpan":12,"rowSpan":1},"columns":2,"fields":[{"field":"name","layout":{"colSpan":6,"rowSpan":1}},{"field":"page_key","layout":{"colSpan":6,"rowSpan":1}},{"field":"kind","layout":{"colSpan":4,"rowSpan":1}},{"field":"profile","layout":{"colSpan":4,"rowSpan":1}},{"field":"model_code","layout":{"colSpan":4,"rowSpan":1}},{"field":"description","layout":{"colSpan":12,"rowSpan":1}}]},
            {"id":"block_form_actions","blockType":"form-buttons","layout":{"colSpan":12,"rowSpan":1},"buttons":[{"code":"save","primary":true,"label":"save"},{"code":"reset","label":"reset"}]}
        ]
    }'

    create_test_page "e2e_test_list" '{
        "pageKey":"e2e_test_list","name":"E2E Test List","title":"E2E Test List","modelCode":"page_schema",
        "description":"List fixture for Page Designer E2E tests",
        "kind":"list",
        "layout":{"type":"stack"},
        "blocks":[
            {"id":"block_list_toolbar","blockType":"toolbar","buttons":[{"code":"create","variant":"primary","label":"create"},{"code":"refresh","label":"refresh"}]},
            {"id":"block_list_filters","blockType":"filters","fields":[{"field":"name"},{"field":"status"}]},
            {"id":"block_list_table","blockType":"table","columns":[{"field":"name","title":"Name","width":200},{"field":"page_key","title":"Page Key","width":220},{"field":"status","title":"Status","width":120},{"field":"updated_at","title":"Updated At","width":180}],"rowActions":[{"code":"view","label":"view"},{"code":"edit","label":"edit"},{"code":"delete","label":"delete"}]}
        ]
    }'

    echo -e "${GREEN}   Test pages complete${NC}"

    # 6b: Create system_overview dashboard (idempotent)
    echo "   Creating system_overview dashboard..."
    DASH_EXISTS=$(api_get "/api/dashboards/code/system_overview" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('pid',''))" 2>/dev/null || echo "")
    SYSTEM_OVERVIEW_PAYLOAD='{
        "code":"system_overview",
        "title":"System Overview",
        "description":"Live overview dashboard seeded for local development",
        "scope":"global",
        "isDefault":true,
        "layoutConfig":{"columns":12,"rowHeight":100,"gap":16},
        "widgets":[
            {"i":"w_accounts","x":0,"y":0,"w":3,"h":2,"type":"NumberCard","title":"Accounts","config":{"title":"Accounts","label":"Accounts","color":"#2563EB","dataSource":{"type":"aggregate","modelCode":"crm_account","metrics":[{"field":"id","aggregation":"count","alias":"count"}]}}},
            {"i":"w_contacts","x":3,"y":0,"w":3,"h":2,"type":"NumberCard","title":"Contacts","config":{"title":"Contacts","label":"Contacts","color":"#10B981","dataSource":{"type":"aggregate","modelCode":"crm_contact","metrics":[{"field":"id","aggregation":"count","alias":"count"}]}}},
            {"i":"w_leads","x":6,"y":0,"w":3,"h":2,"type":"NumberCard","title":"Leads","config":{"title":"Leads","label":"Leads","color":"#F59E0B","dataSource":{"type":"aggregate","modelCode":"crm_lead","metrics":[{"field":"id","aggregation":"count","alias":"count"}]}}},
            {"i":"w_opportunities","x":9,"y":0,"w":3,"h":2,"type":"NumberCard","title":"Opportunities","config":{"title":"Opportunities","label":"Opportunities","color":"#8B5CF6","dataSource":{"type":"aggregate","modelCode":"crm_opportunity","metrics":[{"field":"id","aggregation":"count","alias":"count"}]}}}
        ]
    }'

    if [ -n "$DASH_EXISTS" ] && [ "$DASH_EXISTS" != "None" ] && [ "$DASH_EXISTS" != "" ]; then
        api_put "/api/dashboards/$DASH_EXISTS" "$SYSTEM_OVERVIEW_PAYLOAD" > /dev/null
        api_post "/api/dashboards/$DASH_EXISTS/publish" "{}" > /dev/null
        echo "   system_overview updated"
    else
        DASH_RESP=$(api_post "/api/dashboards" "$SYSTEM_OVERVIEW_PAYLOAD")
        DASH_PID=$(echo "$DASH_RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('pid',''))" 2>/dev/null || echo "")
        if [ -n "$DASH_PID" ] && [ "$DASH_PID" != "None" ] && [ "$DASH_PID" != "" ]; then
            api_post "/api/dashboards/$DASH_PID/publish" "{}" > /dev/null
            echo -e "${GREEN}   Created & published system_overview${NC}"
        else
            echo -e "${YELLOW}   Dashboard creation skipped (may already exist)${NC}"
        fi
    fi

    # 6c: Provision multi-role test users via Admin Create User API (idempotent)
    echo "   Setting up multi-role test users..."

    provision_user() {
        local email="$1"
        local password="$2"
        local role_code="$3"
        local display_name
        display_name=$(echo "$email" | cut -d@ -f1)

        local resp
        resp=$(api_post "/api/admin/users" "{
            \"email\":\"$email\",
            \"displayName\":\"$display_name\",
            \"initialPassword\":\"$password\",
            \"roleCodes\":[\"$role_code\"],
            \"sendInviteEmail\":false
        }")
        local result_email
        result_email=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin).get('data',{}).get('email',''))" 2>/dev/null || echo "")

        if [ -n "$result_email" ] && [ "$result_email" != "None" ] && [ "$result_email" != "" ]; then
            echo -e "${GREEN}   $email: provisioned with $role_code role${NC}"
        else
            local err_msg
            err_msg=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin).get('message','unknown'))" 2>/dev/null || echo "unknown")
            if echo "$err_msg" | grep -qi "already exists"; then
                echo "   $email already exists, skipping"
            else
                echo -e "${YELLOW}   $email: provisioning failed — $err_msg${NC}"
            fi
        fi
    }

    provision_user "e2e-operator@test.com" "Test2026x" "operator"
    provision_user "e2e-viewer@test.com" "Test2026x" "viewer"

    # 6d: Ensure Playwright storageState exists for E2E tests
    echo "   Generating Playwright storageState..."
    cd "$WEB_ADMIN_DIR"
    mkdir -p tests/storage

    # Login via BFF to get session cookie, then save storage state
    BFF_LOGIN_RESP=$(NO_PROXY=localhost curl -s -D - -o /dev/null -X POST http://localhost:5173/login \
        -H "Content-Type: application/x-www-form-urlencoded" \
        -d "email=admin@example.com&password=Test2026x&remember=on&redirectTo=/" 2>/dev/null)
    SESSION_COOKIE=$(echo "$BFF_LOGIN_RESP" | grep -i "set-cookie.*__session" | sed 's/.*__session=\([^;]*\).*/\1/' | head -1)

    if [ -n "$SESSION_COOKIE" ]; then
        cat > tests/storage/admin.json << STORAGEJSON
{
  "cookies": [
    {"name":"__session","value":"$SESSION_COOKIE","domain":"localhost","path":"/","httpOnly":true,"sameSite":"Lax","expires":$(python3 -c "import time; print(int(time.time())+604800)")},
    {"name":"__session","value":"$SESSION_COOKIE","domain":"127.0.0.1","path":"/","httpOnly":true,"sameSite":"Lax","expires":$(python3 -c "import time; print(int(time.time())+604800)")}
  ],
  "origins": []
}
STORAGEJSON
        echo -e "${GREEN}   StorageState saved to tests/storage/admin.json${NC}"
    else
        echo -e "${YELLOW}   Could not extract session cookie (E2E tests may need manual login)${NC}"
    fi

    echo -e "${GREEN}   Step 6 complete (no Playwright required)${NC}"

    echo -e "${YELLOW}Step 7: Verifying user/tenant bootstrap data...${NC}"
    DB_NAME="${POSTGRES_DB:-aura_boot}"
    DB_USER="${POSTGRES_USER:-$(whoami)}"
    DB_HOST="${POSTGRES_HOST:-localhost}"
    BOOTSTRAP_CHECK=$(psql -h localhost -U ghj -d aura_boot -P pager=off -t -A -F',' -c "
SELECT
  (SELECT COUNT(*) FROM ab_user WHERE email='admin@example.com' AND (deleted_flag=FALSE OR deleted_flag IS NULL)) AS admin_users,
  (SELECT COUNT(*) FROM ab_tenant WHERE (deleted_flag=FALSE OR deleted_flag IS NULL)) AS tenants,
  (SELECT COUNT(*) FROM ab_tenant_member tm JOIN ab_user u ON u.id=tm.user_id
    WHERE u.email='admin@example.com' AND (tm.deleted_flag=FALSE OR tm.deleted_flag IS NULL)) AS admin_memberships;
")
    IFS=',' read -r ADMIN_USERS TENANT_COUNT ADMIN_MEMBERSHIPS <<< "$BOOTSTRAP_CHECK"

    echo "   admin users: ${ADMIN_USERS}"
    echo "   tenant count: ${TENANT_COUNT}"
    echo "   admin memberships: ${ADMIN_MEMBERSHIPS}"

    if [ "${ADMIN_USERS}" -lt 1 ] || [ "${TENANT_COUNT}" -lt 1 ] || [ "${ADMIN_MEMBERSHIPS}" -lt 1 ]; then
        echo -e "${RED}   Bootstrap verification failed: user/tenant/member data is incomplete${NC}"
        exit 1
    fi
    echo -e "${GREEN}   Bootstrap verification passed${NC}"

    # Step 7.5: Import required plugins for seed data
    echo -e "${YELLOW}Step 7.5: Importing plugins...${NC}"
    cd "$PROJECT_ROOT"

    # Use LOGIN_JWT for CLI authentication (DB was reset, cached token is stale)
    export AURA_TOKEN="$LOGIN_JWT"

    # Import in dependency order: base plugins first, then dependent ones
    PLUGINS_TO_IMPORT=(core-meta core-bpm core-aurabot page-manager platform-admin org-management crm-starter showcase agent-control-plane acp-showcase workflow-demo)

    # test-fixtures is an internal-only plugin that provides e2et_order/e2et_customer/
    # e2et_payment schemas for the E2E test suite. It is excluded from demo flows and
    # public distribution; import it only when explicitly running tests.
    if [ "${AURA_ENV:-demo}" = "test" ] || [ "${IMPORT_TEST_FIXTURES:-false}" = "true" ]; then
        echo "   Including test-fixtures (AURA_ENV=test or IMPORT_TEST_FIXTURES=true)"
        PLUGINS_TO_IMPORT+=(test-fixtures)
    fi

    for plugin in "${PLUGINS_TO_IMPORT[@]}"; do
        if [ -d "plugins/$plugin" ]; then
            echo -n "   Importing $plugin... "
            NO_PROXY=localhost aura plugin publish "plugins/$plugin" --yes 2>&1 | tail -1
        fi
    done
    echo -e "${GREEN}   Plugin import complete${NC}"

    # Step 7.6: Backfill model displayName for AuraBot Chinese search
    echo -e "${YELLOW}Step 7.6: Backfilling model displayNames...${NC}"
    psql -h localhost -U ghj -d aura_boot -f "$SCRIPT_DIR/backfill-model-displayname.sql" -P pager=off 2>&1 | tail -1
    echo -e "${GREEN}   DisplayName backfill complete${NC}"

    # Step 7.7: Seed marketplace registry
    echo -e "${YELLOW}Step 7.7: Seeding marketplace...${NC}"
    "$SCRIPT_DIR/seed-marketplace.sh" 2>&1 | tail -1
    echo -e "${GREEN}   Marketplace seed complete${NC}"

    # Step 7.8: Seed CS Agent definition
    echo -e "${YELLOW}Step 7.8: Seeding CS Agent definition...${NC}"
    psql -h localhost -U ghj -d aura_boot -f "$SCRIPT_DIR/seed-cs-agent.sql" -P pager=off 2>&1 | grep -E "NOTICE|ERROR" | tail -5
    echo -e "${GREEN}   CS Agent seed complete${NC}"

    # Step 8: Seed showcase demo data (optional — skip with SKIP_SEED=1)
    if [ "${SKIP_SEED:-0}" != "1" ]; then
        echo -e "${YELLOW}Step 8: Seeding showcase demo data...${NC}"
        cd "$WEB_ADMIN_DIR"

        SEED_CONFIG="playwright.seed.config.ts"

        echo "   Seeding core data (org + CRM accounts/leads/opportunities)..."
        NO_PROXY=localhost npx playwright test seed-showcase-data --config=$SEED_CONFIG --reporter=line 2>&1 | tail -3
        echo -e "${GREEN}   Core seed complete${NC}"

        echo "   Seeding extended data (bulk accounts/leads/activities)..."
        NO_PROXY=localhost npx playwright test seed-showcase-extended --config=$SEED_CONFIG --reporter=line 2>&1 | tail -3
        echo -e "${GREEN}   Extended seed complete${NC}"

        echo "   Seeding workflow data (BPMN/automation/webhook)..."
        NO_PROXY=localhost npx playwright test seed-showcase-workflow --config=$SEED_CONFIG --reporter=line 2>&1 | tail -3
        echo -e "${GREEN}   Workflow seed complete${NC}"

        echo "   Seeding AI data (agents/knowledge base)..."
        NO_PROXY=localhost npx playwright test seed-showcase-ai --config=$SEED_CONFIG --reporter=line 2>&1 | tail -3
        echo -e "${GREEN}   AI seed complete${NC}"

        echo "   Seeding arsenal showcase (all-fields + dashboard + report + BPMN + automation)..."
        NO_PROXY=localhost npx playwright test seed-showcase-arsenal --config=$SEED_CONFIG --reporter=line 2>&1 | tail -3
        echo -e "${GREEN}   Arsenal seed complete${NC}"

        echo "   Seeding supplementary data (more contacts + leads + activities)..."
        NO_PROXY=localhost npx playwright test seed-showcase-supplement --config=$SEED_CONFIG --reporter=line 2>&1 | tail -3
        echo -e "${GREEN}   Supplement seed complete${NC}"

        echo "   Seeding commercial data (quotes/complaints/IM/email/opp-contacts)..."
        NO_PROXY=localhost npx playwright test seed-showcase-commercial --config=$SEED_CONFIG --reporter=line 2>&1 | tail -3
        echo -e "${GREEN}   Commercial seed complete${NC}"

        echo -e "${GREEN}   All showcase data seeded successfully${NC}"
    else
        echo -e "${YELLOW}Step 8: Skipping showcase seed (SKIP_SEED=1)${NC}"
    fi
fi

# Final summary
echo ""
if [ "$NO_BOOTSTRAP" = "1" ]; then
    echo -e "${BLUE}=== Environment Ready (NOT initialized) ===${NC}"
    echo ""
    echo -e "${YELLOW}System is uninitialized. To complete setup:${NC}"
    echo "  - Visit http://localhost:5173/setup in your browser"
    echo "  - The banner on / will guide you there"
else
    echo -e "${BLUE}=== Initialization Complete ===${NC}"
    echo ""
    echo -e "${GREEN}Environment is ready with:${NC}"
    echo "  - User: admin@example.com / Test2026x"
    echo "  - Tenant: AuraBoot Dev"
fi
echo ""
echo -e "${YELLOW}Services running:${NC}"
echo "  - Backend: http://localhost:6443"
echo "  - Frontend: http://localhost:5173"
echo ""
echo -e "${BLUE}Logs:${NC}"
echo "  - Backend: /tmp/aura-backend.log"
echo "  - Frontend web: /tmp/aura-web.log"
echo "  - Frontend bff: /tmp/aura-bff.log"
echo "  - Plugin sync: /tmp/aura-sync-plugins.log"
echo ""
