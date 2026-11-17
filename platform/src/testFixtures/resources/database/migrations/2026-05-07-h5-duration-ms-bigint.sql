-- ============================================================================
-- H.5 — align ab_agent_run.duration_ms to BIGINT (matches schema.sql)
-- ============================================================================
--
-- Closes 2026-05-06 ACP follow-up §H.5. schema.sql:4770 declares
-- {@code duration_ms BIGINT}, but pre-existing dev / shared databases were
-- created when the column was {@code INTEGER}. PostgreSQL JDBC therefore
-- returns {@code Integer} for non-null values, and code paths casting to
-- {@code Long} via {@code (Long) rs.getObject("duration_ms")} throw
-- {@code ClassCastException} for any populated row. The application-side
-- fix (commit b76292d3) widened the cast to {@code (Number) ... .longValue()}
-- so reads are robust to either column type. This migration brings the
-- column type itself in line with schema.sql so future code can rely on
-- the declared type.
--
-- WHEN TO RUN:
--   - Production / shared-data environments where the column was created
--     before the schema.sql declaration changed to BIGINT.
--   - Dev environments wipe data via {@code reset-and-init.sh}, which
--     re-creates the table with the current schema.sql declaration; per
--     AGENTS.md "开发阶段声明", dev does not run migrations.
--
-- HOW TO RUN:
--   psql "$DB_URL" -v ON_ERROR_STOP=1 -f migrations/2026-05-07-h5-duration-ms-bigint.sql
--
-- IDEMPOTENCY:
--   {@code ALTER COLUMN ... TYPE BIGINT} is a no-op if the column is
--   already BIGINT, so re-running this script is safe. PostgreSQL still
--   takes a brief ACCESS EXCLUSIVE lock during the no-op check; schedule
--   accordingly on hot tables.
--
-- LOCK / DOWNTIME EXPECTATION:
--   On a populated table with INTEGER → BIGINT, PostgreSQL rewrites the
--   table (BIGINT is wider). Plan a maintenance window proportional to
--   ab_agent_run row count. On already-BIGINT columns it is metadata-only.
-- ============================================================================

-- Step 1: Audit — current column type before the migration.
SELECT
    table_schema,
    table_name,
    column_name,
    data_type,
    udt_name
FROM information_schema.columns
WHERE table_name = 'ab_agent_run' AND column_name = 'duration_ms';

-- Step 2: Idempotent type-widening ALTER.
ALTER TABLE ab_agent_run
    ALTER COLUMN duration_ms TYPE BIGINT
    USING duration_ms::BIGINT;

-- Step 3: Verify — column type after migration. Expect {@code bigint}.
SELECT
    table_schema,
    table_name,
    column_name,
    data_type,
    udt_name
FROM information_schema.columns
WHERE table_name = 'ab_agent_run' AND column_name = 'duration_ms';
