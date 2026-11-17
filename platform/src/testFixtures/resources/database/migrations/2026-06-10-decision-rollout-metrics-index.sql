-- Decision Runtime rollout metrics aggregation support.

CREATE INDEX IF NOT EXISTS idx_drt_log_rollout_arm_window
    ON ab_drt_log (tenant_id, rollout_policy_pid, rollout_arm, created_at DESC)
    WHERE rollout_policy_pid IS NOT NULL;
