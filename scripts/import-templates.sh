#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEMPLATES_DIR="$ROOT_DIR/plugins/templates"
API_BASE="${API_BASE:-http://localhost:6443}"
ADMIN_EMAIL="${ADMIN_EMAIL:-admin@example.com}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-Test2026x}"
OVERWRITE="${OVERWRITE:-true}"
NO_PROXY_VALUE="${NO_PROXY_VALUE:-localhost,127.0.0.1}"
MAX_WAIT_SECONDS="${MAX_WAIT_SECONDS:-120}"

if [[ ! -d "$TEMPLATES_DIR" ]]; then
  echo "templates directory not found: $TEMPLATES_DIR" >&2
  exit 1
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 is required" >&2
  exit 1
fi

echo "Importing templates from: $TEMPLATES_DIR"
echo "API base: $API_BASE"

health_url="$API_BASE/actuator/health"
waited=0
until NO_PROXY="$NO_PROXY_VALUE" curl -fsS "$health_url" >/dev/null 2>&1; do
  if (( waited >= MAX_WAIT_SECONDS )); then
    echo "backend is not ready: $health_url" >&2
    exit 1
  fi
  if (( waited == 0 )); then
    echo "Waiting for backend health check..."
  fi
  sleep 2
  waited=$((waited + 2))
done

login_response="$(
  NO_PROXY="$NO_PROXY_VALUE" curl -fsS -X POST "$API_BASE/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}"
)"

token="$(
  printf '%s' "$login_response" | python3 -c 'import sys, json; print(json.load(sys.stdin)["data"]["jwt"])'
)"

if [[ -z "$token" ]]; then
  echo "failed to acquire JWT" >&2
  exit 1
fi

failures=0
template_count=0

while IFS= read -r template_dir; do
  [[ -z "$template_dir" ]] && continue
  template_count=$((template_count + 1))
  template_name="$(basename "$template_dir")"
  echo "== $template_name =="

  request_body="$(
    TEMPLATE_DIR="$template_dir" OVERWRITE_VALUE="$OVERWRITE" python3 - <<'PY'
import json, os
print(json.dumps({
  "path": os.environ["TEMPLATE_DIR"],
  "overwrite": os.environ["OVERWRITE_VALUE"].lower() == "true",
}))
PY
  )"

  response="$(
    NO_PROXY="$NO_PROXY_VALUE" curl -fsS -X POST "$API_BASE/api/plugins/import/import-directory-sync" \
      -H "Authorization: Bearer $token" \
      -H 'Content-Type: application/json' \
      -d "$request_body"
  )"

  summary="$(printf '%s' "$response" | python3 -c 'import sys, json; d=json.load(sys.stdin); print(json.dumps({"success": d.get("success"), "pluginId": d.get("pluginId"), "errorMessage": d.get("errorMessage")}, ensure_ascii=False))')"
  echo "$summary"

  success="$(printf '%s' "$response" | python3 -c 'import sys, json; print("true" if json.load(sys.stdin).get("success") else "false")')"
  if [[ "$success" != "true" ]]; then
    failures=$((failures + 1))
  fi
done < <(find "$TEMPLATES_DIR" -maxdepth 1 -mindepth 1 -type d | sort)

echo
echo "Imported $template_count template plugins; failures=$failures"

if [[ "$failures" -gt 0 ]]; then
  exit 1
fi
