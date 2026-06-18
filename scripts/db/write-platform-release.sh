#!/usr/bin/env bash
# Append a row to ab_platform_release after a successful Flyway migrate.
# Deploy/reset tooling calls this; it is an append-only DEPLOYMENT AUDIT ledger,
# NOT migration state. It must never influence which migrations run.
# See end-state spec §10 / P4.
#
# Usage (PG_DB = the migrated target database):
#   PG_DB=<db> scripts/db/write-platform-release.sh --edition oss --app-version 1.0.0 --status installed
#
# Computes db_migration_version (latest applied Flyway version), db_schema_hash
# (sha256 of the schema-only dump), git_sha; status defaults to 'installed'.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/db/flyway-common.sh
source "$SCRIPT_DIR/flyway-common.sh"

EDITION="oss"; APP_VERSION=""; BUILD_VERSION=""; GIT_SHA=""; STATUS="installed"
INSTALLED_BY="${INSTALLED_BY:-${USER:-unknown}}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --edition) EDITION="${2:?}"; shift 2 ;;
    --app-version) APP_VERSION="${2:?}"; shift 2 ;;
    --build-version) BUILD_VERSION="${2:?}"; shift 2 ;;
    --git-sha) GIT_SHA="${2:?}"; shift 2 ;;
    --status) STATUS="${2:?}"; shift 2 ;;
    -h|--help) sed -n '2,14p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "[release] unknown arg: $1" >&2; exit 2 ;;
  esac
done
_require_db

_sha256() { if command -v shasum >/dev/null 2>&1; then shasum -a 256; else sha256sum; fi | cut -d' ' -f1; }
_psql() { PGPASSWORD="$PG_PASSWORD" psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_DB" -tA "$@"; }
_sql_lit() { if [[ -z "$1" ]]; then printf 'NULL'; else printf "'%s'" "${1//\'/\'\'}"; fi; }

DB_MIGRATION_VERSION="$(_psql -c "SELECT version FROM ab_flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1;")"
if [[ -z "$DB_MIGRATION_VERSION" ]]; then
  echo "[release] no successful Flyway migration found in '$PG_DB' — run migrate first." >&2
  exit 1
fi

DB_SCHEMA_HASH="$(PGPASSWORD="$PG_PASSWORD" pg_dump -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" \
  --schema-only --no-owner --no-privileges --exclude-table=ab_flyway_schema_history "$PG_DB" \
  | grep -vE '^\\(un)?restrict |^-- Dumped (from database|by pg_dump) version ' | _sha256)"

[[ -n "$GIT_SHA" ]] || GIT_SHA="$(git -C "$AURA_CORE_ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
[[ -n "$APP_VERSION" ]] || APP_VERSION="$DB_MIGRATION_VERSION"
PID="rel_$(date -u +%Y%m%d%H%M%S)_$$"

_psql -c "INSERT INTO ab_platform_release
  (pid, edition, app_version, build_version, git_sha, db_migration_version, db_schema_hash, status, installed_by)
  VALUES (
    $(_sql_lit "$PID"), $(_sql_lit "$EDITION"), $(_sql_lit "$APP_VERSION"), $(_sql_lit "$BUILD_VERSION"),
    $(_sql_lit "$GIT_SHA"), $(_sql_lit "$DB_MIGRATION_VERSION"), $(_sql_lit "$DB_SCHEMA_HASH"),
    $(_sql_lit "$STATUS"), $(_sql_lit "$INSTALLED_BY"))
  ON CONFLICT (edition, app_version, git_sha, db_migration_version) DO NOTHING;" >&2

echo "[release] recorded edition=$EDITION app=$APP_VERSION migration=$DB_MIGRATION_VERSION hash=${DB_SCHEMA_HASH:0:12} status=$STATUS" >&2
