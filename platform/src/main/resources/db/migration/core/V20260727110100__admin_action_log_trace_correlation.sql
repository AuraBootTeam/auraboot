ALTER TABLE ab_admin_action_log
    ADD COLUMN IF NOT EXISTS trace_id VARCHAR(36);
ALTER TABLE ab_admin_action_log
    ADD COLUMN IF NOT EXISTS span_id VARCHAR(36);

COMMENT ON COLUMN ab_admin_action_log.trace_id IS
    'OTel W3C trace id captured on the request thread before the async audit hop';
COMMENT ON COLUMN ab_admin_action_log.span_id IS
    'OTel span id captured on the request thread before the async audit hop';

CREATE INDEX IF NOT EXISTS idx_ab_admin_action_log_trace_id
    ON ab_admin_action_log (trace_id)
    WHERE trace_id IS NOT NULL;
