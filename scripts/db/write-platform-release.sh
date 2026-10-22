#!/usr/bin/env bash
# Append a row to ab_platform_release after a successful Flyway migrate.
# Deploy/reset tooling calls this; it is an append-only DEPLOYMENT AUDIT ledger,
# NOT migration state. It must never influence which migrations run.
# See end-state spec §10 / P4 and docs/agent-rules/flyway-schema-change-and-local-bringup.md §4.
#
# Multi-repo aware: AuraBoot is multiple repos (core + enterprise [+ vertical
# plugin repos]). A single git_sha cannot represent a build, so:
#   - db_schema_hash  = sha256 of the schema-only dump — repo-agnostic schema fingerprint
#   - app_version     = single human version (--app-version > VERSION file > platform.version)
#   - git_sha         = the PRIMARY repo's HEAD (enterprise for enterprise edition, else core)
#   - metadata.repo_shas = { core, enterprise } — full multi-repo provenance
#
# Usage (PG_DB = the migrated target database):
#   PG_DB=<db> scripts/db/write-platform-release.sh --edition oss
#   PG_DB=<db> scripts/db/write-platform-release.sh --edition enterprise \
#        --enterprise-root <enterprise-repo-root> --app-version 1.2.0 --status installed
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/db/flyway-common.sh
source "$SCRIPT_DIR/flyway-common.sh"

EDITION="oss"; APP_VERSION=""; BUILD_VERSION=""; GIT_SHA=""; STATUS="installed"
ENTERPRISE_ROOT="${AURA_ENTERPRISE_ROOT:-}"
INSTALLED_BY="${INSTALLED_BY:-${USER:-unknown}}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --edition) EDITION="${2:?}"; shift 2 ;;
    --enterprise-root) ENTERPRISE_ROOT="${2:?}"; shift 2 ;;
    --app-version) APP_VERSION="${2:?}"; shift 2 ;;
    --build-version) BUILD_VERSION="${2:?}"; shift 2 ;;
    --git-sha) GIT_SHA="${2:?}"; shift 2 ;;
    --status) STATUS="${2:?}"; shift 2 ;;
    -h|--help) sed -n '2,21p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "[release] unknown arg: $1" >&2; exit 2 ;;
  esac
done
_require_db

_sha256() { if command -v shasum >/dev/null 2>&1; then shasum -a 256; else sha256sum; fi | cut -d' ' -f1; }
_psql() { PGPASSWORD="$PG_PASSWORD" psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_DB" -tA "$@"; }
_sql_lit() { if [[ -z "$1" ]]; then printf 'NULL'; else printf "'%s'" "${1//\'/\'\'}"; fi; }

# A release-contained migrator deliberately has no Git checkout. Its caller must
# verify the immutable payload and pass its full repository identities explicitly.
# Never turn an unavailable checkout into successful "unknown" provenance.
REPOSITORY_SHAS="$(CORE_SOURCE_ROOT="$AURA_CORE_ROOT" ENT_SOURCE_ROOT="$ENTERPRISE_ROOT" \
  RELEASE_EDITION="$EDITION" python3 - <<'PY'
import json
import os
from pathlib import Path
import re
import subprocess

required = {'core'} | ({'enterprise'} if os.environ['RELEASE_EDITION'] == 'enterprise' else set())
roots = {'core': os.environ['CORE_SOURCE_ROOT'], 'enterprise': os.environ['ENT_SOURCE_ROOT']}
raw = os.environ.get('AURA_RELEASE_REPOSITORY_SHAS_JSON', '')
shas = json.loads(raw) if raw else {}
if not isinstance(shas, dict):
    raise SystemExit('[release] verified repository identities must be an object')
for name, root in roots.items():
    if name not in required:
        continue
    if root and (Path(root) / '.git').exists():
        actual = subprocess.check_output(['git', '-C', root, 'rev-parse', 'HEAD'], text=True).strip()
        if raw and shas.get(name) != actual:
            raise SystemExit('[release] verified repository SHA contradicts checkout: ' + name)
        if not raw:
            shas[name] = actual
if not required.issubset(shas):
    raise SystemExit('[release] explicit verified repository SHAs are required outside a Git checkout')
for name, value in shas.items():
    if not re.fullmatch(r'[a-z][a-z0-9_-]*', name) or not isinstance(value, str) or not re.fullmatch(r'[0-9a-f]{40}', value):
        raise SystemExit('[release] repository identities require safe owners and full Git OIDs')
print(json.dumps(shas, sort_keys=True))
PY
)"

DB_MIGRATION_VERSION="$(_psql -c "SELECT version FROM ab_flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1;")"
if [[ -z "$DB_MIGRATION_VERSION" ]]; then
  echo "[release] no successful Flyway migration found in '$PG_DB' — run migrate first." >&2
  exit 1
fi

# Repo-agnostic schema fingerprint (independent of how many repos contributed migrations).
DB_SCHEMA_HASH="$(PGPASSWORD="$PG_PASSWORD" pg_dump -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" \
  --schema-only --no-owner --no-privileges --exclude-table=ab_flyway_schema_history "$PG_DB" \
  | grep -vE '^\\(un)?restrict |^-- Dumped (from database|by pg_dump) version ' | _sha256)"

# Multi-repo provenance.
CORE_SHA="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["core"])' "$REPOSITORY_SHAS")"
ENT_SHA=""
PRIMARY_ROOT="$AURA_CORE_ROOT"
if [[ "$EDITION" == "enterprise" && -n "$ENTERPRISE_ROOT" ]]; then
  ENT_SHA="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["enterprise"])' "$REPOSITORY_SHAS")"
  PRIMARY_ROOT="$ENTERPRISE_ROOT"
fi
PRIMARY_SHA="${ENT_SHA:-$CORE_SHA}"
[[ -z "$GIT_SHA" || "$GIT_SHA" == "$PRIMARY_SHA" ]] || {
  echo "[release] primary Git SHA must match the verified primary repository" >&2; exit 2;
}
GIT_SHA="$PRIMARY_SHA"

# app_version: --app-version > VERSION file (primary repo) > platform.version > migration version.
if [[ -z "$APP_VERSION" && -f "$PRIMARY_ROOT/VERSION" ]]; then
  APP_VERSION="$(tr -d ' \r\n' < "$PRIMARY_ROOT/VERSION")"
fi
if [[ -z "$APP_VERSION" ]]; then
  APP_VERSION="$(grep -A3 '^  platform:' "$AURA_CORE_ROOT/platform/platform-app/src/main/resources/application.yml" 2>/dev/null \
    | grep -m1 -E '^[[:space:]]+version:' | sed 's/.*version:[[:space:]]*//' | tr -d ' \r')"
fi
[[ -n "$APP_VERSION" ]] || APP_VERSION="$DB_MIGRATION_VERSION"

META="$(REPOSITORY_SHAS="$REPOSITORY_SHAS" BUILD="$BUILD_VERSION" python3 -c '
import json, os
shas = json.loads(os.environ["REPOSITORY_SHAS"])
meta = {"repo_shas": shas}
if os.environ.get("BUILD"):
    meta["build"] = os.environ["BUILD"]
print(json.dumps(meta))
')"

PID="rel_$(date -u +%Y%m%d%H%M%S)_$$"

_psql -c "INSERT INTO ab_platform_release
  (pid, edition, app_version, build_version, git_sha, db_migration_version, db_schema_hash, status, installed_by, metadata)
  VALUES (
    $(_sql_lit "$PID"), $(_sql_lit "$EDITION"), $(_sql_lit "$APP_VERSION"), $(_sql_lit "$BUILD_VERSION"),
    $(_sql_lit "$GIT_SHA"), $(_sql_lit "$DB_MIGRATION_VERSION"), $(_sql_lit "$DB_SCHEMA_HASH"),
    $(_sql_lit "$STATUS"), $(_sql_lit "$INSTALLED_BY"), $(_sql_lit "$META")::jsonb)
  ON CONFLICT (edition, app_version, git_sha, db_migration_version) DO NOTHING;" >&2

echo "[release] recorded edition=$EDITION app=$APP_VERSION migration=$DB_MIGRATION_VERSION" >&2
echo "[release]   git_sha(primary)=$GIT_SHA repo_shas=core:$CORE_SHA${ENT_SHA:+ enterprise:$ENT_SHA} hash=${DB_SCHEMA_HASH:0:12} status=$STATUS" >&2
