#!/usr/bin/env bash
#
# Import AuraBoot plugins into a running backend.
#
# This is the single plugin import executor for host and isolated Docker
# environments. Reset/init scripts decide which profile to import; bootstrap
# setup does not import plugins.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
STACK_DIR="$PROJECT_ROOT/.aura-stack"

SLUG=""
PROFILE="core"
EDITION="auto"
BACKEND_URL="${BACKEND_URL:-}"
PLUGIN_ROOT="${PLUGIN_ROOT:-${PLUGINS_DIR:-}}"
ENTERPRISE_PLUGIN_ROOT="${ENTERPRISE_PLUGIN_ROOT:-${ENTERPRISE_PLUGINS_DIR:-}}"
PLUGINS=()

usage() {
    cat <<USAGE
Usage: $0 [--slug=<name>] [--profile=core|demo|e2e|enterprise-demo|pcba-agent] [options] [plugin...]

Options:
  --slug=<name>                    Isolated Docker stack slug. When supplied,
                                   backend URL, DB port, and container names are
                                   loaded from .aura-stack/<slug>.env.
  --profile=<name>                 Plugin import profile from scripts/dev/plugin-import-profiles.json.
  --edition=auto|oss|enterprise    Plugin root selection mode:
                                     auto       prefer enterprise root when it exists
                                     oss        only import from OSS plugin root
                                     enterprise prefer enterprise plugin root, then OSS root
  --backend-url=<url>              Backend base URL for host mode.
  --plugin-root=<path>             OSS plugin root. Host path in host mode; container
                                   path in --slug Docker mode.
  --enterprise-plugin-root=<path>  Enterprise plugin root. Host path in host mode;
                                   container path in --slug Docker mode.

Environment:
  ADMIN_EMAIL        default: admin@auraboot.com
  ADMIN_PASSWORD     default: Test2026x
  IMPORT_ATTEMPTS    default: 2
USAGE
}

for arg in "$@"; do
    case "$arg" in
        --slug=*) SLUG="${arg#--slug=}" ;;
        --profile=*) PROFILE="${arg#--profile=}" ;;
        --edition=*) EDITION="${arg#--edition=}" ;;
        --backend-url=*) BACKEND_URL="${arg#--backend-url=}" ;;
        --plugin-root=*) PLUGIN_ROOT="${arg#--plugin-root=}" ;;
        --enterprise-plugin-root=*) ENTERPRISE_PLUGIN_ROOT="${arg#--enterprise-plugin-root=}" ;;
        --help|-h) usage; exit 0 ;;
        --*) echo "ERROR: unknown argument: $arg" >&2; usage; exit 2 ;;
        *) PLUGINS+=("$arg") ;;
    esac
done

case "$EDITION" in
    auto|oss|enterprise) ;;
    *) echo "ERROR: unknown edition: $EDITION" >&2; exit 2 ;;
esac

PROJECT_NAME=""
BACKEND_CONTAINER=""
POSTGRES_CONTAINER=""
DB_USER="${PG_USER:-${PGUSER:-auraboot}}"
DB_NAME="${PG_DB:-${PGDATABASE:-aura_boot}}"

if [ -n "$SLUG" ]; then
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
    BACKEND_CONTAINER="${PROJECT_NAME}-backend"
    POSTGRES_CONTAINER="${PROJECT_NAME}-postgres"
    DB_USER="${PG_USER:-auraboot}"
    DB_NAME="${PG_DB:-aura_boot}"
    PLUGIN_ROOT="${PLUGIN_ROOT:-/app/plugins}"
    ENTERPRISE_PLUGIN_ROOT="${ENTERPRISE_PLUGIN_ROOT:-/app/plugins-enterprise}"
else
    BACKEND_URL="${BACKEND_URL:-http://localhost:${BE_PORT:-6443}}"
    PLUGIN_ROOT="${PLUGIN_ROOT:-$PROJECT_ROOT/plugins}"
    if [ -z "$ENTERPRISE_PLUGIN_ROOT" ] && [ -n "${AURA_ENTERPRISE_ROOT:-}" ]; then
        ENTERPRISE_PLUGIN_ROOT="$AURA_ENTERPRISE_ROOT/plugins"
    fi
fi

ADMIN_EMAIL="${ADMIN_EMAIL:-admin@auraboot.com}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-Test2026x}"
IMPORT_ATTEMPTS="${IMPORT_ATTEMPTS:-2}"
PROFILE_CONFIG="$PROJECT_ROOT/scripts/dev/plugin-import-profiles.json"

if [ "$PROFILE" = "default" ]; then
    echo "WARNING: deprecated profile: default; use core, demo, e2e, enterprise-demo, or pcba-agent" >&2
fi

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

if [ "${#PLUGINS[@]}" -eq 0 ]; then
    echo "ERROR: no plugins selected" >&2
    exit 2
fi

echo "Importing plugins into ${PROJECT_NAME:-host backend} ($BACKEND_URL)"
echo "Profile: $PROFILE; edition: $EDITION"
echo "Plugin root: $PLUGIN_ROOT"
if [ -n "$ENTERPRISE_PLUGIN_ROOT" ]; then
    echo "Enterprise plugin root: $ENTERPRISE_PLUGIN_ROOT"
fi
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

path_has_plugin() {
    local path="$1"
    if [ -n "$BACKEND_CONTAINER" ]; then
        docker exec "$BACKEND_CONTAINER" sh -lc "[ -f '$path/plugin.json' ]" >/dev/null 2>&1
    else
        if [ -f "$path/plugin.json" ]; then
            return 0
        fi
        case "$path" in
            /app/plugins/*)
                [ -f "$PROJECT_ROOT/plugins/${path#/app/plugins/}/plugin.json" ]
                ;;
            /app/plugins-enterprise/*)
                if [ -n "${AURA_ENTERPRISE_ROOT:-}" ]; then
                    [ -f "$AURA_ENTERPRISE_ROOT/plugins/${path#/app/plugins-enterprise/}/plugin.json" ]
                else
                    return 1
                fi
                ;;
            *)
                return 1
                ;;
        esac
    fi
}

container_plugin_path() {
    local plugin="$1"
    local candidate

    case "$EDITION" in
        oss)
            candidate="$PLUGIN_ROOT/$plugin"
            if path_has_plugin "$candidate"; then
                printf '%s\n' "$candidate"
                return 0
            fi
            ;;
        auto|enterprise)
            if [ -n "$ENTERPRISE_PLUGIN_ROOT" ]; then
                candidate="$ENTERPRISE_PLUGIN_ROOT/$plugin"
                if path_has_plugin "$candidate"; then
                    printf '%s\n' "$candidate"
                    return 0
                fi
            fi
            candidate="$PLUGIN_ROOT/$plugin"
            if path_has_plugin "$candidate"; then
                printf '%s\n' "$candidate"
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
if d.get('success') is True or d.get('code') == '0':
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
        echo "  FAIL $plugin: plugin.json not found in configured plugin roots"
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

run_import_history_query() {
    local sql="$1"
    if [ -n "$POSTGRES_CONTAINER" ]; then
        docker exec "$POSTGRES_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -Atc "$sql"
        return
    fi

    local pg_host="${PG_HOST:-${PGHOST:-localhost}}"
    local pg_port="${PG_PORT:-${PGPORT:-5432}}"
    local pg_user="${PG_USER:-${PGUSER:-${USER:-ghj}}}"
    local pg_db="${PG_DB:-${PGDATABASE:-aura_boot}}"

    if [ -n "${PG_PASSWORD:-${PGPASSWORD:-}}" ]; then
        PGPASSWORD="${PG_PASSWORD:-${PGPASSWORD}}" psql -h "$pg_host" -p "$pg_port" -U "$pg_user" -d "$pg_db" -Atc "$sql"
    else
        psql -h "$pg_host" -p "$pg_port" -U "$pg_user" -d "$pg_db" -Atc "$sql"
    fi
}

verify_latest_import_statuses() {
    local plugin_id values_sql failed_rows query

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

    query="
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
"
    local error_file
    error_file="$(mktemp -t aura-import-history.XXXXXX)"
    if ! failed_rows="$(run_import_history_query "$query" 2>"$error_file")"; then
        echo "ERROR: could not verify latest plugin import statuses" >&2
        cat "$error_file" >&2 || true
        rm -f "$error_file"
        return 1
    fi
    rm -f "$error_file"

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
