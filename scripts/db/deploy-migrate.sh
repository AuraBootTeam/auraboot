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

EDITION="oss"; ENTERPRISE_ROOT=""; PASS=(); PRE1900_CORE_COMPAT="${AURA_FLYWAY_PRE1900_CORE_COMPAT:-0}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --edition) EDITION="${2:?}"; shift 2 ;;
    --enterprise-root) ENTERPRISE_ROOT="${2:?}"; shift 2 ;;
    --pre1900-core-compat) PRE1900_CORE_COMPAT=1; shift ;;
    --app-version|--build-version|--git-sha|--status) PASS+=("$1" "${2:?}"); shift 2 ;;
    -h|--help) sed -n '2,17p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "[deploy-migrate] unknown arg: $1" >&2; exit 2 ;;
  esac
done
[[ "$PRE1900_CORE_COMPAT" == 0 || "$PRE1900_CORE_COMPAT" == 1 ]] || {
  echo "[deploy-migrate] AURA_FLYWAY_PRE1900_CORE_COMPAT must be 0 or 1" >&2
  exit 2
}
ENT_ARGS=()
[[ -n "$ENTERPRISE_ROOT" ]] && ENT_ARGS=(--enterprise-root "$ENTERPRISE_ROOT")

if [[ "$PRE1900_CORE_COMPAT" == 1 ]]; then
  [[ "$EDITION" == enterprise ]] || {
    echo "[deploy-migrate] pre-#1900 compatibility requires edition=enterprise" >&2
    exit 2
  }
  [[ -n "${PG_DB:-${PGDATABASE:-}}" ]] || {
    echo "[deploy-migrate] PG_DB is required for pre-#1900 compatibility" >&2
    exit 2
  }
  command -v psql >/dev/null || exit 127
  history_match="$(PGPASSWORD="${PG_PASSWORD:-${PGPASSWORD:-}}" psql -X -qAt -v ON_ERROR_STOP=1 \
    -h "${PG_HOST:-${PGHOST:-localhost}}" -p "${PG_PORT:-${PGPORT:-5432}}" \
    -U "${PG_USER:-${PGUSER:-auraboot}}" -d "${PG_DB:-${PGDATABASE:-}}" \
    -c "SELECT count(*) FROM ab_flyway_schema_history WHERE version='20260705093000' AND checksum=633449782 AND success")" || {
    echo "[deploy-migrate] cannot verify target Flyway history" >&2
    exit 2
  }
  [[ "$history_match" == 1 ]] || {
    echo "[deploy-migrate] target is not the audited pre-#1900 migration cohort" >&2
    exit 2
  }
  stage_root="$(mktemp -d)"
  trap 'rm -r "$stage_root"' EXIT
  "$SCRIPT_DIR/stage-pre1900-core-migrations.sh" \
    --core-root "$(cd "$SCRIPT_DIR/../.." && pwd)" --out-dir "$stage_root/core"
  export AURA_FLYWAY_CORE_MIGRATION_DIR="$stage_root/core"
  export AURA_FLYWAY_OUT_OF_ORDER=1
  export AURA_FLYWAY_SKIP_KNOWN_DRIFT_REPAIR=1
  echo "[deploy-migrate] pinned core overlay manifest sha256=$(shasum -a 256 "$stage_root/core/sha256-manifest.txt" | awk '{print $1}')"
fi

echo "[deploy-migrate] 1/3 flywayMigrate (edition=$EDITION)"
"$SCRIPT_DIR/flyway-migrate.sh" --edition "$EDITION" ${ENT_ARGS[@]+"${ENT_ARGS[@]}"}

echo "[deploy-migrate] 2/3 flywayValidate"
"$SCRIPT_DIR/flyway-validate.sh" --edition "$EDITION" ${ENT_ARGS[@]+"${ENT_ARGS[@]}"}

echo "[deploy-migrate] 3/3 record ab_platform_release"
"$SCRIPT_DIR/write-platform-release.sh" --edition "$EDITION" ${ENT_ARGS[@]+"${ENT_ARGS[@]}"} ${PASS[@]+"${PASS[@]}"}

echo "[deploy-migrate] done. Now (re)start the application."
