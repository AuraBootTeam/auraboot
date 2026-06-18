#!/usr/bin/env bash
# Production deploy orchestrator. Runs the schema migration + release ledger
# BEFORE starting the app, then brings the app up via docker-compose, then
# health-checks. Application startup is NOT the migrator (end-state §deploy / §9).
#
# Prod-facing — run on the deploy host / CI, NOT auto-run by the repo. Requires
# the Flyway CLI, psql/pg_dump, and docker compose on the host.
#
# Usage:
#   PG_DB=<prod_db> PG_HOST=... PG_USER=... PG_PASSWORD=... \
#     scripts/deploy/prod-deploy.sh --edition enterprise \
#     --enterprise-root <enterprise-repo-root> [--app-version 1.2.0]
#   Env overrides: COMPOSE_FILE (default docker-compose.prod.yml), HEALTH_URL.
#
# Steps:
#   0. (operator) BACKUP the database — required before a destructive upgrade.
#   1. deploy-migrate.sh : flywayMigrate -> flywayValidate -> write ab_platform_release
#   2. docker compose -f <COMPOSE_FILE> up -d
#   3. wait for /actuator/health UP
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-$ROOT/docker-compose.prod.yml}"
HEALTH_URL="${HEALTH_URL:-http://localhost:6443/actuator/health}"

EDITION="oss"; ENTERPRISE_ROOT=""; PASS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --edition) EDITION="${2:?}"; shift 2 ;;
    --enterprise-root) ENTERPRISE_ROOT="${2:?}"; shift 2 ;;
    --app-version|--build-version|--git-sha) PASS+=("$1" "${2:?}"); shift 2 ;;
    -h|--help) sed -n '2,23p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "[prod-deploy] unknown arg: $1" >&2; exit 2 ;;
  esac
done
ENT=()
[[ -n "$ENTERPRISE_ROOT" ]] && ENT=(--enterprise-root "$ENTERPRISE_ROOT")

echo "[prod-deploy] reminder: ensure a DB backup exists before a destructive upgrade."
echo "[prod-deploy] 1/3 schema migrate + release ledger (edition=$EDITION)"
"$ROOT/scripts/db/deploy-migrate.sh" --edition "$EDITION" ${ENT[@]+"${ENT[@]}"} ${PASS[@]+"${PASS[@]}"}

echo "[prod-deploy] 2/3 docker compose up ($COMPOSE_FILE)"
docker compose -f "$COMPOSE_FILE" up -d

echo "[prod-deploy] 3/3 health check ($HEALTH_URL)"
for _ in $(seq 1 60); do
  if curl -fsS -m 5 "$HEALTH_URL" 2>/dev/null | grep -q '"status":"UP"'; then
    echo "[prod-deploy] healthy — deploy complete."
    exit 0
  fi
  sleep 5
done
echo "[prod-deploy] health check did not pass in time — investigate (app log / DB)." >&2
exit 1
