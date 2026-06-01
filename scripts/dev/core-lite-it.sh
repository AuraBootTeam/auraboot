#!/usr/bin/env bash
# W2: shared core-lite integration-test harness.
#
# Starts (or reuses) ONE OSS-core-only isolated stack (OSS core host + PG + Redis,
# via scripts/dev/start-isolated.sh — NOT the enterprise full stack), mounts a
# hybrid plugin's backend jar via ENTERPRISE_PLUGIN_JARS_DIR, then imports a single
# plugin's config via the platform import API. Each IT run uses its own tenant for
# isolation; cleanup is by tenant (see AbstractCoreLiteIT).
#
# Usage:
#   scripts/dev/core-lite-it.sh --slug=core-lite --jars-dir=/tmp/core-lite-jars \
#       --plugin=/abs/path/to/<plugin-root>   # the dir containing plugin.json
# Prints BE_PORT (read from .aura-stack/<slug>.env) on success.
# (Harness is plugin-agnostic: --plugin / --jars-dir point at any plugin's root + jar.)
set -euo pipefail

SLUG=""
JARS_DIR=""
PLUGIN_DIR=""
REBUILD=""
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORE_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"   # auraboot (OSS)

for arg in "$@"; do
  case "$arg" in
    --slug=*)     SLUG="${arg#--slug=}" ;;
    --jars-dir=*) JARS_DIR="${arg#--jars-dir=}" ;;
    --plugin=*)   PLUGIN_DIR="${arg#--plugin=}" ;;
    --rebuild)    REBUILD="--rebuild" ;;
    *) echo "unknown arg: $arg" >&2; exit 2 ;;
  esac
done
[ -n "$SLUG" ] || { echo "ERROR: --slug required" >&2; exit 2; }

ENV_FILE="$CORE_ROOT/.aura-stack/${SLUG}.env"

# 1. Start/reuse the OSS-core isolated stack. ENTERPRISE_PLUGIN_JARS_DIR mounts plugin jars.
if [ ! -f "$ENV_FILE" ] || [ -n "$REBUILD" ]; then
  echo ">> starting OSS-core-lite isolated stack (slug=$SLUG)"
  ENTERPRISE_PLUGIN_JARS_DIR="${JARS_DIR:-}" \
    "$SCRIPT_DIR/start-isolated.sh" --slug="$SLUG" --e2e $REBUILD --wait
fi
[ -f "$ENV_FILE" ] || { echo "ERROR: $ENV_FILE missing after start" >&2; exit 1; }

BE_PORT="$(grep '^BE_PORT=' "$ENV_FILE" | cut -d= -f2)"
[ -n "$BE_PORT" ] || { echo "ERROR: BE_PORT not in $ENV_FILE" >&2; exit 1; }

# 2. Smoke gate (fail fast).
HEALTH="$(NO_PROXY=localhost curl -s -o /dev/null -w '%{http_code}' \
  "http://localhost:${BE_PORT}/actuator/health" || echo 000)"
[ "$HEALTH" = "200" ] || { echo "ERROR: backend not healthy (http=$HEALTH) on $BE_PORT" >&2; exit 1; }

# 3. Import the plugin config (model-driven DDL builds tables here) via docker cp + real import API.
#    The OSS-core container does not mount enterprise plugin config, so copy it in, then import.
if [ -n "$PLUGIN_DIR" ]; then
  [ -f "$PLUGIN_DIR/plugin.json" ] || { echo "ERROR: --plugin must point at a plugin root containing plugin.json: $PLUGIN_DIR" >&2; exit 1; }
  local_name="$(basename "$PLUGIN_DIR")"
  container="auraboot-${SLUG}-backend"
  echo ">> docker cp $PLUGIN_DIR -> ${container}:/tmp/${local_name}"
  docker cp "$PLUGIN_DIR" "${container}:/tmp/${local_name}" || { echo "ERROR: docker cp failed" >&2; exit 1; }
  jwt="$(NO_PROXY=localhost curl -s -X POST "http://localhost:${BE_PORT}/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d '{"email":"admin@auraboot.com","password":"Test2026x"}' \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["jwt"])')"
  [ -n "$jwt" ] || { echo "ERROR: admin login returned no jwt" >&2; exit 1; }
  echo ">> import-directory-sync /tmp/${local_name}"
  resp="$(NO_PROXY=localhost curl -s -X POST "http://localhost:${BE_PORT}/api/plugins/import/import-directory-sync" \
    -H "Authorization: Bearer ${jwt}" -H 'Content-Type: application/json' \
    -d "{\"path\":\"/tmp/${local_name}\",\"overwrite\":true}")"
  echo "$resp" | python3 -c 'import sys,json;d=json.load(sys.stdin);sys.exit(0 if d.get("success") else 1)' \
    || { echo "ERROR: import failed: ${resp:0:200}" >&2; exit 1; }
  echo ">> imported ${local_name} (success:true)"
fi

echo "BE_PORT=$BE_PORT"
