#!/bin/bash
#
# Import OSS and enterprise plugins into a running isolated stack.
#
# Usage:
#   scripts/dev/import-isolated-plugins.sh --slug=agent-runtime-e2e --profile=pcba-agent
#   scripts/dev/import-isolated-plugins.sh --slug=enterprise-demo --profile=enterprise-demo --edition=enterprise
#   scripts/dev/import-isolated-plugins.sh --slug=agent-runtime-e2e core-meta core-aurabot

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
STACK_DIR="$PROJECT_ROOT/.aura-stack"

SLUG=""
PROFILE="default"
EDITION="auto"
PLUGINS=()

usage() {
    cat <<USAGE
Usage: $0 [--slug=<name>] [--profile=default|pcba-agent|enterprise-demo] [--edition=auto|oss|enterprise] [plugin...]

Options:
  --slug=<name>      Isolated stack slug. Defaults to current branch slug.
  --profile=<name>   Plugin import profile. pcba-agent imports the minimum
                     OSS + enterprise plugins needed by the PCBA agent E2E.
                     enterprise-demo imports the enterprise reset/demo plugin set.
  --edition=<mode>   Plugin root selection mode:
                       auto       prefer enterprise root when it exists (default)
                       oss        only import from /app/plugins
                       enterprise prefer /app/plugins-enterprise, then /app/plugins

Environment:
  ADMIN_EMAIL        default: admin@auraboot.com
  ADMIN_PASSWORD     default: Test2026x
  IMPORT_ATTEMPTS    default: 2
USAGE
}

normalize_slug() {
    local raw="$1"
    printf '%s' "$raw" \
        | tr '[:upper:]' '[:lower:]' \
        | tr '/_' '--' \
        | sed -E 's/[^a-z0-9-]/-/g; s/-+/-/g; s/^-//; s/-$//' \
        | cut -c1-24
}

for arg in "$@"; do
    case "$arg" in
        --slug=*) SLUG="${arg#--slug=}" ;;
        --profile=*) PROFILE="${arg#--profile=}" ;;
        --edition=*) EDITION="${arg#--edition=}" ;;
        --help|-h) usage; exit 0 ;;
        --*) echo "ERROR: unknown argument: $arg" >&2; usage; exit 2 ;;
        *) PLUGINS+=("$arg") ;;
    esac
done

if [ -z "$SLUG" ]; then
    branch="$(git -C "$PROJECT_ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || echo HEAD)"
    if [ "$branch" = "HEAD" ]; then
        branch="$(basename "$PROJECT_ROOT")"
    fi
    SLUG="$(normalize_slug "$branch")"
fi

case "$EDITION" in
    auto|oss|enterprise) ;;
    *) echo "ERROR: unknown edition: $EDITION" >&2; exit 2 ;;
esac

if [ -z "$SLUG" ]; then
    echo "ERROR: could not derive slug — supply --slug=<name>" >&2
    exit 2
fi

STACK_ENV_FILE="$STACK_DIR/${SLUG}.env"
if [ ! -f "$STACK_ENV_FILE" ]; then
    echo "ERROR: stack env file not found: $STACK_ENV_FILE" >&2
    echo "       start the stack first with scripts/dev/start-isolated.sh --slug=$SLUG" >&2
    exit 2
fi

# shellcheck disable=SC1090
source "$STACK_ENV_FILE"

PROJECT_NAME="${COMPOSE_PROJECT_NAME:-auraboot-$SLUG}"
BACKEND_URL="${BACKEND_URL:-http://localhost:${BE_PORT}}"
ADMIN_EMAIL="${ADMIN_EMAIL:-admin@auraboot.com}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-Test2026x}"
BACKEND_CONTAINER="${PROJECT_NAME}-backend"
IMPORT_ATTEMPTS="${IMPORT_ATTEMPTS:-2}"

DEFAULT_PLUGINS=(
  core-meta
  core-bpm
  core-announcement
  core-aurabot
  page-manager
  platform-admin
  org-management
  agent-control-plane
)

PCBA_AGENT_PLUGINS=(
  core-meta
  core-bpm
  core-announcement
  core-aurabot
  page-manager
  platform-admin
  org-management
  agent-control-plane
  product-catalog
  crm
  inventory
  finance
  sales
  quality
  procurement
  pcba-base
  pcba-crm
  pcba-solution
  pcba-industry
  pcba-procurement
  pcba-sales
  pcba-manufacturing
  pcba-warehouse
  pcba-finance
  pcba-compliance
)

ENTERPRISE_DEMO_PLUGINS=(
  core-meta
  core-bpm
  page-manager
  platform-admin
  platform-admin-ee
  org-management
  core-announcement
  core-aurabot
  agent-control-plane
  marketplace-server
  portal
  connectors
  compliance
  ai-employees
  crm
  showcase
  asset-management
  workflow-demo
  dual-prevention
  product-catalog
  project-management
  pcba-crm
  finance
  inventory
  quality
  annual-plan
  construction-process
  contract-cost
  doc-knowledge
  quarry-industry
  sales
  procurement
  indirect-procurement
  source-to-pay
  production
  maintenance
  logistics
  jiejia-integration
  jiejia-ai-bom-quote
  jiejia-portal
  tax-compliance
  pcba-base
  pcba-industry
  pcba-solution
  pcba-procurement
  pcba-sales
  pcba-manufacturing
  pcba-warehouse
  pcba-compliance
  pcba-finance
  sales-templates
  quarry-solution
  d7-knowledge-wiki
  dev-pipeline
  growth
  jiejia-solution
)

if [ "${#PLUGINS[@]}" -eq 0 ]; then
    case "$PROFILE" in
        default) PLUGINS=("${DEFAULT_PLUGINS[@]}") ;;
        pcba-agent) PLUGINS=("${PCBA_AGENT_PLUGINS[@]}") ;;
        enterprise-demo) PLUGINS=("${ENTERPRISE_DEMO_PLUGINS[@]}") ;;
        *) echo "ERROR: unknown profile: $PROFILE" >&2; exit 2 ;;
    esac
fi

echo "Importing plugins into $PROJECT_NAME ($BACKEND_URL)"
echo "Profile: $PROFILE; edition: $EDITION"
echo "Plugins (${#PLUGINS[@]}): ${PLUGINS[*]}"

health="$(NO_PROXY=localhost curl -s "$BACKEND_URL/actuator/health" 2>/dev/null || echo '{}')"
status="$(printf '%s' "$health" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status',''))" 2>/dev/null || echo "")"
if [ "$status" != "UP" ]; then
    echo "ERROR: backend is not healthy: $health" >&2
    exit 1
fi

login_resp="$(NO_PROXY=localhost curl -s -X POST "$BACKEND_URL/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}")"
jwt="$(printf '%s' "$login_resp" | python3 -c "import sys,json; print(json.load(sys.stdin).get('data',{}).get('jwt',''))" 2>/dev/null || echo "")"
tenant_id="$(printf '%s' "$login_resp" | python3 -c "import sys,json; print(json.load(sys.stdin).get('data',{}).get('tenantId',''))" 2>/dev/null || echo "")"

if [ -z "$jwt" ] || [ "$jwt" = "None" ]; then
    echo "ERROR: admin login failed: $login_resp" >&2
    exit 1
fi

if [ -z "$tenant_id" ] || [ "$tenant_id" = "None" ]; then
    spaces="$(NO_PROXY=localhost curl -s "$BACKEND_URL/api/tenant-selection/my-spaces" \
        -H "Authorization: Bearer $jwt")"
    biz_tenant="$(printf '%s' "$spaces" | python3 -c "
import sys,json
for item in json.load(sys.stdin).get('data',[]):
    if item.get('spaceType') == 'business' and item.get('tenantId'):
        print(item.get('tenantId'))
        break
" 2>/dev/null || echo "")"
    if [ -z "$biz_tenant" ]; then
        echo "ERROR: no business tenant found: $spaces" >&2
        exit 1
    fi
    select_resp="$(NO_PROXY=localhost curl -s -X POST "$BACKEND_URL/api/tenant-selection/process" \
        -H "Authorization: Bearer $jwt" \
        -H "Content-Type: application/json" \
        -d "{\"action\":\"select\",\"tenantId\":$biz_tenant}")"
    selected_jwt="$(printf '%s' "$select_resp" | python3 -c "import sys,json; print(json.load(sys.stdin).get('data',{}).get('jwt',''))" 2>/dev/null || echo "")"
    if [ -n "$selected_jwt" ] && [ "$selected_jwt" != "None" ]; then
        jwt="$selected_jwt"
    else
        echo "ERROR: tenant selection failed: $select_resp" >&2
        exit 1
    fi
fi

container_plugin_path() {
    local plugin="$1"
    case "$EDITION" in
        oss)
            if docker exec "$BACKEND_CONTAINER" sh -lc "[ -f /app/plugins/$plugin/plugin.json ]" >/dev/null 2>&1; then
                printf '/app/plugins/%s\n' "$plugin"
                return 0
            fi
            ;;
        auto|enterprise)
            # Match enterprise reset-and-init.sh precedence: when an enterprise
            # plugin shadows an OSS template name, import the enterprise
            # solution variant.
            if docker exec "$BACKEND_CONTAINER" sh -lc "[ -f /app/plugins-enterprise/$plugin/plugin.json ]" >/dev/null 2>&1; then
                printf '/app/plugins-enterprise/%s\n' "$plugin"
                return 0
            fi
            if docker exec "$BACKEND_CONTAINER" sh -lc "[ -f /app/plugins/$plugin/plugin.json ]" >/dev/null 2>&1; then
                printf '/app/plugins/%s\n' "$plugin"
                return 0
            fi
            ;;
    esac
    return 1
}

import_plugin_once() {
    local path="$1"
    local resp result

    resp="$(NO_PROXY=localhost curl -s -X POST "$BACKEND_URL/api/plugins/import/import-directory-sync" \
        -H "Authorization: Bearer $jwt" \
        -H "Content-Type: application/json" \
        -d "{\"path\":\"$path\",\"conflictStrategy\":\"OVERWRITE\",\"autoPublishModels\":true,\"autoPublishFields\":true,\"autoPublishCommands\":true,\"autoPublishPages\":true}")"
    result="$(printf '%s' "$resp" | python3 -c "
import sys,json
try:
    d=json.load(sys.stdin)
except Exception:
    print('parse-error')
    sys.exit(0)
if d.get('success') is True:
    print('ok')
else:
    print(d.get('errorMessage') or d.get('message') or str(d)[:300])
" 2>/dev/null || echo "$resp")"
    printf '%s\n' "$result"
}

failures=()
for plugin in "${PLUGINS[@]}"; do
    if ! path="$(container_plugin_path "$plugin")"; then
        echo "  FAIL $plugin: plugin.json not found in /app/plugins or /app/plugins-enterprise"
        failures+=("$plugin: missing")
        continue
    fi

    result=""
    attempt=1
    while [ "$attempt" -le "$IMPORT_ATTEMPTS" ]; do
        printf '  Importing %-24s ' "$plugin"
        if [ "$attempt" -gt 1 ]; then
            printf '(retry %s/%s) ' "$attempt" "$IMPORT_ATTEMPTS"
        fi

        result="$(import_plugin_once "$path")"
        if [ "$result" = "ok" ]; then
            echo "OK ($path)"
            break
        fi

        echo "FAIL ($result)"
        if [ "$attempt" -lt "$IMPORT_ATTEMPTS" ]; then
            sleep "$attempt"
        fi
        attempt=$((attempt + 1))
    done

    if [ "$result" != "ok" ]; then
        failures+=("$plugin: $result")
    fi
done

if [ "${#failures[@]}" -gt 0 ]; then
    echo "ERROR: ${#failures[@]} plugin import(s) failed:" >&2
    printf '  - %s\n' "${failures[@]}" >&2
    exit 1
fi

echo "Plugin import complete."
