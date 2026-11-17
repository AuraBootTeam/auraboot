-- ============================================================================
-- Phase D.3 — child run cost / tokens reverse rollup columns on ab_agent_run
-- ============================================================================
--
-- Closes backlog item D.3 (docs/backlog/2026-05-06-acp-p0-p1-followups.md):
--   "父 run terminal 时,子 run 还在跑,其 cost 永远不会回灌父 run。
--    财务/配额账目从一开始就漏。"
--
-- Adds two nullable-default-0 counters on {@code ab_agent_run}:
--   - {@code child_aggregate_cost}    DECIMAL(10,6) DEFAULT 0
--   - {@code child_aggregate_tokens}  INTEGER       DEFAULT 0
--
-- {@code ParentJoinService} (already wired on {@code SessionEndedEvent} per T1
-- commit 6a716429) atomically increments these counters whenever a direct child
-- run reaches terminal — succeeded / cancelled / failed alike. The increment
-- is a single-statement {@code UPDATE ab_agent_run SET col = COALESCE(col,0) + ?}
-- and runs even when the parent has already reached its own terminal state, so
-- finance / quota accounting reconciles regardless of finish-order.
--
-- Direct-children only: no grandchild flattening. The full subtree cost can be
-- reconstructed at read time by recursively summing each level. Flattening
-- here would either double-count (child + grandchild both rolled into root) or
-- require a transaction-spanning aggregate that breaks the atomicity guarantee.
--
-- WHEN TO RUN:
--   - Production / shared-data environments only. Dev environments wipe data
--     via {@code reset-and-init.sh}, which re-creates the table from
--     {@code schema.sql} where the columns are already present.
--
-- HOW TO RUN:
--   psql "$DB_URL" -v ON_ERROR_STOP=1 \
--     -f migrations/2026-05-07-d3-agent-run-child-aggregate.sql
--
-- IDEMPOTENCY:
--   Both ALTERs use {@code ADD COLUMN IF NOT EXISTS}. Re-running the script
--   is a no-op once the columns exist.
--
-- BACK-COMPAT:
--   Existing rows get {@code 0} on first read because of the column DEFAULT.
--   No backfill is required — historical child-completion events are gone, and
--   no caller relies on a NULL signal versus a 0 signal (both mean "no child
--   has rolled up yet"). Listener code uses {@code COALESCE(col, 0) + ?} as a
--   defence-in-depth guard if a future migration ever drops the DEFAULT.
-- ============================================================================

ALTER TABLE ab_agent_run
    ADD COLUMN IF NOT EXISTS child_aggregate_cost DECIMAL(10,6) DEFAULT 0;

ALTER TABLE ab_agent_run
    ADD COLUMN IF NOT EXISTS child_aggregate_tokens INTEGER DEFAULT 0;

COMMENT ON COLUMN ab_agent_run.child_aggregate_cost IS
    'Backlog D.3: reverse rollup of direct-child run total_cost; '
    'incremented atomically by ParentJoinService on ChildRunCompletedEvent.';

COMMENT ON COLUMN ab_agent_run.child_aggregate_tokens IS
    'Backlog D.3: reverse rollup of direct-child run (input_tokens + '
    'output_tokens); incremented atomically by ParentJoinService on '
    'ChildRunCompletedEvent.';
