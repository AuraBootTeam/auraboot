#!/bin/bash

# AuraBoot Database Reset Script
# This script drops and recreates the aura_boot database

set -e

# ─── Multi-worktree pre-flight guard ────────────────────────────────────
# Refuse to run when ≥ 2 git worktrees of this repo are active. Triggered
# by the 2026-05-07 env-layering DB pollution incident: feat/env-layering-poc
# worktree ran reset-db.sh against the shared `aura_boot` host DB, rebuilt
# the schema with env_id NOT NULL while the host backend was still running
# with a stale m2 jar (no envId field) → 104 POST /api/pages failures.
#
# Multi-worktree must use the isolated docker stack workflow (each worktree
# gets its own COMPOSE_PROJECT_NAME=auraboot-<slug> stack with its own
# postgres / backend / vite / BFF / redis on dedicated ports). The escape
# hatch FORCE_HOST=1 lets the operator override when other worktrees are
# known dormant; each override is logged to ~/.aura/host-override.log so
# the owner can audit which scenarios depend on it.
#
# Spec: docs/plans/2026-05/2026-05-07-docker-per-worktree-isolation-design.md
# §3.5 (P0 #5).
ACTIVE_WORKTREES=$(git worktree list 2>/dev/null | wc -l | tr -d ' ')
if [ "${ACTIVE_WORKTREES:-0}" -ge 2 ]; then
    echo "ERROR: detected ${ACTIVE_WORKTREES} active worktrees on this repo." >&2
    echo "  Multi-worktree mode requires isolated docker stack." >&2
    echo "  Run: ./scripts/dev/start-isolated.sh && reset uses your isolated DB." >&2
    echo "  Override (only if other worktrees are dormant): FORCE_HOST=1 ./scripts/reset-db.sh" >&2
    if [ "${FORCE_HOST:-}" != "1" ]; then
        exit 1
    fi
    # Audit log
    mkdir -p "$HOME/.aura"
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) reset-db.sh FORCE_HOST=1 in $(pwd) [$(git rev-parse --abbrev-ref HEAD)] worktrees=${ACTIVE_WORKTREES}" >> "$HOME/.aura/host-override.log"
    echo "WARN: FORCE_HOST=1 used. Logged to ~/.aura/host-override.log" >&2
fi

DB_NAME="aura_boot"
DB_USER="ghj"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
SCHEMA_FILE="$PROJECT_ROOT/platform/src/main/resources/database/schema.sql"

echo "=== AuraBoot Database Reset ==="
echo ""

# Check if schema file exists
if [ ! -f "$SCHEMA_FILE" ]; then
    echo "Error: Schema file not found at $SCHEMA_FILE"
    exit 1
fi

# Confirm before proceeding
read -p "This will DELETE all data in '$DB_NAME'. Continue? (y/N): " confirm
if [[ "$confirm" != "y" && "$confirm" != "Y" ]]; then
    echo "Aborted."
    exit 0
fi

echo ""
echo "Step 0: Ensuring database role 'auraboot' exists..."
psql -d postgres -c "DO \$\$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'auraboot') THEN CREATE ROLE auraboot WITH LOGIN SUPERUSER; RAISE NOTICE 'Role auraboot created'; ELSE RAISE NOTICE 'Role auraboot already exists'; END IF; END \$\$;" 2>/dev/null || true

echo "Step 1: Terminating existing connections to '$DB_NAME'..."
psql -d postgres -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$DB_NAME' AND pid <> pg_backend_pid();" 2>/dev/null || true

echo "Step 2: Dropping database '$DB_NAME'..."
psql -d postgres -c "DROP DATABASE IF EXISTS $DB_NAME;"

echo "Step 3: Creating database '$DB_NAME'..."
psql -d postgres -c "CREATE DATABASE $DB_NAME;"

echo "Step 4: Initializing schema..."
psql -d "$DB_NAME" -f "$SCHEMA_FILE"

echo ""
echo "Step 5: Migrating deprecated blockType aliases in ab_page_schema..."
psql -d "$DB_NAME" -c "
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
