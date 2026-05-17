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
  --profile=<name>   Plugin import profile from scripts/dev/plugin-import-profiles.json.
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
POSTGRES_CONTAINER="${PROJECT_NAME}-postgres"
DB_USER="${PG_USER:-auraboot}"
DB_NAME="${PG_DB:-aura_boot}"
IMPORT_ATTEMPTS="${IMPORT_ATTEMPTS:-2}"
PROFILE_CONFIG="$PROJECT_ROOT/scripts/dev/plugin-import-profiles.json"

load_profile_plugins() {
    local profile="$1"
    python3 - "$PROFILE_CONFIG" "$profile" <<'PY'
import json
import sys

config_path, profile = sys.argv[1], sys.argv[2]
with open(config_path, encoding='utf-8') as f:
    profiles = json.load(f)

plugins = profiles.get(profile)
if not isinstance(plugins, list) or any(not isinstance(item, str) or not item for item in plugins):
    known = ', '.join(sorted(profiles))
    print(f"ERROR: unknown or invalid profile: {profile}. Known profiles: {known}", file=sys.stderr)
    sys.exit(2)

for plugin in plugins:
    print(plugin)
PY
}

if [ "${#PLUGINS[@]}" -eq 0 ]; then
    while IFS= read -r plugin; do
        PLUGINS+=("$plugin")
    done < <(load_profile_plugins "$PROFILE")
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
    plugin_id = d.get('pluginId') or d.get('data', {}).get('pluginId') or ''
    if plugin_id:
        print('ok\\t' + plugin_id)
    else:
        print('missing pluginId in import response')
else:
    print(d.get('errorMessage') or d.get('message') or str(d)[:300])
" 2>/dev/null || echo "$resp")"
    printf '%s\n' "$result"
}

failures=()
successful_plugin_ids=()
for plugin in "${PLUGINS[@]}"; do
    if ! path="$(container_plugin_path "$plugin")"; then
        echo "  FAIL $plugin: plugin.json not found in /app/plugins or /app/plugins-enterprise"
        failures+=("$plugin: missing")
        continue
    fi

    result=""
    imported=0
    attempt=1
    while [ "$attempt" -le "$IMPORT_ATTEMPTS" ]; do
        printf '  Importing %-24s ' "$plugin"
        if [ "$attempt" -gt 1 ]; then
            printf '(retry %s/%s) ' "$attempt" "$IMPORT_ATTEMPTS"
        fi

        result="$(import_plugin_once "$path")"
        if [[ "$result" == ok$'\t'* ]]; then
            successful_plugin_ids+=("${result#*$'\t'}")
            imported=1
            echo "OK ($path)"
            break
        fi

        echo "FAIL ($result)"
        if [ "$attempt" -lt "$IMPORT_ATTEMPTS" ]; then
            sleep "$attempt"
        fi
        attempt=$((attempt + 1))
    done

    if [ "$imported" -ne 1 ]; then
        failures+=("$plugin: $result")
    fi
done

verify_latest_import_statuses() {
    local plugin_id values_sql failed_rows

    if [ "${#successful_plugin_ids[@]}" -eq 0 ]; then
        echo "ERROR: no successful plugin IDs were captured for latest-status verification" >&2
        return 1
    fi

    values_sql=""
    for plugin_id in "${successful_plugin_ids[@]}"; do
        if [[ ! "$plugin_id" =~ ^[A-Za-z0-9._-]+$ ]]; then
            echo "ERROR: unsafe pluginId returned by import API: $plugin_id" >&2
            return 1
        fi
        values_sql="${values_sql}('$plugin_id'),"
    done
    values_sql="${values_sql%,}"

    failed_rows="$(docker exec "$POSTGRES_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -Atc "
with expected(plugin_id) as (values $values_sql),
latest as (
  select distinct on (plugin_id) plugin_id, status
  from ab_plugin_import_history
  where plugin_id in (select plugin_id from expected)
  order by plugin_id, id desc
)
select e.plugin_id || '|' || coalesce(l.status, 'missing')
from expected e
left join latest l using (plugin_id)
where coalesce(l.status, 'missing') != 'success'
" 2>/dev/null || true)"

    if [ -n "$failed_rows" ]; then
        while IFS='|' read -r plugin_id status; do
            echo "ERROR: $plugin_id latest import status is not success: $status" >&2
        done <<< "$failed_rows"
        return 1
    fi
}

if [ "${#failures[@]}" -gt 0 ]; then
    echo "ERROR: ${#failures[@]} plugin import(s) failed:" >&2
    printf '  - %s\n' "${failures[@]}" >&2
    exit 1
fi

verify_latest_import_statuses

echo "Plugin import complete."
