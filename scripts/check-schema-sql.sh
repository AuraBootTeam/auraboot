#!/usr/bin/env bash
#
# Validate platform/src/main/resources/database/schema.sql by applying it to a
# transient PostgreSQL database with ON_ERROR_STOP. Catches:
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
#   scripts/check-schema-sql.sh                    # local Postgres, auto-detect schema.sql
#   scripts/check-schema-sql.sh path/to/schema.sql
#   scripts/check-schema-sql.sh --local            # explicit local mode (default)
#   scripts/check-schema-sql.sh --docker           # legacy container mode
#
# Local mode connection:
#   PG_HOST/PGPORT/PG_USER/PG_PASSWORD (PGHOST/PGPORT/PGUSER/PGPASSWORD accepted)
#   PG_ADMIN_DB=postgres                            # admin DB used to create/drop temp DB
#   SCHEMA_CHECK_DB=aura_schema_sql_check_123       # optional temp DB name
#
# Exit codes:
#   0 — schema applies cleanly
#   1 — schema rejected by psql (look at last few lines of output)
#   2 — environment problem (psql/Postgres/docker missing, etc.)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
SCHEMA_FILE="$PROJECT_ROOT/platform/src/main/resources/database/schema.sql"
MODE="${SCHEMA_CHECK_MODE:-local}"

CLEANUP_KIND=""
LOCAL_PG_HOST=""
LOCAL_PG_PORT=""
LOCAL_PG_USER=""
LOCAL_PG_PASSWORD=""
LOCAL_PG_ADMIN_DB=""
LOCAL_CHECK_DB=""
LOCAL_LOG_FILE=""
DOCKER_CTR_NAME=""
DOCKER_LOG_FILE=""

cleanup() {
    case "$CLEANUP_KIND" in
        local)
            if [[ -n "$LOCAL_CHECK_DB" ]]; then
                PGPASSWORD="$LOCAL_PG_PASSWORD" psql \
                    -h "$LOCAL_PG_HOST" -p "$LOCAL_PG_PORT" -U "$LOCAL_PG_USER" -d "$LOCAL_PG_ADMIN_DB" \
                    -v ON_ERROR_STOP=0 \
                    -c "DROP DATABASE IF EXISTS \"$LOCAL_CHECK_DB\" WITH (FORCE);" >/dev/null 2>&1 || true
            fi
            [[ -z "$LOCAL_LOG_FILE" ]] || rm -f "$LOCAL_LOG_FILE"
            ;;
        docker)
            [[ -z "$DOCKER_CTR_NAME" ]] || docker rm -f "$DOCKER_CTR_NAME" >/dev/null 2>&1 || true
            [[ -z "$DOCKER_LOG_FILE" ]] || rm -f "$DOCKER_LOG_FILE"
            ;;
    esac
}
trap cleanup EXIT

while [[ $# -gt 0 ]]; do
    case "$1" in
        --local)
            MODE="local"
            shift
            ;;
        --docker)
            MODE="docker"
            shift
            ;;
        -h|--help)
            sed -n '2,32p' "${BASH_SOURCE[0]}"
            exit 0
            ;;
        -*)
            echo "❌ Unknown option: $1" >&2
            exit 2
            ;;
        *)
            SCHEMA_FILE="$1"
            shift
            ;;
    esac
done

if [[ ! -f "$SCHEMA_FILE" ]]; then
    echo "❌ Schema file not found: $SCHEMA_FILE" >&2
    exit 2
fi

# Pre-flight: count CREATE TABLE so a half-cooked schema (where psql stops on
# error before reaching the end) gets caught even if it's syntactically valid
# up to the error point.
expected_tables=$(grep -c "^CREATE TABLE" "$SCHEMA_FILE" || true)

print_header() {
    echo "▶ schema.sql syntax check"
    echo "  file:  $SCHEMA_FILE ($(wc -l < "$SCHEMA_FILE" | tr -d ' ') lines)"
    echo "  mode:  $MODE"
    echo "  expected CREATE TABLE statements: $expected_tables"
}

assert_table_count() {
    local actual_tables="$1"
    echo "  actually created tables: $actual_tables"

    if [[ "$actual_tables" -lt $((expected_tables - 5)) ]]; then
        echo "❌ Created table count ($actual_tables) is much lower than expected ($expected_tables)" >&2
        echo "   suggests psql stopped early at a silent error" >&2
        exit 1
    fi

    echo "✅ schema.sql applies cleanly ($actual_tables tables created)"
}

run_local() {
    command -v psql >/dev/null 2>&1 || {
        echo "❌ psql is required for local mode" >&2
        exit 2
    }

    CLEANUP_KIND="local"
    LOCAL_PG_HOST="${PG_HOST:-${PGHOST:-localhost}}"
    LOCAL_PG_PORT="${PG_PORT:-${PGPORT:-5432}}"
    LOCAL_PG_USER="${PG_USER:-${PGUSER:-auraboot}}"
    LOCAL_PG_PASSWORD="${PG_PASSWORD:-${PGPASSWORD:-}}"
    LOCAL_PG_ADMIN_DB="${PG_ADMIN_DB:-postgres}"
    LOCAL_CHECK_DB="${SCHEMA_CHECK_DB:-aura_schema_sql_check_$$}"
    LOCAL_LOG_FILE="/tmp/schema-check-$$.log"

    if [[ ! "$LOCAL_CHECK_DB" =~ ^[A-Za-z0-9_]+$ ]]; then
        echo "❌ SCHEMA_CHECK_DB must contain only letters, digits, and underscores: $LOCAL_CHECK_DB" >&2
        exit 2
    fi

    psql_admin() {
        PGPASSWORD="$LOCAL_PG_PASSWORD" psql \
            -h "$LOCAL_PG_HOST" -p "$LOCAL_PG_PORT" -U "$LOCAL_PG_USER" -d "$LOCAL_PG_ADMIN_DB" "$@"
    }
    psql_check() {
        PGPASSWORD="$LOCAL_PG_PASSWORD" psql \
            -h "$LOCAL_PG_HOST" -p "$LOCAL_PG_PORT" -U "$LOCAL_PG_USER" -d "$LOCAL_CHECK_DB" "$@"
    }

    print_header
    echo "  postgres: $LOCAL_PG_USER@$LOCAL_PG_HOST:$LOCAL_PG_PORT/$LOCAL_PG_ADMIN_DB"
    echo "  temp db:  $LOCAL_CHECK_DB"

    if ! psql_admin -tAc "SELECT 1" >/dev/null 2>&1; then
        echo "❌ cannot reach PostgreSQL admin DB: $LOCAL_PG_USER@$LOCAL_PG_HOST:$LOCAL_PG_PORT/$LOCAL_PG_ADMIN_DB" >&2
        echo "   set PG_HOST/PG_PORT/PG_USER/PG_PASSWORD or PG_ADMIN_DB" >&2
        exit 2
    fi

    cleanup
    psql_admin -v ON_ERROR_STOP=1 -c "CREATE DATABASE \"$LOCAL_CHECK_DB\";" >/dev/null

    if ! psql_check -v ON_ERROR_STOP=1 -f "$SCHEMA_FILE" > "$LOCAL_LOG_FILE" 2>&1; then
        echo "❌ psql rejected schema.sql:" >&2
        tail -20 "$LOCAL_LOG_FILE" >&2
        exit 1
    fi

    local actual_tables
    actual_tables="$(psql_check -tAc "SELECT count(*) FROM information_schema.tables WHERE table_schema='public'")"
    assert_table_count "$actual_tables"
}

run_docker() {
    command -v docker >/dev/null 2>&1 || {
        echo "❌ docker is required for --docker mode" >&2
        exit 2
    }

    # pgvector/pgvector:pg16 ships pg_trgm + pgcrypto + vector, matching production.
    local image actual_tables
    image="${SCHEMA_CHECK_IMAGE:-pgvector/pgvector:pg16}"
    CLEANUP_KIND="docker"
    DOCKER_CTR_NAME="schema-check-$$"
    DOCKER_LOG_FILE="/tmp/schema-check-$$.log"

    print_header
    echo "  image: $image"

    docker run -d --name "$DOCKER_CTR_NAME" \
        -e POSTGRES_PASSWORD=check \
        -e POSTGRES_USER=check \
        -e POSTGRES_DB=schema_check \
        "$image" >/dev/null

    for _ in $(seq 1 30); do
        if docker exec "$DOCKER_CTR_NAME" pg_isready -U check >/dev/null 2>&1; then
            break
        fi
        sleep 1
    done

    docker cp "$SCHEMA_FILE" "$DOCKER_CTR_NAME:/tmp/schema.sql" >/dev/null

    if ! docker exec "$DOCKER_CTR_NAME" psql -U check -d schema_check \
            -v ON_ERROR_STOP=1 -f /tmp/schema.sql > "$DOCKER_LOG_FILE" 2>&1; then
        echo "❌ psql rejected schema.sql:" >&2
        tail -20 "$DOCKER_LOG_FILE" >&2
        exit 1
    fi

    actual_tables="$(docker exec "$DOCKER_CTR_NAME" psql -U check -d schema_check -tAc \
        "SELECT count(*) FROM information_schema.tables WHERE table_schema='public'")"
    assert_table_count "$actual_tables"
}

case "$MODE" in
    local) run_local ;;
    docker) run_docker ;;
    *)
        echo "❌ Unsupported SCHEMA_CHECK_MODE: $MODE (expected local|docker)" >&2
        exit 2
        ;;
esac
