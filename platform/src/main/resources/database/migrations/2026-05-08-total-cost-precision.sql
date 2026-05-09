-- ============================================================================
-- 2026-05-08 — ab_agent_run.total_cost precision drift repair
-- ============================================================================
--
-- schema.sql declares ab_agent_run.total_cost as DECIMAL(10,6), matching the
-- D.3 child_aggregate_cost column added in 2026-05-07-d3-agent-run-child-aggregate.
-- However, environments seeded before that migration still carry the legacy
-- DECIMAL(10,2) precision, which silently rounds sub-cent costs (the LLM cost
-- reporting golden tests were observing 0.00 instead of 0.012345 / 0.005000 /
-- 0.075000 / 0.000123 etc.).
--
-- This migration brings live databases in line with schema.sql by widening the
-- column to DECIMAL(10,6). It is safe to apply repeatedly because PostgreSQL
-- treats ALTER COLUMN TYPE on a no-op precision change as a metadata-only
-- update (and a real widening preserves all existing data — no value can lose
-- precision when going from (10,2) to (10,6)).
--
-- Why a migration rather than relying on schema.sql alone:
--   - schema.sql is only re-applied on a full reset-db.sh run; long-lived dev
--     and CI databases won't see the new precision until this DDL ships.
--   - Tests that read back BigDecimal-equal values (cost concurrency, child
--     cost rollup, ParentJoinService.joinChildRun) depend on the full 6-digit
--     scale being preserved.
-- ============================================================================

ALTER TABLE ab_agent_run
    ALTER COLUMN total_cost TYPE DECIMAL(10,6);
