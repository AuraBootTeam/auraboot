#!/usr/bin/env bash
#
# Shared reset/init primitives. This file is sourced by lifecycle scripts; keep
# functions side-effect free until explicitly called.

aura_export_docker_proxy_defaults() {
  local host_http="${http_proxy:-${HTTP_PROXY:-}}"
  local host_https="${https_proxy:-${HTTPS_PROXY:-}}"

  if [ -n "$host_http" ] && [ -z "${AURA_DOCKER_HTTP_PROXY:-}" ]; then
    export AURA_DOCKER_HTTP_PROXY="${host_http/127.0.0.1/host.docker.internal}"
  fi
  if [ -n "$host_https" ] && [ -z "${AURA_DOCKER_HTTPS_PROXY:-}" ]; then
    export AURA_DOCKER_HTTPS_PROXY="${host_https/127.0.0.1/host.docker.internal}"
  fi
  if [ -z "${AURA_DOCKER_NPM_REGISTRY:-}" ]; then
    export AURA_DOCKER_NPM_REGISTRY="${npm_config_registry:-https://registry.npmmirror.com}"
  fi
}

aura_sync_marketplace_catalog() {
  if [ "$#" -lt 2 ]; then
    echo "ERROR: aura_sync_marketplace_catalog requires <catalog-root> <pg-port>" >&2
    return 2
  fi

  local catalog_root="$1"
  local pg_port="$2"
  local pg_host="${3:-${AURA_MARKETPLACE_PG_HOST:-localhost}}"
  local pg_user="${4:-${AURA_MARKETPLACE_PG_USER:-auraboot}}"
  local pg_db="${5:-${AURA_MARKETPLACE_PG_DB:-aura_boot}}"
  local pg_password="${6:-${AURA_MARKETPLACE_PG_PASSWORD:-auraboot_dev}}"

  PG_HOST="$pg_host" \
  PG_PORT="$pg_port" \
  PG_USER="$pg_user" \
  PG_DB="$pg_db" \
  PGPASSWORD="$pg_password" \
    "$catalog_root/scripts/sync-marketplace-catalog.sh"
}

_aura_bootstrap_initialized_from_json() {
  python3 -c "
import json
import sys
try:
    d = json.load(sys.stdin)
except Exception:
    print('false')
    sys.exit(0)
data = d.get('data') if isinstance(d, dict) else {}
print('true' if isinstance(data, dict) and data.get('initialized') is True else 'false')
"
}

_aura_bootstrap_setup_response_ok() {
  python3 -c "
import json
import sys
try:
    d = json.load(sys.stdin)
except Exception:
    print('false')
    sys.exit(0)
data = d.get('data') if isinstance(d, dict) else {}
message = (d.get('message') or '') if isinstance(d, dict) else ''
success = (
    d.get('success') is True
    or d.get('code') == '0'
    or (isinstance(data, dict) and data.get('success') is True)
    or 'already initialized' in message.lower()
)
print('true' if success else 'false')
"
}

_aura_bootstrap_setup_payload() {
  python3 - "$1" "$2" "$3" "$4" "$5" <<'PY'
import json
import sys

company_name, admin_email, admin_password, admin_display_name, system_mode = sys.argv[1:6]
print(json.dumps({
    "companyName": company_name,
    "adminEmail": admin_email,
    "adminPassword": admin_password,
    "adminDisplayName": admin_display_name,
    "systemMode": system_mode,
}, separators=(",", ":")))
PY
}

aura_bootstrap_setup_if_needed() {
  if [ "$#" -lt 6 ]; then
    echo "ERROR: aura_bootstrap_setup_if_needed requires <api-base> <company-name> <admin-email> <admin-password> <admin-display-name> <system-mode> [log-prefix]" >&2
    return 2
  fi

  local api_base="${1%/}"
  local company_name="$2"
  local admin_email="$3"
  local admin_password="$4"
  local admin_display_name="$5"
  local system_mode="$6"
  local log_prefix="${7:-[bootstrap]}"
  local status_timeout="${AURA_BOOTSTRAP_STATUS_TIMEOUT:-10}"
  local setup_timeout="${AURA_BOOTSTRAP_SETUP_TIMEOUT:-30}"
  local status_resp initialized payload setup_out http_code setup_body setup_ok

  status_resp="$(NO_PROXY=localhost,127.0.0.1 curl -sS --max-time "$status_timeout" "$api_base/api/bootstrap/status" 2>/dev/null || echo '{}')"
  initialized="$(printf '%s' "$status_resp" | _aura_bootstrap_initialized_from_json 2>/dev/null || echo false)"

  if [ "$initialized" = "true" ]; then
    echo "$log_prefix bootstrap already initialized"
    return 0
  fi

  echo "$log_prefix bootstrap is not initialized; running /api/bootstrap/setup..."
  payload="$(_aura_bootstrap_setup_payload "$company_name" "$admin_email" "$admin_password" "$admin_display_name" "$system_mode")"
  setup_out="$(NO_PROXY=localhost,127.0.0.1 curl -sS --max-time "$setup_timeout" -w $'\n%{http_code}' -X POST "$api_base/api/bootstrap/setup" \
    -H 'Content-Type: application/json' \
    -d "$payload")"
  http_code="$(printf '%s\n' "$setup_out" | tail -n 1)"
  setup_body="$(printf '%s\n' "$setup_out" | sed '$d')"

  if [ "$http_code" != "200" ]; then
    echo "ERROR: /api/bootstrap/setup failed (HTTP $http_code)" >&2
    printf '%s\n' "$setup_body" >&2
    return 1
  fi

  setup_ok="$(printf '%s' "$setup_body" | _aura_bootstrap_setup_response_ok 2>/dev/null || echo false)"
  if [ "$setup_ok" != "true" ]; then
    echo "ERROR: /api/bootstrap/setup returned an unsuccessful response" >&2
    printf '%s\n' "$setup_body" >&2
    return 1
  fi

  status_resp="$(NO_PROXY=localhost,127.0.0.1 curl -sS --max-time "$status_timeout" "$api_base/api/bootstrap/status")"
  initialized="$(printf '%s' "$status_resp" | _aura_bootstrap_initialized_from_json 2>/dev/null || echo false)"
  if [ "$initialized" != "true" ]; then
    echo "ERROR: bootstrap status is still not initialized: $status_resp" >&2
    return 1
  fi

  echo "$log_prefix bootstrap setup OK"
}
