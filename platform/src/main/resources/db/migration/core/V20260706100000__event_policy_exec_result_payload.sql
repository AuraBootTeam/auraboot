-- Store structured per-action result evidence for EventPolicy execution logs.
ALTER TABLE ab_drt_policy_exec_log
    ADD COLUMN IF NOT EXISTS result_payload JSONB NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN ab_drt_policy_exec_log.result_payload IS
    'Structured ActionHandler result payload for product trace UI, e.g. sentCount, processInstanceId, updatedFields';
