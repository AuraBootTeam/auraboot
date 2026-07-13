#!/usr/bin/env bash
#
# Answer one question about an EXISTING database: does it still match
# platform/src/main/resources/database/schema.sql?
#
# Why this exists: schema.sql is 300+ `CREATE TABLE IF NOT EXISTS` statements, so
# re-applying it to a database that was created by an older checkout is a silent
# no-op for every table that already exists — columns added since then are never
# back-filled. The database then looks fine (psql exits 0) and fails much later,
# far from the cause: on 2026-07-13 a reused slot-80 database was missing
# ab_named_query.resource_code, and the only symptom was a plugin import dying with
# `25P02 current transaction is aborted` pointing at an unrelated COUNT(*).
#
# Applies schema.sql to a throwaway reference database (same trick as
# check-schema-sql.sh), then diffs table+column sets against the target.
#
# Usage:
#   scripts/db/check-db-matches-schema-sql.sh <target-db> [--quiet]
#
# Connection (same env contract as the rest of scripts/db):
#   PG_HOST/PG_PORT/PG_USER/PG_PASSWORD, PG_ADMIN_DB=postgres
#
# Exit codes:
#   0 — target matches schema.sql (or target has no app tables yet: nothing to drift)
#   1 — target is missing tables/columns that schema.sql declares (drift)
#   2 — environment problem (psql missing, cannot connect, schema.sql absent)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SCHEMA_FILE="$PROJECT_ROOT/platform/src/main/resources/database/schema.sql"

TARGET_DB="${1:-}"
QUIET=0
[ "${2:-}" = "--quiet" ] && QUIET=1

if [ -z "$TARGET_DB" ]; then
  echo "usage: $0 <target-db> [--quiet]" >&2
  exit 2
fi
if [ ! -f "$SCHEMA_FILE" ]; then
  echo "[db-drift] schema.sql not found: $SCHEMA_FILE" >&2
  exit 2
fi
command -v psql >/dev/null 2>&1 || { echo "[db-drift] psql not on PATH" >&2; exit 2; }

PG_HOST="${PG_HOST:-${PGHOST:-127.0.0.1}}"
PG_PORT="${PG_PORT:-${PGPORT:-5432}}"
PG_USER="${PG_USER:-${PGUSER:-auraboot}}"
PG_PASSWORD="${PG_PASSWORD:-${PGPASSWORD:-auraboot}}"
PG_ADMIN_DB="${PG_ADMIN_DB:-postgres}"
export PGPASSWORD="$PG_PASSWORD"

REF_DB="aura_schema_ref_$$"
cleanup() {
  psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_ADMIN_DB" -q \
    -c "DROP DATABASE IF EXISTS $REF_DB" >/dev/null 2>&1 || true
  rm -f /tmp/"$REF_DB".ref /tmp/"$REF_DB".target /tmp/"$REF_DB".apply
}
trap cleanup EXIT

say() { [ "$QUIET" = "1" ] || echo "$@" >&2; }

# Column inventory of a database: "table.column" per line, app tables only.
inventory() {
  psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$1" -tAq -c "
    SELECT table_name || '.' || column_name
      FROM information_schema.columns
     WHERE table_schema = 'public'
       AND table_name LIKE 'ab\_%'
     ORDER BY 1"
}

target_tables=$(psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$TARGET_DB" -tAq -c "
  SELECT count(*) FROM information_schema.tables
   WHERE table_schema = 'public' AND table_name LIKE 'ab\_%'" 2>/dev/null) || {
  echo "[db-drift] cannot connect to target database '$TARGET_DB'" >&2
  exit 2
}

if [ "${target_tables:-0}" -eq 0 ]; then
  say "[db-drift] '$TARGET_DB' has no ab_* tables yet — fresh database, nothing to drift"
  exit 0
fi

say "[db-drift] building reference database from schema.sql"
psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_ADMIN_DB" -q \
  -c "CREATE DATABASE $REF_DB" >/dev/null 2>&1 || { echo "[db-drift] cannot create $REF_DB" >&2; exit 2; }
if ! psql -v ON_ERROR_STOP=1 -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$REF_DB" \
     -q -f "$SCHEMA_FILE" >/tmp/"$REF_DB".apply 2>&1; then
  echo "[db-drift] schema.sql does not apply cleanly to a fresh database:" >&2
  tail -5 /tmp/"$REF_DB".apply >&2
  exit 2
fi

inventory "$REF_DB" >/tmp/"$REF_DB".ref
inventory "$TARGET_DB" >/tmp/"$REF_DB".target

missing=$(comm -23 /tmp/"$REF_DB".ref /tmp/"$REF_DB".target)
if [ -z "$missing" ]; then
  say "[db-drift] '$TARGET_DB' matches schema.sql ($(wc -l < /tmp/"$REF_DB".ref | tr -d ' ') columns)"
  exit 0
fi

count=$(printf '%s\n' "$missing" | wc -l | tr -d ' ')
echo "[db-drift] '$TARGET_DB' is missing $count column(s) that schema.sql declares:" >&2
printf '%s\n' "$missing" | sed 's/^/  - /' | head -30 >&2
[ "$count" -gt 30 ] && echo "  ... and $((count - 30)) more" >&2
cat >&2 <<EOF

schema.sql is all CREATE TABLE IF NOT EXISTS, so re-applying it cannot repair an
existing database — those columns will stay missing and surface later as confusing
runtime errors (a plugin import aborting with 25P02, a mapper selecting a column
that does not exist).

Recreate the database instead of reusing it.
EOF
exit 1
