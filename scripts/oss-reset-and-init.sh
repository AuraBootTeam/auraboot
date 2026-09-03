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
#                SHOWCASE_DEFAULT_DASHBOARD_CODE or the official CRM dashboard.
#   2026-05-10 — §8 seed is now fail-fast: Playwright seed output is written to
#                per-step logs, failures print the tail and stop the script, and
#                final invariants verify CRM/showcase/arsenal/default dashboard.
#   2026-05-17 — §7.5 restored as explicit profile-based plugin import.
#                /api/bootstrap/setup is now minimal system bootstrap only;
#                core/demo/e2e plugin selection is owned by scripts/import-plugins.sh.
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
# 7.4  Verify platform_admin bootstrap invariant
# 7.5  Import plugins via scripts/import-plugins.sh profile
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
            echo "  PLUGIN_IMPORT_PROFILE=core|demo|e2e  Override plugin import profile"
            echo "  AURA_RESET_ALLOW_TARGETS=\"<pg_db>,<be_port>\"  REQUIRED allow-list: reset only proceeds when both the target PG_DB and BE_PORT appear in it (\"@any\" overrides)"
            echo "  AURABOOT_DEMO_SEED=false  Backward-compatible alias for PLUGIN_IMPORT_PROFILE=core"
            echo "  SHOWCASE_COMMERCIAL_SEED=auto|required|skip  Control full-CRM commercial seed"
            echo "  SHOWCASE_DEFAULT_DASHBOARD_CODE=crm_dashboard  Override demo default dashboard"
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
# dev.sh runtime envs (POSTGRES_DB / SERVER_PORT) are honoured as fallbacks so a
# slot-scoped run can never silently target the shared aura_boot database.
PG_DB="${PG_DB:-${PGDATABASE:-${POSTGRES_DB:-aura_boot}}}"
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
export BE_PORT="${BE_PORT:-${SERVER_PORT:-6443}}"
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
export PLUGIN_IMPORT_PROFILE="${PLUGIN_IMPORT_PROFILE:-}"
export SHOWCASE_COMMERCIAL_SEED="${SHOWCASE_COMMERCIAL_SEED:-auto}"
export AURA_PSQL_BASE="psql -h ${PG_HOST} -p ${PG_PORT} -U ${PG_USER} -d ${PG_DB}"

# ── Target designation gate (fail-closed) ────────────────────────────────────
# Reset is destructive: it stops whatever process owns the target service
# ports and drops the target database. On a shared host several isolated slot
# stacks coexist, so a reset must NAME the environment it is allowed to touch.
# AURA_RESET_ALLOW_TARGETS is a comma/space separated token list; this
# invocation proceeds only when BOTH the target database name (PG_DB) and the
# target backend port (BE_PORT) appear in the list. The literal "@any" restores
# the legacy untargeted behavior and is NOT recommended on shared hosts.
aura_reset_allow_targets="${AURA_RESET_ALLOW_TARGETS:-}"
if [ -z "$(printf '%s' "$aura_reset_allow_targets" | tr -d '[:space:]')" ]; then
    echo "REFUSED: untargeted reset is not allowed (shared-host safety)." >&2
    echo "Export AURA_RESET_ALLOW_TARGETS naming the environment you intend to reset," >&2
    echo "e.g. AURA_RESET_ALLOW_TARGETS=\"${PG_DB},${BE_PORT}\" — both PG_DB and BE_PORT" >&2
    echo "must appear in the list. Use \"@any\" to override at your own risk." >&2
    exit 1
fi
if [ "$aura_reset_allow_targets" != "@any" ]; then
    aura_reset_allow_db=0
    aura_reset_allow_be=0
    for aura_reset_token in $(printf '%s' "$aura_reset_allow_targets" | tr ', ' '  '); do
        [ "$aura_reset_token" = "$PG_DB" ] && aura_reset_allow_db=1
        [ "$aura_reset_token" = "$BE_PORT" ] && aura_reset_allow_be=1
    done
    if [ "$aura_reset_allow_db" = 0 ] || [ "$aura_reset_allow_be" = 0 ]; then
        echo "REFUSED: reset target (PG_DB=${PG_DB}, BE_PORT=${BE_PORT}) is not designated" >&2
        echo "in AURA_RESET_ALLOW_TARGETS=\"${aura_reset_allow_targets}\". Add both tokens (or point" >&2
        echo "the invocation at a designated environment) before resetting." >&2
        exit 1
    fi
    echo "Target designation OK: PG_DB=${PG_DB}, BE_PORT=${BE_PORT} (allow-list matched)"
fi
# ── end target designation gate ──────────────────────────────────────────────

if [ -z "$PLUGIN_IMPORT_PROFILE" ]; then
    case "$AURABOOT_DEMO_SEED" in
        false|FALSE|False|0|no|NO|No) PLUGIN_IMPORT_PROFILE="core" ;;
        *) PLUGIN_IMPORT_PROFILE="demo" ;;
    esac
fi

case "$PLUGIN_IMPORT_PROFILE" in
    core|demo|e2e|pcba-agent) ;;
    default)
        echo "PLUGIN_IMPORT_PROFILE=default is deprecated; use core, demo, e2e, or pcba-agent."
        exit 2
        ;;
    *)
        echo "PLUGIN_IMPORT_PROFILE must be one of: core, demo, e2e, pcba-agent"
        exit 2
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

# shellcheck source=lib/runtime-process-owner.sh
source "$SCRIPT_DIR/lib/runtime-process-owner.sh"

RESET_RUNTIME_LABEL_RAW="${AURA_RESET_RUNTIME_LABEL:-${AURA_RUNTIME_NAME:-}}"
RESET_RUNTIME_LABEL="$(aura_reset_sanitize_label "$RESET_RUNTIME_LABEL_RAW")"
if [ -n "$RESET_RUNTIME_LABEL_RAW" ] && [ -z "$RESET_RUNTIME_LABEL" ]; then
    echo "Invalid AURA_RESET_RUNTIME_LABEL=$RESET_RUNTIME_LABEL_RAW" >&2
    exit 1
fi
if [ -n "$RESET_RUNTIME_LABEL" ]; then
    BACKEND_LOG="${AURA_RESET_BACKEND_LOG:-/tmp/aura-${RESET_RUNTIME_LABEL}-backend.log}"
    FRONTEND_LOG="${AURA_RESET_FRONTEND_LOG:-/tmp/aura-${RESET_RUNTIME_LABEL}-web.log}"
    BFF_LOG="${AURA_RESET_BFF_LOG:-/tmp/aura-${RESET_RUNTIME_LABEL}-bff.log}"
    SYNC_PLUGINS_LOG="${AURA_RESET_SYNC_PLUGINS_LOG:-/tmp/aura-${RESET_RUNTIME_LABEL}-sync-plugins.log}"
else
    BACKEND_LOG="${AURA_RESET_BACKEND_LOG:-/tmp/aura-backend.log}"
    FRONTEND_LOG="${AURA_RESET_FRONTEND_LOG:-/tmp/aura-web.log}"
    BFF_LOG="${AURA_RESET_BFF_LOG:-/tmp/aura-bff.log}"
    SYNC_PLUGINS_LOG="${AURA_RESET_SYNC_PLUGINS_LOG:-/tmp/aura-sync-plugins.log}"
fi
RESET_STOP_PROCESSES="${AURA_RESET_STOP_PROCESSES:-1}"

backend_owner_command_token() {
    local record_file token
    record_file="$(aura_reset_service_record_file backend)"
    if [ ! -f "$record_file" ]; then
        printf '%s\n' "java -jar"
        return
    fi
    token="$(aura_reset_record_value "$record_file" command_token)"
    case "$token" in
        bootRun|"java -jar") printf '%s\n' "$token" ;;
        *)
            aura_reset_owner_error "backend owner uses an unsupported command token: $token"
            return 1
            ;;
    esac
}

resolve_boot_jar() {
    local jar
    if [ -n "${AURA_BOOT_JAR:-}" ]; then
        jar="$AURA_BOOT_JAR"
    else
        jar="$(find "$PLATFORM_DIR/build/libs" -maxdepth 1 -type f -name '*-boot.jar' -print 2>/dev/null \
            | while IFS= read -r candidate; do
                printf '%s\t%s\n' "$(stat -f '%m' "$candidate" 2>/dev/null || stat -c '%Y' "$candidate")" "$candidate"
              done \
            | sort -rn \
            | awk -F'\t' 'NR == 1 { print $2; exit }')"
    fi
    [ -n "$jar" ] && [ -f "$jar" ] || {
        echo "No executable boot jar found under $PLATFORM_DIR/build/libs" >&2
        return 1
    }
    case "$jar" in
        /*) printf '%s\n' "$jar" ;;
        *) printf '%s/%s\n' "$PLATFORM_DIR" "$jar" ;;
    esac
}

aura_reset_owner_init \
    "oss" "$PROJECT_ROOT" "$RESET_RUNTIME_LABEL_RAW" \
    "$PG_HOST" "$PG_PORT" "$PG_DB" "$BE_PORT" "$VITE_PORT" "$BFF_PORT"
aura_reset_acquire_locks

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}=== AuraBoot Environment Reset & Initialization ===${NC}"
echo ""
echo "Runtime owner: $AURA_RESET_OWNER_RUNTIME_ID"
echo "Owner state:   $AURA_RESET_OWNER_STATE_DIR"
echo ""

if [ "$NO_BOOTSTRAP" != "1" ] && [ "$PLUGIN_IMPORT_PROFILE" = "core" ] && [ "${SKIP_SEED:-0}" != "1" ]; then
    echo -e "${RED}PLUGIN_IMPORT_PROFILE=core does not import demo plugins, so Step 8 showcase seed cannot run.${NC}"
    echo "Set SKIP_SEED=1 or use PLUGIN_IMPORT_PROFILE=demo/e2e."
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

    if ! dashboard_definition_exists "crm_dashboard"; then
        echo -e "${RED}   No CRM dashboard is imported for demo default selection.${NC}"
        echo "   Expected crm_dashboard from the official CRM plugin."
        exit 1
    fi
    export SHOWCASE_DEFAULT_DASHBOARD_CODE="crm_dashboard"
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
if [ "$RESET_STOP_PROCESSES" = "0" ]; then
    echo -e "${YELLOW}   Ownership-scoped backend stop disabled; the target port must be free${NC}"
    aura_reset_assert_service_absent "backend" "$BE_PORT"
else
    aura_reset_stop_service "backend" "$BE_PORT" "$PLATFORM_DIR" "$(backend_owner_command_token)"
    echo -e "${GREEN}   Owned backend service stopped${NC}"
fi

# Step 2: Stop BFF server if running
echo -e "${YELLOW}Step 2: Stopping BFF server...${NC}"
if [ "$RESET_STOP_PROCESSES" = "0" ]; then
    echo -e "${YELLOW}   Ownership-scoped frontend/BFF stop disabled; target ports must be free${NC}"
    aura_reset_assert_service_absent "web" "$VITE_PORT"
    aura_reset_assert_service_absent "bff" "$BFF_PORT"
else
    aura_reset_stop_service "web" "$VITE_PORT" "$WEB_ADMIN_DIR" "pnpm dev:web"
    aura_reset_stop_service "bff" "$BFF_PORT" "$WEB_ADMIN_DIR" "pnpm dev:bff"
    echo -e "${GREEN}   Owned frontend/BFF dev processes stopped${NC}"
fi

# Step 3: Reset database
echo -e "${YELLOW}Step 3: Resetting database...${NC}"
echo "y" | "$SCRIPT_DIR/reset-db.sh"
echo -e "${GREEN}   Database reset complete${NC}"

# Step 4: Start backend service
echo -e "${YELLOW}Step 4: Starting backend service...${NC}"
cd "$PLATFORM_DIR"

if [ "$NO_BOOTSTRAP" = "1" ]; then
    echo "   bootstrap setup disabled (--no-bootstrap escape hatch)"
else
    echo "   /api/bootstrap/setup is script authority"
    echo "   PLUGIN_IMPORT_PROFILE=${PLUGIN_IMPORT_PROFILE}"
fi

# Start backend in background as a single long-running process
./gradlew --no-daemon :bootJar -x test
BOOT_JAR="$(resolve_boot_jar)"

# Stage PF4J plugin backend jars before boot. Import-time validation
# (ExtensionValidator S-EXT-HANDLER) checks CommandHandlerRegistry, which is
# populated at ApplicationReadyEvent from @Extension classes discovered via the
# plugin jar's META-INF/extensions.idx. Config-only directory imports never load
# backend classes, so a plugin whose commands declare `handler:` refs (today:
# crm) fails import with ~50 unregistered-handler errors unless its jar was
# loaded at startup. Build it from source (cached, up-to-date after first run)
# and stage it into the boot-relative plugins dir (aura.plugins.dir defaults to
# "plugins" resolved from the platform cwd).
mkdir -p "$PLATFORM_DIR/plugins"
stage_plugin_jar() {
    local plugin_name="$1"
    local plugin_backend="$PROJECT_ROOT/plugins/${plugin_name}/backend"
    [ -d "$plugin_backend" ] || return 0
    local staged="no"
    local existing_jar
    existing_jar="$(ls "$plugin_backend/build/libs/${plugin_name}-plugin-"*.jar 2>/dev/null | sort | tail -1 || true)"
    if [ -z "$existing_jar" ]; then
        echo "   Building ${plugin_name} PF4J plugin jar (first run)..."
        local plugin_api_jar="$PLATFORM_DIR/platform-plugin-api/build/libs/platform-plugin-api-1.0.0-SNAPSHOT.jar"
        local plugin_api_args=""
        [ -f "$plugin_api_jar" ] && plugin_api_args="-PplatformPluginApiJar=$plugin_api_jar"
        # shellcheck disable=SC2086
        (cd "$PROJECT_ROOT" && ./gradlew --project-dir "plugins/${plugin_name}/backend" jar $plugin_api_args --console=plain > /dev/null) || {
            echo -e "${RED}   ${plugin_name} plugin jar build failed; its handler-backed commands will fail import validation${NC}" >&2
            return 1
        }
        existing_jar="$(ls "$plugin_backend/build/libs/${plugin_name}-plugin-"*.jar 2>/dev/null | sort | tail -1 || true)"
    fi
    if [ -n "$existing_jar" ]; then
        if ! cmp -s "$existing_jar" "$PLATFORM_DIR/plugins/$(basename "$existing_jar")" 2>/dev/null; then
            cp -f "$existing_jar" "$PLATFORM_DIR/plugins/"
        fi
        echo -e "${GREEN}   Staged PF4J plugin jar: $(basename "$existing_jar")${NC}"
        staged="yes"
    fi
    [ "$staged" = "yes" ]
}
stage_plugin_jar "crm"

aura_reset_assert_port_available "backend" "$BE_PORT"
BACKEND_PID="$(aura_reset_spawn_detached "$PLATFORM_DIR" "$BACKEND_LOG" env \
    SERVER_PORT="$BE_PORT" \
    DATABASE_URL="jdbc:postgresql://${PG_HOST}:${PG_PORT}/${PG_DB}?charSet=UTF8" \
    java -jar "$BOOT_JAR")"
aura_reset_register_process "backend" "$BACKEND_PID" "$BE_PORT" "$PLATFORM_DIR" "java -jar"

echo "   Backend starting (PID: $BACKEND_PID)..."
echo "   Boot jar: $BOOT_JAR"
echo "   Waiting for backend to be ready..."

# Wait for backend to be ready (max 120 seconds)
MAX_WAIT=120
WAITED=0
while [ $WAITED -lt $MAX_WAIT ]; do
    HTTP_CODE=$(NO_PROXY=localhost curl -s -o /dev/null -w "%{http_code}" ${AURA_BE_BASE}/actuator/health 2>/dev/null || echo "000")

    if [ "$HTTP_CODE" = "200" ]; then
        aura_reset_assert_service_owned \
            "backend" "$BE_PORT" "$PLATFORM_DIR" "java -jar"
        echo -e "${GREEN}   Backend is ready (took ${WAITED}s)${NC}"
        break
    fi

    sleep 3
    WAITED=$((WAITED + 3))
    echo "   Still waiting... (${WAITED}s)"
done

if [ $WAITED -ge $MAX_WAIT ]; then
    echo -e "${RED}   Backend failed to start within ${MAX_WAIT} seconds${NC}"
    echo "   Check logs at $BACKEND_LOG"
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
                \"adminEmail\": \"admin@auraboot.com\",
                \"adminPassword\": \"Test2026x\",
                \"adminDisplayName\": \"Admin User\",
                \"systemMode\": \"single\"
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
        -d '{"email":"admin@auraboot.com","password":"Test2026x"}' 2>/dev/null)

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
pnpm sync-plugins > "$SYNC_PLUGINS_LOG" 2>&1

aura_reset_assert_port_available "web" "$VITE_PORT"
aura_reset_assert_port_available "bff" "$BFF_PORT"
WEB_PID="$(aura_reset_spawn_detached "$WEB_ADMIN_DIR" "$FRONTEND_LOG" pnpm dev:web)"
aura_reset_register_process "web" "$WEB_PID" "$VITE_PORT" "$WEB_ADMIN_DIR" "pnpm dev:web"
# SPRING_BOOT_URL is mandatory in slot mode: bff.server.ts otherwise falls back
# to AURA_BE_BASE (exported above) and finally the :6443 host-mode default,
# which leaves the BFF proxying to a dead port on any non-default BE_PORT.
BFF_PID="$(aura_reset_spawn_detached "$WEB_ADMIN_DIR" "$BFF_LOG" env SPRING_BOOT_URL="$AURA_BE_BASE" pnpm dev:bff)"
aura_reset_register_process "bff" "$BFF_PID" "$BFF_PORT" "$WEB_ADMIN_DIR" "pnpm dev:bff"

echo "   Frontend starting (web PID: $WEB_PID, bff PID: $BFF_PID)..."
echo "   Waiting for frontend and BFF to be ready..."

# Wait for frontend and BFF to be ready
MAX_WAIT_FE=30
WAITED_FE=0
while [ $WAITED_FE -lt $MAX_WAIT_FE ]; do
    FRONTEND_HTTP_CODE=$(NO_PROXY=localhost curl -s -o /dev/null -w "%{http_code}" ${AURA_VITE_BASE} 2>/dev/null || echo "000")
    BFF_HTTP_CODE=$(NO_PROXY=localhost curl -s -o /dev/null -w "%{http_code}" ${AURA_BFF_BASE}/health 2>/dev/null || echo "000")

    if { [ "$FRONTEND_HTTP_CODE" = "200" ] || [ "$FRONTEND_HTTP_CODE" = "302" ] || [ "$FRONTEND_HTTP_CODE" = "304" ]; } && [ "$BFF_HTTP_CODE" = "200" ]; then
        aura_reset_assert_service_owned \
            "web" "$VITE_PORT" "$WEB_ADMIN_DIR" "pnpm dev:web"
        aura_reset_assert_service_owned \
            "bff" "$BFF_PORT" "$WEB_ADMIN_DIR" "pnpm dev:bff"
        echo -e "${GREEN}   Frontend+BFF are ready (took ${WAITED_FE}s)${NC}"
        break
    fi

    sleep 2
    WAITED_FE=$((WAITED_FE + 2))
    echo "   Still waiting... (frontend=${FRONTEND_HTTP_CODE}, bff=${BFF_HTTP_CODE}, ${WAITED_FE}s)"
done

if [ $WAITED_FE -ge $MAX_WAIT_FE ]; then
    echo -e "${RED}   Frontend/BFF failed to start within ${MAX_WAIT_FE} seconds${NC}"
    echo "   Check logs at $FRONTEND_LOG and $BFF_LOG"
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

    # Login via the app form (field is `identifier` — `email` is not the form
    # contract) to get the BFF session cookie, then save the storage state.
    # Every command is errexit-guarded: a cold Vite SSR hiccup must degrade to
    # the warning branch, never kill the script silently between steps.
    BFF_LOGIN_RESP=$(NO_PROXY=localhost curl -s -D - -o /dev/null -X POST ${AURA_VITE_BASE}/login \
        -H "Content-Type: application/x-www-form-urlencoded" \
        -d "identifier=admin@auraboot.com&password=Test2026x&remember=on&redirectTo=/" 2>/dev/null) || BFF_LOGIN_RESP=""
    SESSION_COOKIE=$(echo "$BFF_LOGIN_RESP" | grep -i "set-cookie.*__session" | sed 's/.*__session=\([^;]*\).*/\1/' | head -1 || true)
    STORAGE_EXPIRY=$(( $(date +%s) + 604800 ))

    if [ -n "$SESSION_COOKIE" ]; then
        cat > tests/storage/admin.json << STORAGEJSON
{
  "cookies": [
    {"name":"__session","value":"$SESSION_COOKIE","domain":"localhost","path":"/","httpOnly":true,"secure":false,"sameSite":"Lax","expires":$STORAGE_EXPIRY},
    {"name":"__session","value":"$SESSION_COOKIE","domain":"127.0.0.1","path":"/","httpOnly":true,"secure":false,"sameSite":"Lax","expires":$STORAGE_EXPIRY}
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
    # psql_run targets PG_HOST/PG_PORT/PG_USER/PG_DB — the same coordinates the
    # reset itself used. (POSTGRES_* names were dead here and pointed readers at
    # the legacy aura_boot default.)
    BOOTSTRAP_CHECK=$(psql_run -P pager=off -t -A -F',' -c "
SELECT
  (SELECT COUNT(*) FROM ab_user WHERE email='admin@auraboot.com' AND (ab_user.deleted_flag=FALSE OR ab_user.deleted_flag IS NULL)) AS admin_users,
  (SELECT COUNT(*) FROM ab_tenant WHERE (ab_tenant.deleted_flag=FALSE OR ab_tenant.deleted_flag IS NULL)) AS tenants,
  (SELECT COUNT(*) FROM ab_tenant_member tm JOIN ab_user u ON u.id=tm.user_id
    WHERE u.email='admin@auraboot.com' AND (tm.deleted_flag=FALSE OR tm.deleted_flag IS NULL)) AS admin_memberships;
") || BOOTSTRAP_CHECK=""
    IFS=',' read -r ADMIN_USERS TENANT_COUNT ADMIN_MEMBERSHIPS <<< "$BOOTSTRAP_CHECK"

    echo "   admin users: ${ADMIN_USERS:-ERR}"
    echo "   tenant count: ${TENANT_COUNT:-ERR}"
    echo "   admin memberships: ${ADMIN_MEMBERSHIPS:-ERR}"

    if ! [ "${ADMIN_USERS:-0}" -ge 1 ] || ! [ "${TENANT_COUNT:-0}" -ge 1 ] || ! [ "${ADMIN_MEMBERSHIPS:-0}" -ge 1 ]; then
        echo -e "${RED}   Bootstrap verification failed: user/tenant/member data is incomplete${NC}"
        exit 1
    fi
    echo -e "${GREEN}   Bootstrap verification passed${NC}"

    # Step 7.4: Verify platform_admin invariant created by /api/bootstrap/setup.
    echo -e "${YELLOW}Step 7.4: Verifying System tenant platform_admin grant...${NC}"
    PLATFORM_ADMIN_GRANTS=$(psql_scalar "
SELECT COUNT(*)
FROM ab_user_role ur
JOIN ab_tenant_member tm ON ur.member_id = tm.id
JOIN ab_user u ON tm.user_id = u.id
JOIN ab_role r ON ur.role_id = r.id
JOIN ab_tenant t ON r.tenant_id = t.id
WHERE u.email = 'admin@auraboot.com'
  AND t.name = 'System'
  AND r.code = 'platform_admin'
  AND COALESCE(ur.deleted_flag, false) = false
  AND COALESCE(tm.deleted_flag, false) = false
  AND COALESCE(r.deleted_flag, false) = false;
")
    if [ "${PLATFORM_ADMIN_GRANTS}" -lt 1 ]; then
        echo -e "${RED}   Bootstrap verification failed: admin lacks platform_admin in System tenant${NC}"
        exit 1
    fi
    echo -e "${GREEN}   platform_admin grant verified${NC}"

    # Step 7.5: Import plugins via explicit profile. Bootstrap setup does not
    # import core/demo plugins; scripts/import-plugins.sh owns retry and latest
    # import-history success verification.
    echo -e "${YELLOW}Step 7.5: Importing plugins (profile=${PLUGIN_IMPORT_PROFILE})...${NC}"
    "$SCRIPT_DIR/import-plugins.sh" \
        --profile="$PLUGIN_IMPORT_PROFILE" \
        --edition=oss \
        --backend-url="$AURA_BE_BASE" \
        --plugin-root="$PROJECT_ROOT/plugins"
    echo -e "${GREEN}   Plugin import complete${NC}"

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

        seed_phases=(data extended workflow ai arsenal supplement)

        case "$SHOWCASE_COMMERCIAL_SEED" in
            skip)
                echo -e "${YELLOW}   Commercial seed skipped (SHOWCASE_COMMERCIAL_SEED=skip)${NC}"
                ;;
            auto)
                if command_definition_exists "crm:create_quote" && command_definition_exists "crm:create_complaint"; then
                    seed_phases+=(commercial)
                else
                    echo -e "${YELLOW}   Commercial seed skipped: full CRM quote/complaint commands are not imported.${NC}"
                    echo "     The optional showcase quote seed requires Sales quote commands outside the CRM core."
                fi
                ;;
            required)
                if ! command_definition_exists "crm:create_quote" || ! command_definition_exists "crm:create_complaint"; then
                    echo -e "${RED}   Commercial seed required but full CRM quote/complaint commands are not imported.${NC}"
                    echo "   Import a Sales quote extension, or set SHOWCASE_COMMERCIAL_SEED=auto/skip."
                    exit 1
                fi
                seed_phases+=(commercial)
                ;;
        esac

        run_seed_step "Showcase seed sequence (${seed_phases[*]})" "$SEED_LOG_DIR/showcase-seed-sequence.log" \
            node scripts/run-showcase-seed-sequence.mjs --config="$SEED_CONFIG" \
                --output-prefix="$SEED_LOG_DIR/showcase" "${seed_phases[@]}"

        # workflow-demo carries its own business data (leave balances + requests + approval
        # tasks). Without a balance row, wd_leave_validation rejects every annual leave
        # request the demo can submit, so this is part of a usable demo, not an extra.
        if command_definition_exists "wd:create_leave_balance"; then
            run_seed_step "Workflow-demo seed (leave balances + requests)" "$SEED_LOG_DIR/workflow-demo-seed.log" \
                node scripts/seed-workflow-demo.mjs --base-url="$AURA_VITE_BASE"
        else
            echo -e "${YELLOW}   Workflow-demo seed skipped: workflow-demo plugin is not imported.${NC}"
        fi

        select_default_showcase_dashboard
        export SHOWCASE_DEFAULT_DASHBOARD_CODE
        echo "   Demo default dashboard target: ${SHOWCASE_DEFAULT_DASHBOARD_CODE}"
        run_seed_step "Showcase seed finalization (dashboard-default + invariants)" "$SEED_LOG_DIR/showcase-seed-finalization.log" \
            node scripts/run-showcase-seed-sequence.mjs --config="$SEED_CONFIG" \
                --output-prefix="$SEED_LOG_DIR/showcase" dashboard-default invariants

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
    echo "  - User: admin@auraboot.com / Test2026x"
    echo "  - Tenant: AuraBoot Dev"
fi
echo ""
echo -e "${YELLOW}Services running:${NC}"
echo "  - Backend: ${AURA_BE_BASE}"
echo "  - Frontend: ${AURA_VITE_BASE}"
echo ""
echo -e "${BLUE}Logs:${NC}"
echo "  - Backend: $BACKEND_LOG"
echo "  - Frontend web: $FRONTEND_LOG"
echo "  - Frontend bff: $BFF_LOG"
echo "  - Plugin sync: $SYNC_PLUGINS_LOG"
echo ""
