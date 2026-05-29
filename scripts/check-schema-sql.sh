#!/usr/bin/env bash
#
# Validate platform/src/main/resources/database/schema.sql by running it through
# psql against a transient PostgreSQL container with ON_ERROR_STOP. Catches:
#
# - unterminated dollar-quoted strings (PL/pgSQL DO $$ ... END $$; mismatches)
# - syntax errors in DDL statements
# - extension dependencies (pg_trgm, pgcrypto, vector)
# - DDL ordering issues (FK to non-existent table, etc.)
#
# This script exists because main was broken for ~12 hours on 2026-05-28/29
# after a PR appended a migration into a DO $$ block without closing it. The
# canonical schema.sql is the entry point /docker-entrypoint-initdb.d uses on
# every fresh dev stack, so a syntax error there cascades to every newcomer.
#
# Reflection trail: auraboot/ida/docs/25 §3.1 + ida/docs/26-schema-sql-governance.md
#
# Usage:
#   scripts/check-schema-sql.sh                    # auto-detect schema.sql
#   scripts/check-schema-sql.sh path/to/schema.sql
#
# Exit codes:
#   0 — schema applies cleanly
#   1 — schema rejected by psql (look at last few lines of output)
#   2 — environment problem (docker missing, etc.)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
SCHEMA_FILE="${1:-$PROJECT_ROOT/platform/src/main/resources/database/schema.sql}"

if [[ ! -f "$SCHEMA_FILE" ]]; then
    echo "❌ Schema file not found: $SCHEMA_FILE" >&2
    exit 2
fi

if ! command -v docker >/dev/null 2>&1; then
    echo "❌ docker is required (apt-get install docker.io or brew install docker)" >&2
    exit 2
fi

# pgvector/pgvector:pg16 ships pg_trgm + pgcrypto + vector, matching production.
IMAGE="${SCHEMA_CHECK_IMAGE:-pgvector/pgvector:pg16}"
CTR_NAME="schema-check-$$"

cleanup() {
    docker rm -f "$CTR_NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "▶ schema.sql syntax check"
echo "  file:  $SCHEMA_FILE ($(wc -l < "$SCHEMA_FILE" | tr -d ' ') lines)"
echo "  image: $IMAGE"

# Pre-flight: count CREATE TABLE so a half-cooked schema (where psql stops on
# error before reaching the end) gets caught even if it's syntactically valid
# up to the error point.
expected_tables=$(grep -c "^CREATE TABLE" "$SCHEMA_FILE" || true)
echo "  expected CREATE TABLE statements: $expected_tables"

docker run -d --name "$CTR_NAME" \
    -e POSTGRES_PASSWORD=check \
    -e POSTGRES_USER=check \
    -e POSTGRES_DB=schema_check \
    "$IMAGE" >/dev/null

# Wait for ready
for _ in $(seq 1 30); do
    if docker exec "$CTR_NAME" pg_isready -U check >/dev/null 2>&1; then
        break
    fi
    sleep 1
done

docker cp "$SCHEMA_FILE" "$CTR_NAME:/tmp/schema.sql" >/dev/null

# ON_ERROR_STOP=1 turns the first psql error into a non-zero exit.
if ! docker exec "$CTR_NAME" psql -U check -d schema_check \
        -v ON_ERROR_STOP=1 -f /tmp/schema.sql > /tmp/schema-check-$$.log 2>&1; then
    echo "❌ psql rejected schema.sql:"
    tail -20 /tmp/schema-check-$$.log >&2
    rm -f /tmp/schema-check-$$.log
    exit 1
fi
rm -f /tmp/schema-check-$$.log

# Post-flight: count actually created tables
actual_tables=$(docker exec "$CTR_NAME" psql -U check -d schema_check -tAc \
    "SELECT count(*) FROM information_schema.tables WHERE table_schema='public'")

echo "  actually created tables: $actual_tables"

if [[ "$actual_tables" -lt $((expected_tables - 5)) ]]; then
    echo "❌ Created table count ($actual_tables) is much lower than expected ($expected_tables)" >&2
    echo "   suggests psql stopped early at a silent error" >&2
    exit 1
fi

echo "✅ schema.sql applies cleanly ($actual_tables tables created)"
