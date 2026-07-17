ALTER TABLE ab_drt_policy_exec_log
    ADD COLUMN IF NOT EXISTS failure_strategy TEXT,
    ADD COLUMN IF NOT EXISTS action_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS context_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS max_attempts INTEGER NOT NULL DEFAULT 3,
    ADD COLUMN IF NOT EXISTS next_retry_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_retry_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS dead_lettered_at TIMESTAMPTZ;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_drt_exec_retry_attempts'
    ) THEN
        ALTER TABLE ab_drt_policy_exec_log
            ADD CONSTRAINT chk_drt_exec_retry_attempts
            CHECK (attempt_count >= 0 AND max_attempts > 0 AND max_attempts <= 20);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_drt_exec_retry_ready
    ON ab_drt_policy_exec_log (next_retry_at, tenant_id, executed_at)
    WHERE status = 'RETRY_PENDING';

CREATE INDEX IF NOT EXISTS idx_drt_exec_dead_letter
    ON ab_drt_policy_exec_log (tenant_id, dead_lettered_at DESC, executed_at DESC)
    WHERE status = 'DEAD_LETTER';

COMMENT ON COLUMN ab_drt_policy_exec_log.failure_strategy IS
    'Failure strategy used when the action execution row was recorded';
COMMENT ON COLUMN ab_drt_policy_exec_log.action_payload IS
    'Retry envelope: resolved action target/order/payload captured before execution';
COMMENT ON COLUMN ab_drt_policy_exec_log.context_payload IS
    'Retry envelope: decision context scopes captured before execution';
COMMENT ON COLUMN ab_drt_policy_exec_log.attempt_count IS
    'Number of execution attempts recorded for this idempotency key';
COMMENT ON COLUMN ab_drt_policy_exec_log.max_attempts IS
    'Maximum attempts before RETRY_PENDING is exhausted into DEAD_LETTER';
COMMENT ON COLUMN ab_drt_policy_exec_log.next_retry_at IS
    'When RETRY_ASYNC worker may next execute this action';
COMMENT ON COLUMN ab_drt_policy_exec_log.last_retry_at IS
    'Last time the action was attempted';
COMMENT ON COLUMN ab_drt_policy_exec_log.dead_lettered_at IS
    'When retry exhaustion or explicit DEAD_LETTER routing moved this row to the dead-letter queue';
