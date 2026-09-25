#!/usr/bin/env bash
# Deploy-time schema step: migrate the target DB, validate, and record the
# release ledger. Run this against the target database BEFORE (re)starting the
# application — application startup is NOT the migrator (end-state §deploy / §9).
# Multi-repo aware (records core + enterprise shas via write-platform-release.sh).
#
# Order is migrate -> validate -> ledger so it is robust for both a FRESH deploy
# (everything pending) and an UPGRADE (existing history). Flyway migrate already
# checksum-validates previously-applied migrations before applying new ones.
#
# Usage (PG_DB = the target database; ensure extensions + Flyway CLI available):
#   PG_DB=<db> scripts/db/deploy-migrate.sh --edition oss
#   PG_DB=<db> scripts/db/deploy-migrate.sh --edition enterprise \
#        --enterprise-root <enterprise-repo-root> [--app-version 1.2.0]
#
# For production behind docker-compose: run this as a one-shot deploy step
# against the prod DB, then `docker compose -f docker-compose.prod.yml up -d`.
# Do NOT bake migration into the app container entrypoint.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

EDITION="oss"; ENTERPRISE_ROOT=""; PASS=(); RELEASE_STATUS="installed"; PRE1900_CORE_COMPAT="${AURA_FLYWAY_PRE1900_CORE_COMPAT:-0}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --edition) EDITION="${2:?}"; shift 2 ;;
    --enterprise-root) ENTERPRISE_ROOT="${2:?}"; shift 2 ;;
    --pre1900-core-compat) PRE1900_CORE_COMPAT=1; shift ;;
    --app-version|--build-version|--git-sha) PASS+=("$1" "${2:?}"); shift 2 ;;
    --status) RELEASE_STATUS="${2:?}"; PASS+=("$1" "$RELEASE_STATUS"); shift 2 ;;
    -h|--help) sed -n '2,17p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "[deploy-migrate] unknown arg: $1" >&2; exit 2 ;;
  esac
done
[[ "$PRE1900_CORE_COMPAT" == 0 || "$PRE1900_CORE_COMPAT" == 1 ]] || {
  echo "[deploy-migrate] AURA_FLYWAY_PRE1900_CORE_COMPAT must be 0 or 1" >&2
  exit 2
}
[[ "$RELEASE_STATUS" == installed || "$RELEASE_STATUS" == failed || "$RELEASE_STATUS" == rolled_back ]] || {
  echo "[deploy-migrate] release status must be installed, failed, or rolled_back" >&2
  exit 2
}
ENT_ARGS=()
[[ -n "$ENTERPRISE_ROOT" ]] && ENT_ARGS=(--enterprise-root "$ENTERPRISE_ROOT")

if [[ "$PRE1900_CORE_COMPAT" == 1 ]]; then
  echo "[deploy-migrate] pre-1900 compatibility is retired; a separately reviewed historical upgrade is required" >&2
  exit 2
fi

echo "[deploy-migrate] 1/3 flywayMigrate (edition=$EDITION)"
"$SCRIPT_DIR/flyway-migrate.sh" --edition "$EDITION" ${ENT_ARGS[@]+"${ENT_ARGS[@]}"}

echo "[deploy-migrate] 2/3 flywayValidate"
"$SCRIPT_DIR/flyway-validate.sh" --edition "$EDITION" ${ENT_ARGS[@]+"${ENT_ARGS[@]}"}

echo "[deploy-migrate] 3/3 record ab_platform_release"
"$SCRIPT_DIR/write-platform-release.sh" --edition "$EDITION" ${ENT_ARGS[@]+"${ENT_ARGS[@]}"} ${PASS[@]+"${PASS[@]}"}

echo "[deploy-migrate] done. Now (re)start the application."
