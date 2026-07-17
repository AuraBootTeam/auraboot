ALTER TABLE ab_drt_policy_exec_log
    ADD COLUMN IF NOT EXISTS decision_trace_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS correlation_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_drt_exec_decision_trace
    ON ab_drt_policy_exec_log (tenant_id, decision_trace_id, executed_at DESC)
    WHERE decision_trace_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_drt_exec_correlation
    ON ab_drt_policy_exec_log (tenant_id, correlation_id, executed_at DESC)
    WHERE correlation_id IS NOT NULL;

COMMENT ON COLUMN ab_drt_policy_exec_log.decision_trace_id IS
    'Decision Runtime trace_id that caused this EventPolicy action execution';
COMMENT ON COLUMN ab_drt_policy_exec_log.correlation_id IS
    'EventPolicy run correlation id shared with linked decision evaluations';
