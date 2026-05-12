#!/bin/bash

# AuraBoot Database Reset Script
# This script drops and recreates the configured AuraBoot database.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
SCHEMA_FILE="$PROJECT_ROOT/platform/src/main/resources/database/schema.sql"

PG_HOST="${PG_HOST:-${PGHOST:-localhost}}"
PG_PORT="${PG_PORT:-${PGPORT:-5432}}"
PG_USER="${PG_USER:-${PGUSER:-${USER:-ghj}}}"
PG_DB="${PG_DB:-${PGDATABASE:-aura_boot}}"
PG_PASSWORD="${PG_PASSWORD:-${PGPASSWORD:-}}"

psql_run() {
    local db="$1"
    shift
    if [ -n "$PG_PASSWORD" ]; then
        PGPASSWORD="$PG_PASSWORD" psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$db" "$@"
    else
        psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$db" "$@"
    fi
}

quote_ident() {
    printf '"%s"' "$(printf '%s' "$1" | sed 's/"/""/g')"
}

DB_IDENT="$(quote_ident "$PG_DB")"

# shellcheck source=lib/multi-worktree-guard.sh
source "$SCRIPT_DIR/lib/multi-worktree-guard.sh"
aura_multi_worktree_guard "reset-db.sh"

echo "=== AuraBoot Database Reset ==="
echo ""
echo "Target: $PG_HOST:$PG_PORT/$PG_DB (user $PG_USER)"
echo ""

# Check if schema file exists
if [ ! -f "$SCHEMA_FILE" ]; then
    echo "Error: Schema file not found at $SCHEMA_FILE"
    exit 1
fi

# Confirm before proceeding
read -p "This will DELETE all data in '$PG_DB'. Continue? (y/N): " confirm
if [[ "$confirm" != "y" && "$confirm" != "Y" ]]; then
    echo "Aborted."
    exit 0
fi

echo ""
echo "Step 0: Ensuring database role 'auraboot' exists..."
psql_run postgres -c "DO \$\$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'auraboot') THEN CREATE ROLE auraboot WITH LOGIN SUPERUSER; RAISE NOTICE 'Role auraboot created'; ELSE RAISE NOTICE 'Role auraboot already exists'; END IF; END \$\$;" 2>/dev/null || true

echo "Step 1: Terminating existing connections to '$PG_DB'..."
psql_run postgres -v dbname="$PG_DB" -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = :'dbname' AND pid <> pg_backend_pid();" 2>/dev/null || true

echo "Step 2: Dropping database '$PG_DB'..."
psql_run postgres -c "DROP DATABASE IF EXISTS $DB_IDENT;"

echo "Step 3: Creating database '$PG_DB'..."
psql_run postgres -c "CREATE DATABASE $DB_IDENT;"

echo "Step 4: Initializing schema..."
psql_run "$PG_DB" -f "$SCHEMA_FILE"

echo ""
echo "Step 5: Migrating deprecated blockType aliases in ab_page_schema..."
psql_run "$PG_DB" -c "
UPDATE ab_page_schema
SET blocks = regexp_replace(
  regexp_replace(
    regexp_replace(
      regexp_replace(
        regexp_replace(
          blocks::text,
          '\"blockType\"\s*:\s*\"data-table\"', '\"blockType\": \"table\"', 'g'
        ),
        '\"blockType\"\s*:\s*\"filter-form\"', '\"blockType\": \"filters\"', 'g'
      ),
      '\"blockType\"\s*:\s*\"list-tabs\"', '\"blockType\": \"tabs\"', 'g'
    ),
    '\"blockType\"\s*:\s*\"toolbar-buttons\"', '\"blockType\": \"toolbar\"', 'g'
  ),
  '\"blockType\"\s*:\s*\"action\"', '\"blockType\": \"custom\"', 'g'
)::jsonb
WHERE blocks::text ~ '\"blockType\"\s*:\s*\"(data-table|filter-form|list-tabs|toolbar-buttons|action)\"';
" && echo "  Migration complete (idempotent — 0 rows updated is fine if no stale data)." || echo "  Migration skipped (table may not exist yet, schema init will create it fresh)."

echo ""
echo "=== Database reset complete! ==="
