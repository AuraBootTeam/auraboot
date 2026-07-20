-- F8 (execution-architecture review, 2026-07-20): approval grants must be
-- consumable exactly once.
--
-- Approving a pending agent approval resumes the task as a NEW run, whose
-- idempotency key ({runId}:{toolCode}) missed the approved row — so the gate
-- minted another pending approval, forever, and the approved action never
-- executed (live-reproduced infinite approval loop).
--
-- The gate now looks up an APPROVED, unconsumed grant for (tenant, task, tool)
-- and claims it with a conditional UPDATE on this column, so a grant authorizes
-- exactly one execution and concurrent runs cannot both proceed.
ALTER TABLE ab_agent_approval ADD COLUMN IF NOT EXISTS consumed_at TIMESTAMP;

COMMENT ON COLUMN ab_agent_approval.consumed_at IS
    'When an approved grant was claimed by an executing run (F8 single-use guard); NULL = not yet consumed';

-- Partial index: the gate only ever scans approved+unconsumed rows.
CREATE INDEX IF NOT EXISTS idx_agent_approval_grant_lookup
    ON ab_agent_approval (tenant_id, task_id)
    WHERE approval_status = 'approved' AND consumed_at IS NULL;
