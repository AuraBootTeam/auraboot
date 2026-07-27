ALTER TABLE ab_permission_audit_log
    ADD COLUMN IF NOT EXISTS trace_id VARCHAR(36);
ALTER TABLE ab_permission_audit_log
    ADD COLUMN IF NOT EXISTS span_id VARCHAR(36);

COMMENT ON COLUMN ab_permission_audit_log.trace_id IS
    'OTel W3C trace id of the request; distinct from Rule Center ids in evaluation_trace';
COMMENT ON COLUMN ab_permission_audit_log.span_id IS
    'OTel span id when available to the asynchronous audit writer';

CREATE INDEX IF NOT EXISTS idx_ab_permission_audit_log_trace_id
    ON ab_permission_audit_log (trace_id)
    WHERE trace_id IS NOT NULL;
