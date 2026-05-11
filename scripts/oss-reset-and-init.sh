#!/bin/bash

# AuraBoot OSS Environment Reset and Initialization Script
#
# Responsibility: reset environment (DB + services) and optionally import data.
#                 NEVER auto-compensate missing data — that masks bootstrap failures.
#
# History:
#   2026-05-10 — §8 seed is capability-aware for the OSS/full-CRM split:
#                base showcase seed remains fail-fast; commercial CRM seed runs
#                only when full CRM quote/complaint commands are present unless
#                SHOWCASE_COMMERCIAL_SEED=required; dashboard-default targets
#                SHOWCASE_DEFAULT_DASHBOARD_CODE or the best imported CRM
#                dashboard (crm_dashboard, else crm_overview).
#   2026-05-10 — §8 seed is now fail-fast: Playwright seed output is written to
#                per-step logs, failures print the tail and stop the script, and
#                final invariants verify CRM/showcase/arsenal/default dashboard.
#   2026-05-09 — §7.5 removed (Phase 3 / bootstrap-unified Op 6+8): plugin
#                import for core (core-meta/core-bpm/core-aurabot/page-manager/
#                org-management/platform-admin) and demo
#                (crm-starter/showcase/agent-control-plane/workflow-demo)
#                profiles is now in-process via
#                BuiltinPluginImportService, gated by AURABOOT_DEMO_SEED.
#                test-fixtures is seeded by the Playwright setup project
#                (web-admin/tests/api/setup/03-import-test-fixtures.spec.ts).
#                §7.6/§7.7/§7.8/§7.9 are KEPT — they seed marketplace
#                registry / agent definitions / model displayName, which
#                are not part of the bootstrap invariants and need direct
#                SQL/script access.
#   2026-05-09 — §6 trimmed: test pages, system_overview dashboard, and
#                multi-role users moved into the Playwright setup project
#                (web-admin/tests/api/setup/0[0-2]-*.spec.ts). The setup
#                project runs as the first project in playwright.oss.config.ts
#                so any later Playwright invocation inherits the provisioned
#                state idempotently. §6d (storageState generation) kept
#                because some legacy specs read tests/storage/admin.json
#                directly before the auth project runs.
#
# Default flow:
# 1-2. Stop backend + frontend services
# 3.   Reset database (drop + recreate)
# 4.   Start backend + wait for health check
# 4.5  Bootstrap via /api/bootstrap/setup API (creates admin + System Tenant +
#      Business Tenant + platform_admin/tenant_admin role assignments)
# 5.   Start frontend + wait for ready
# 6.   Generate Playwright storageState (test data prep itself moved to setup project)
# 7.   Verify bootstrap data
# 7.4  Grant platform_admin to default admin user
# 7.5  (removed — plugin import is now in-process; see History above)
# 7.6-7.9. Backfill + marketplace seed + CS Agent seed + AuraBot seed
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
set -o pipefail

# Parse arguments
NO_BOOTSTRAP=0
for arg in "$@"; do
    case "$arg" in
        --no-bootstrap)
            NO_BOOTSTRAP=1
            ;;
        -h|--help)
            HELP_VITE_BASE="http://localhost:${VITE_PORT:-5173}"
            echo "Usage: $0 [--no-bootstrap]"
            echo ""
            echo "  (default)       Reset DB, start services, bootstrap system, import plugins, seed demo data"
            echo "  --no-bootstrap  Reset DB and start services only; system stays uninitialized"
            echo "                  (visit ${HELP_VITE_BASE}/setup to bootstrap via the web wizard)"
            echo ""
            echo "Env vars:"
            echo "  SKIP_SEED=1     Skip Playwright showcase seed (step 8)"
            echo "  AURABOOT_DEMO_SEED=false  Disable demo plugin import (use with SKIP_SEED=1)"
            echo "  SHOWCASE_COMMERCIAL_SEED=auto|required|skip  Control full-CRM commercial seed"
            echo "  SHOWCASE_DEFAULT_DASHBOARD_CODE=crm_overview|crm_dashboard  Override demo default dashboard"
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

# Env-aware psql connection params (memory:feedback_psql_helpers_must_be_env_aware).
# Defaults match the legacy host setup (localhost:5432 / user ghj / db aura_boot).
# Isolated docker stacks must override PG_HOST/PG_PORT/PG_USER/PG_DB before invoking.
PG_HOST="${PG_HOST:-${PGHOST:-localhost}}"
PG_PORT="${PG_PORT:-${PGPORT:-5432}}"
PG_USER="${PG_USER:-${PGUSER:-ghj}}"
PG_DB="${PG_DB:-${PGDATABASE:-aura_boot}}"
PG_PASSWORD_ENV=""
if [ -n "${PG_PASSWORD:-${PGPASSWORD:-}}" ]; then
    PG_PASSWORD_ENV="PGPASSWORD=${PG_PASSWORD:-${PGPASSWORD}}"
fi
psql_run() {
    if [ -n "$PG_PASSWORD_ENV" ]; then
        env "$PG_PASSWORD_ENV" psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_DB" "$@"
    else
        psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_DB" "$@"
    fi
}
WEB_ADMIN_DIR="$PROJECT_ROOT/web-admin"

# Port overrides — when targeting an isolated docker stack, source the per-stack
# .env file or set these inline. Defaults match host-mode (auraboot dev singleton).
#   BE_PORT      backend (default 6443)
#   VITE_PORT    vite dev server (default 5173)
#   BFF_PORT     remix BFF (default 3500)
#   PG_HOST      postgres host (default localhost)
#   PG_PORT      postgres port (default 5432)
#   PG_USER      postgres user (default $USER for host; auraboot for isolated)
#   PG_DB        postgres db   (default aura_boot)
# Example: BE_PORT=6478 VITE_PORT=5208 BFF_PORT=3535 PG_PORT=5467 \
#          PG_USER=auraboot PGPASSWORD=auraboot_dev ./scripts/oss-reset-and-init.sh
export BE_PORT="${BE_PORT:-6443}"
export VITE_PORT="${VITE_PORT:-5173}"
export BFF_PORT="${BFF_PORT:-3500}"
export AURA_BE_BASE="http://localhost:${BE_PORT}"
export AURA_VITE_BASE="http://localhost:${VITE_PORT}"
export AURA_BFF_BASE="http://localhost:${BFF_PORT}"
export PG_HOST="${PG_HOST:-localhost}"
export PG_PORT="${PG_PORT:-5432}"
export PG_USER="${PG_USER:-${USER:-ghj}}"
export PG_DB="${PG_DB:-aura_boot}"
export AURABOOT_DEMO_SEED="${AURABOOT_DEMO_SEED:-true}"
export SHOWCASE_COMMERCIAL_SEED="${SHOWCASE_COMMERCIAL_SEED:-auto}"
export AURA_PSQL_BASE="psql -h ${PG_HOST} -p ${PG_PORT} -U ${PG_USER} -d ${PG_DB}"

BOOTSTRAP_SEED_DEMO_DATA=true
case "$AURABOOT_DEMO_SEED" in
    false|FALSE|False|0|no|NO|No)
        BOOTSTRAP_SEED_DEMO_DATA=false
        ;;
esac

case "$SHOWCASE_COMMERCIAL_SEED" in
    auto|required|skip) ;;
    *)
        echo "SHOWCASE_COMMERCIAL_SEED must be one of: auto, required, skip"
        exit 1
        ;;
esac

# shellcheck source=lib/multi-worktree-guard.sh
source "$SCRIPT_DIR/lib/multi-worktree-guard.sh"
aura_multi_worktree_guard "oss-reset-and-init.sh"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}=== AuraBoot Environment Reset & Initialization ===${NC}"
echo ""

if [ "$NO_BOOTSTRAP" != "1" ] && [ "$BOOTSTRAP_SEED_DEMO_DATA" = "false" ] && [ "${SKIP_SEED:-0}" != "1" ]; then
    echo -e "${RED}AURABOOT_DEMO_SEED=false disables demo plugin import, so Step 8 showcase seed cannot run.${NC}"
    echo "Set SKIP_SEED=1 or enable AURABOOT_DEMO_SEED=true."
    exit 1
fi

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

run_seed_step() {
    local label="$1"
    local log_file="$2"
    shift 2

    mkdir -p "$(dirname "$log_file")"
    echo "   ${label}..."

    if NO_PROXY=localhost "$@" > "$log_file" 2>&1; then
        tail -3 "$log_file" | sed 's/^/     /' || true
        echo -e "${GREEN}   ${label} complete${NC}"
    else
        local status=$?
        echo -e "${RED}   ${label} failed (exit ${status}). Last 80 log lines:${NC}"
        tail -80 "$log_file" || true
        echo "   Full log: $log_file"
        exit "$status"
    fi
}

psql_scalar() {
    psql_run -tAc "$1" | tr -d '[:space:]'
}

command_definition_exists() {
    local command_code="$1"
    [ "$(psql_scalar "select exists(select 1 from ab_command_definition where code = '${command_code}')")" = "t" ]
}

dashboard_definition_exists() {
    local dashboard_code="$1"
    [ "$(psql_scalar "select exists(select 1 from ab_dashboard where code = '${dashboard_code}')")" = "t" ]
}

ensure_dashboard_definition_exists() {
    local dashboard_code="$1"
    if ! dashboard_definition_exists "$dashboard_code"; then
        echo -e "${RED}   Required dashboard '${dashboard_code}' is not imported.${NC}"
        echo "   Set SHOWCASE_DEFAULT_DASHBOARD_CODE to an imported dashboard code or import the matching plugin resources."
        exit 1
    fi
}

select_default_showcase_dashboard() {
    if [ -n "${SHOWCASE_DEFAULT_DASHBOARD_CODE:-}" ]; then
        ensure_dashboard_definition_exists "$SHOWCASE_DEFAULT_DASHBOARD_CODE"
        return
    fi

    if dashboard_definition_exists "crm_dashboard"; then
        export SHOWCASE_DEFAULT_DASHBOARD_CODE="crm_dashboard"
    elif dashboard_definition_exists "crm_overview"; then
        export SHOWCASE_DEFAULT_DASHBOARD_CODE="crm_overview"
    else
        echo -e "${RED}   No CRM dashboard is imported for demo default selection.${NC}"
        echo "   Expected crm_dashboard (full CRM) or crm_overview (OSS crm-starter)."
        exit 1
    fi
}

# Step 0: Preflight — required local services must be running.
# PG / Redis are external dependencies the platform connects to. If absent
# the rest of the script fails inside `set -e` with cryptic JDBC errors.
# Surface a clear actionable message instead.
echo -e "${YELLOW}Step 0: Checking local service prerequisites...${NC}"
if ! pg_isready -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" >/dev/null 2>&1; then
    echo -e "${RED}   PostgreSQL is not reachable at ${PG_HOST}:${PG_PORT} (user=${PG_USER}).${NC}"
    echo "   Start it with one of:"
    echo "     macOS (Homebrew):  brew services start postgresql@16"
    echo "     Linux (systemd):   sudo systemctl start postgresql"
    echo "     Docker:            see docker-compose.yml"
    echo "   Or set PG_HOST / PG_PORT / PG_USER if running on a non-default endpoint."
    exit 1
fi
echo -e "${GREEN}   PostgreSQL ready${NC}"

if ! redis-cli ping >/dev/null 2>&1; then
    echo -e "${RED}   Redis is not reachable at default endpoint (127.0.0.1:6379).${NC}"
    echo "   Start it with one of:"
    echo "     macOS (Homebrew):  brew services start redis"
    echo "     Linux (systemd):   sudo systemctl start redis"
    exit 1
fi
echo -e "${GREEN}   Redis ready${NC}"

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

# This script still drives the canonical /api/bootstrap/setup flow below.
# Keep the in-process BootstrapStartupRunner disabled here so there is only one
# bootstrap authority in this process. Otherwise the runner can import plugins
# while /api/bootstrap/setup is still creating the default tenant roles.
export AURABOOT_BOOTSTRAP_ENABLED=false
if [ "$NO_BOOTSTRAP" = "1" ]; then
    echo "   AURABOOT_BOOTSTRAP_ENABLED=false (--no-bootstrap escape hatch)"
else
    echo "   AURABOOT_BOOTSTRAP_ENABLED=false (/api/bootstrap/setup is script authority)"
    echo "   AURABOOT_DEMO_SEED=${AURABOOT_DEMO_SEED} (bootstrap seedDemoData=${BOOTSTRAP_SEED_DEMO_DATA})"
fi

# Start backend in background as a single long-running process
nohup ./gradlew bootRun > /tmp/aura-backend.log 2>&1 &
BACKEND_PID=$!

echo "   Backend starting (PID: $BACKEND_PID)..."
echo "   Waiting for backend to be ready..."

# Wait for backend to be ready (max 120 seconds)
MAX_WAIT=120
WAITED=0
while [ $WAITED -lt $MAX_WAIT ]; do
    HTTP_CODE=$(NO_PROXY=localhost curl -s -o /dev/null -w "%{http_code}" ${AURA_BE_BASE}/actuator/health 2>/dev/null || echo "000")

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
    echo "   System will remain uninitialized. Visit ${AURA_VITE_BASE}/setup to bootstrap."
else
    echo -e "${YELLOW}Step 4.5: Bootstrapping system...${NC}"

    BOOTSTRAP_STATUS=$(NO_PROXY=localhost curl -s ${AURA_BE_BASE}/api/bootstrap/status 2>/dev/null || echo '{}')
    IS_INITIALIZED=$(echo "$BOOTSTRAP_STATUS" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('initialized',False))" 2>/dev/null || echo "False")

    if [ "$IS_INITIALIZED" = "True" ]; then
        echo -e "${GREEN}   System already initialized, skipping bootstrap${NC}"
    else
        echo "   Calling /api/bootstrap/setup..."
        BOOTSTRAP_RESP=$(NO_PROXY=localhost curl -s -w "\n%{http_code}" -X POST ${AURA_BE_BASE}/api/bootstrap/setup \
            -H "Content-Type: application/json" \
            -d "{
                \"companyName\": \"AuraBoot Dev\",
                \"adminEmail\": \"admin@example.com\",
                \"adminPassword\": \"Test2026x\",
                \"adminDisplayName\": \"Admin User\",
                \"systemMode\": \"single\",
                \"seedDemoData\": ${BOOTSTRAP_SEED_DEMO_DATA}
            }" 2>/dev/null)

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
    LOGIN_RESP=$(NO_PROXY=localhost curl -s -X POST ${AURA_BE_BASE}/api/auth/login \
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
        SPACES_RESP=$(NO_PROXY=localhost curl -s ${AURA_BE_BASE}/api/tenant-selection/my-spaces \
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
        SELECT_RESP=$(NO_PROXY=localhost curl -s -X POST ${AURA_BE_BASE}/api/tenant-selection/process \
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

# Step 5.0: Preflight — ensure pnpm workspace dependencies are installed.
# Fresh-clone scenario: web-admin/node_modules absent → react-router / tsx
# binaries unresolved → dev:web/dev:bff fail with "command not found".
# Use --frozen-lockfile so lockfile drift surfaces here instead of silently
# rewriting the lockfile and producing a corrupt install.
if [ ! -d "$WEB_ADMIN_DIR/node_modules" ] || [ ! -d "$PROJECT_ROOT/node_modules" ]; then
    echo "   web-admin/node_modules or root node_modules missing — installing pnpm workspace deps..."
    if ! (cd "$PROJECT_ROOT" && pnpm install --frozen-lockfile); then
        echo -e "${RED}   pnpm install --frozen-lockfile failed.${NC}"
        echo "   If lockfile is out of sync with package.json, run 'pnpm install' manually,"
        echo "   commit the updated lockfile, and rerun this script."
        exit 1
    fi
fi

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
    FRONTEND_HTTP_CODE=$(NO_PROXY=localhost curl -s -o /dev/null -w "%{http_code}" ${AURA_VITE_BASE} 2>/dev/null || echo "000")
    BFF_HTTP_CODE=$(NO_PROXY=localhost curl -s -o /dev/null -w "%{http_code}" ${AURA_BFF_BASE}/health 2>/dev/null || echo "000")

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
    # Step 6: Generate Playwright storageState (test pages / dashboard /
    # multi-role users moved to the Playwright setup project — see
    # web-admin/tests/api/setup/0[01-2]-*.spec.ts and the documentation
    # at docs/guides/r2-isolated-stack-sop.md). The setup project runs
    # as the first project in playwright.oss.config.ts so any test
    # invocation (including the showcase seed below) inherits the
    # provisioned data idempotently. Trimmed in commit on 2026-05-09 —
    # see HISTORY block at the top of this file.
    echo -e "${YELLOW}Step 6: Generating Playwright storageState (test data prep is now in tests/api/setup/0[0-2]-*.spec.ts)...${NC}"

    cd "$WEB_ADMIN_DIR"
    mkdir -p tests/storage

    # Login via BFF to get session cookie, then save storage state
    BFF_LOGIN_RESP=$(NO_PROXY=localhost curl -s -D - -o /dev/null -X POST ${AURA_VITE_BASE}/login \
        -H "Content-Type: application/x-www-form-urlencoded" \
        -d "email=admin@example.com&password=Test2026x&remember=on&redirectTo=/" 2>/dev/null)
    SESSION_COOKIE=$(echo "$BFF_LOGIN_RESP" | grep -i "set-cookie.*__session" | sed 's/.*__session=\([^;]*\).*/\1/' | head -1)

    if [ -n "$SESSION_COOKIE" ]; then
        cat > tests/storage/admin.json << STORAGEJSON
{
  "cookies": [
    {"name":"__session","value":"$SESSION_COOKIE","domain":"localhost","path":"/","httpOnly":true,"secure":false,"sameSite":"Lax","expires":$(python3 -c "import time; print(int(time.time())+604800)")},
    {"name":"__session","value":"$SESSION_COOKIE","domain":"127.0.0.1","path":"/","httpOnly":true,"secure":false,"sameSite":"Lax","expires":$(python3 -c "import time; print(int(time.time())+604800)")}
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
    BOOTSTRAP_CHECK=$(psql_run -P pager=off -t -A -F',' -c "
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

    # Step 7.4: Grant platform_admin to default admin user
    #
    # /api/bootstrap/setup grants tenant_admin automatically but NOT platform_admin.
    # platform_admin gates /api/admin/infrastructure/** and /api/admin/cloud-config/**.
    # We JOIN via ab_tenant_member so the grant is scoped to each tenant the admin belongs to.
    #
    # ID generation:  floor(epoch_µs + random_µs) — no sequence on ab_user_role.id (plain BIGINT PK).
    # PID generation: left(md5('pa_ur_' || member_id), 26) — deterministic, exactly 26 chars,
    #                 safe to re-run (NOT EXISTS guard prevents duplicate).
    echo -e "${YELLOW}Step 7.4: Granting platform_admin role to default admin user...${NC}"
    psql_run -v ON_ERROR_STOP=1 -P pager=off <<'GRANT_SQL'
INSERT INTO ab_user_role (
    id, pid, member_id, tenant_id, role_id,
    status, assign_type, deleted_flag,
    created_at, updated_at
)
SELECT
    -- id: epoch microseconds + random offset → unique BIGINT (no sequence on this table)
    floor(extract(epoch from clock_timestamp()) * 1000000 + random() * 999999)::bigint,
    -- pid: deterministic 26-char md5 prefix keyed on member_id (safe for re-runs)
    left(md5('pa_ur_' || tm.id::text), 26),
    tm.id,
    r.tenant_id,
    r.id,
    'active', 'direct', FALSE,
    NOW(), NOW()
FROM ab_user u
JOIN ab_tenant_member tm
    ON tm.user_id = u.id
    AND (tm.deleted_flag = FALSE OR tm.deleted_flag IS NULL)
JOIN ab_role r
    ON r.code = 'platform_admin'
    AND r.tenant_id = tm.tenant_id
    AND (r.deleted_flag = FALSE OR r.deleted_flag IS NULL)
WHERE u.email = 'admin@example.com'
  AND (u.deleted_flag = FALSE OR u.deleted_flag IS NULL)
  AND NOT EXISTS (
      SELECT 1 FROM ab_user_role ur2
      WHERE ur2.member_id = tm.id
        AND ur2.role_id = r.id
        AND (ur2.deleted_flag = FALSE OR ur2.deleted_flag IS NULL)
  );
GRANT_SQL
    echo -e "${GREEN}   platform_admin granted to admin user${NC}"

    # Step 7.5: (REMOVED 2026-05-09 — Phase 3 of bootstrap-unified)
    #
    # Plugin import for the core+demo profiles is now done in-process by
    # BuiltinPluginImportService (core-meta, core-bpm, core-aurabot,
    # page-manager, org-management, platform-admin, crm-starter, showcase,
    # agent-control-plane, workflow-demo). In this script the
    # /api/bootstrap/setup path invokes BootstrapEngineService step 9;
    # startup-runner imports are disabled above to keep one bootstrap
    # authority. Demo profile is controlled by the seedDemoData request field,
    # derived from AURABOOT_DEMO_SEED.
    #
    # The internal-only `test-fixtures` plugin is NOT imported via the platform.
    # It is seeded by the Playwright setup project (web-admin/tests/api/setup/
    # 03-import-test-fixtures.spec.ts), gated by AURA_ENV=test.

    # Step 7.6: Backfill model displayName for AuraBot Chinese search
    echo -e "${YELLOW}Step 7.6: Backfilling model displayNames...${NC}"
    psql_run -f "$SCRIPT_DIR/backfill-model-displayname.sql" -P pager=off 2>&1 | tail -1
    echo -e "${GREEN}   DisplayName backfill complete${NC}"

    # Step 7.7: Seed marketplace registry
    echo -e "${YELLOW}Step 7.7: Seeding marketplace...${NC}"
    "$SCRIPT_DIR/seed-marketplace.sh" 2>&1 | tail -1
    echo -e "${GREEN}   Marketplace seed complete${NC}"

    # Step 7.8: Seed CS Agent definition
    echo -e "${YELLOW}Step 7.8: Seeding CS Agent definition...${NC}"
    CS_AGENT_LOG="/tmp/aura-seed-cs-agent.log"
    psql_run -f "$SCRIPT_DIR/seed-cs-agent.sql" -P pager=off > "$CS_AGENT_LOG" 2>&1
    grep -E "NOTICE|ERROR" "$CS_AGENT_LOG" | tail -5 || tail -5 "$CS_AGENT_LOG" || true
    echo -e "${GREEN}   CS Agent seed complete${NC}"

    # Step 7.9: Seed AuraBot agent definition (GAP-296)
    # Per-tenant aurabot agent_definition row so AuraBotAgentResolver hot-paths
    # never fall back to the inline LAZY_SEED_AURABOT branch.
    echo -e "${YELLOW}Step 7.9: Seeding AuraBot agent definition...${NC}"
    AURABOT_AGENT_LOG="/tmp/aura-seed-aurabot-agent.log"
    psql_run -f "$SCRIPT_DIR/seed-aurabot-agent.sql" -P pager=off > "$AURABOT_AGENT_LOG" 2>&1
    grep -E "NOTICE|ERROR" "$AURABOT_AGENT_LOG" | tail -5 || tail -5 "$AURABOT_AGENT_LOG" || true
    echo -e "${GREEN}   AuraBot agent seed complete${NC}"

    # Step 8: Seed showcase demo data (optional — skip with SKIP_SEED=1)
    if [ "${SKIP_SEED:-0}" != "1" ]; then
        echo -e "${YELLOW}Step 8: Seeding showcase demo data...${NC}"
        cd "$WEB_ADMIN_DIR"

        SEED_CONFIG="playwright.seed.config.ts"
        SEED_LOG_DIR="$WEB_ADMIN_DIR/test-results/seed/reset-and-init"

        run_seed_step "Core seed (org + CRM accounts/leads/opportunities)" "$SEED_LOG_DIR/seed-showcase-data.log" \
            npx playwright test seed-showcase-data --config="$SEED_CONFIG" --reporter=line

        run_seed_step "Extended seed (bulk accounts/leads/activities)" "$SEED_LOG_DIR/seed-showcase-extended.log" \
            npx playwright test seed-showcase-extended --config="$SEED_CONFIG" --reporter=line

        run_seed_step "Workflow seed (BPMN/automation/webhook)" "$SEED_LOG_DIR/seed-showcase-workflow.log" \
            npx playwright test seed-showcase-workflow --config="$SEED_CONFIG" --reporter=line

        run_seed_step "AI seed (agents/knowledge base)" "$SEED_LOG_DIR/seed-showcase-ai.log" \
            npx playwright test seed-showcase-ai --config="$SEED_CONFIG" --reporter=line

        run_seed_step "Arsenal seed (all-fields + dashboard + report + BPMN + automation)" "$SEED_LOG_DIR/seed-showcase-arsenal.log" \
            npx playwright test seed-showcase-arsenal --config="$SEED_CONFIG" --reporter=line

        run_seed_step "Supplement seed (more contacts + leads + activities)" "$SEED_LOG_DIR/seed-showcase-supplement.log" \
            npx playwright test seed-showcase-supplement --config="$SEED_CONFIG" --reporter=line

        case "$SHOWCASE_COMMERCIAL_SEED" in
            skip)
                echo -e "${YELLOW}   Commercial seed skipped (SHOWCASE_COMMERCIAL_SEED=skip)${NC}"
                ;;
            auto)
                if command_definition_exists "crm:create_quote" && command_definition_exists "crm:create_complaint"; then
                    run_seed_step "Commercial seed (quotes/complaints/IM/email/opp-contacts)" "$SEED_LOG_DIR/seed-showcase-commercial.log" \
                        npx playwright test seed-showcase-commercial --config="$SEED_CONFIG" --reporter=line
                else
                    echo -e "${YELLOW}   Commercial seed skipped: full CRM quote/complaint commands are not imported.${NC}"
                    echo "     OSS crm-starter supports base CRM seed; full commercial seed requires the enterprise CRM plugin."
                fi
                ;;
            required)
                if ! command_definition_exists "crm:create_quote" || ! command_definition_exists "crm:create_complaint"; then
                    echo -e "${RED}   Commercial seed required but full CRM quote/complaint commands are not imported.${NC}"
                    echo "   Import the enterprise CRM plugin resources, or set SHOWCASE_COMMERCIAL_SEED=auto/skip."
                    exit 1
                fi
                run_seed_step "Commercial seed (quotes/complaints/IM/email/opp-contacts)" "$SEED_LOG_DIR/seed-showcase-commercial.log" \
                    npx playwright test seed-showcase-commercial --config="$SEED_CONFIG" --reporter=line
                ;;
        esac

        run_seed_step "Seed invariants (CRM + arsenal)" "$SEED_LOG_DIR/seed-showcase-invariants.log" \
            npx playwright test seed-showcase-invariants --config="$SEED_CONFIG" --reporter=line

        select_default_showcase_dashboard
        echo "   Demo default dashboard target: ${SHOWCASE_DEFAULT_DASHBOARD_CODE}"
        run_seed_step "CRM dashboard demo default (${SHOWCASE_DEFAULT_DASHBOARD_CODE})" "$SEED_LOG_DIR/seed-showcase-dashboard-default.log" \
            npx playwright test seed-showcase-dashboard-default --config="$SEED_CONFIG" --reporter=line

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
    echo "  - Visit ${AURA_VITE_BASE}/setup in your browser"
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
echo "  - Backend: ${AURA_BE_BASE}"
echo "  - Frontend: ${AURA_VITE_BASE}"
echo ""
echo -e "${BLUE}Logs:${NC}"
echo "  - Backend: /tmp/aura-backend.log"
echo "  - Frontend web: /tmp/aura-web.log"
echo "  - Frontend bff: /tmp/aura-bff.log"
echo "  - Plugin sync: /tmp/aura-sync-plugins.log"
echo ""
