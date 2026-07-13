#!/usr/bin/env bash
# Fail when database/schema.sql and db/migration/** disagree about a CHECK constraint.
#
# WHY THIS EXISTS
# ---------------
# The platform keeps its schema in three places: the Flyway migrations (the source of
# truth), db/snapshots/schema-current.sql (generated), and database/schema.sql (a hand-
# maintained mirror that golden and fresh stacks load DIRECTLY — they never run Flyway).
#
# Widen a CHECK constraint in a migration, forget the mirror, and every existing gate stays
# green: flyway-validate passes, check-schema-drift passes (it compares Flyway to the
# snapshot, and the snapshot is generated from Flyway), check-schema-sql passes (it only
# proves schema.sql *applies*), unit tests pass, and integration tests pass because they run
# on a Flyway-built database. The failure surfaces much later, on a fresh golden stack, as a
# CHECK violation on the first real INSERT — far from the change that caused it.
#
# This has bitten the platform before (chk_doc_source, 2026-07) and it is invisible to every
# other check we run, so it gets its own.
#
# WHAT IT DOES
# ------------
# Builds two throwaway databases — one from the migrations, one from database/schema.sql —
# and diffs their CHECK constraints for the tables both define. Tables that exist in only one
# (ab_platform_release is Flyway-only: it records deployments and fresh stacks have none) are
# not compared; this is about constraints drifting apart, not about table inventory.
#
# Usage: scripts/db/check-schema-sql-constraint-drift.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/db/flyway-common.sh
source "$SCRIPT_DIR/flyway-common.sh"

SCHEMA_SQL="$AURA_CORE_ROOT/platform/src/main/resources/database/schema.sql"
FLYWAY_DB="aura_cons_flyway_$$"
MIRROR_DB="aura_cons_mirror_$$"

_psql_admin() { PGPASSWORD="$PG_PASSWORD" psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d postgres "$@"; }
cleanup() {
  _psql_admin -c "DROP DATABASE IF EXISTS $FLYWAY_DB WITH (FORCE);" >/dev/null 2>&1 || true
  _psql_admin -c "DROP DATABASE IF EXISTS $MIRROR_DB WITH (FORCE);" >/dev/null 2>&1 || true
  rm -f /tmp/"$FLYWAY_DB".cons /tmp/"$MIRROR_DB".cons
}
trap cleanup EXIT

# Constraints, per table, for tables the two builds have in common.
CONSTRAINTS_SQL="
SELECT t.relname || ' | ' || c.conname || ' | ' || pg_get_constraintdef(c.oid)
FROM pg_constraint c
JOIN pg_class t ON c.conrelid = t.oid
JOIN pg_namespace n ON t.relnamespace = n.oid
WHERE c.contype = 'c' AND n.nspname = 'public'
ORDER BY 1;
"

echo "[constraint-drift] building '$FLYWAY_DB' from db/migration/**" >&2
_psql_admin -c "CREATE DATABASE $FLYWAY_DB;" >/dev/null
PG_DB="$FLYWAY_DB" "$SCRIPT_DIR/flyway-migrate.sh" >/dev/null 2>&1

echo "[constraint-drift] building '$MIRROR_DB' from database/schema.sql" >&2
_psql_admin -c "CREATE DATABASE $MIRROR_DB;" >/dev/null
PGPASSWORD="$PG_PASSWORD" psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$MIRROR_DB" \
  -q -v ON_ERROR_STOP=1 -f "$SCHEMA_SQL" >/dev/null

_dump_constraints() {
  PGPASSWORD="$PG_PASSWORD" psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$1" -tAc "$CONSTRAINTS_SQL"
}
_tables() {
  PGPASSWORD="$PG_PASSWORD" psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$1" -tAc \
    "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY 1;"
}

# Only compare tables both builds define — a table missing entirely from the mirror is a
# different (and deliberate) thing than a constraint that drifted.
COMMON_TABLES=$(comm -12 <(_tables "$FLYWAY_DB") <(_tables "$MIRROR_DB"))
_filter_common() {
  while IFS= read -r line; do
    table="${line%% | *}"
    if grep -qxF "$table" <<<"$COMMON_TABLES"; then echo "$line"; fi
  done
}

_dump_constraints "$FLYWAY_DB" | _filter_common > /tmp/"$FLYWAY_DB".cons
_dump_constraints "$MIRROR_DB" | _filter_common > /tmp/"$MIRROR_DB".cons

if diff -u /tmp/"$FLYWAY_DB".cons /tmp/"$MIRROR_DB".cons > /tmp/constraint-drift.diff; then
  echo "✅ database/schema.sql CHECK constraints match db/migration/** ($(wc -l < /tmp/"$FLYWAY_DB".cons | tr -d ' ') constraints compared)"
  exit 0
fi

echo "" >&2
echo "❌ database/schema.sql has drifted from db/migration/**" >&2
echo "" >&2
echo "   '-' = in the migrations but NOT in database/schema.sql (you forgot the mirror)" >&2
echo "   '+' = in database/schema.sql but NOT in the migrations (mirror edited by hand)" >&2
echo "" >&2
grep -E '^[-+][^-+]' /tmp/constraint-drift.diff >&2
echo "" >&2
echo "   Fix database/schema.sql to match. Golden and fresh stacks load it directly and never" >&2
echo "   run Flyway, so this drift only surfaces as a CHECK violation on a real INSERT." >&2
exit 1
