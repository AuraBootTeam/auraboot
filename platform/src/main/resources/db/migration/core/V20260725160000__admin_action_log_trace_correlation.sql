-- The last audit surface with no OTel anchor. SoT 121 §6 listed three tables missing from
-- the unified troubleshooting entry point; command/query/admin-event got columns in
-- V20260620000000, ab_permission_audit_log in V20260725150000, and this is the remainder.
--
-- ab_admin_action_log answers "which admin HTTP request did this", including the denials
-- (AdminRoleInterceptor writes a row for rejected requests too). Without a trace column an
-- admin request that failed could not be reached from the trace id its own response carried.
--
-- Note on how it gets populated, which differs from every other audit writer here:
-- AdminAuditService.logAdminAction is @Async("adminAuditExecutor"), and unlike taskExecutor /
-- eventTaskExecutor / exportTaskExecutor / asyncTaskExecutor that pool has NO TaskDecorator.
-- So neither the OTel context nor MetaContext reaches it, and the MetaContext-snapshot
-- fallback that works for the permission audit cannot work here. The trace id is captured by
-- AdminRoleInterceptor on the request thread and passed in as a parameter instead.

ALTER TABLE ab_admin_action_log ADD COLUMN IF NOT EXISTS trace_id VARCHAR(36);
ALTER TABLE ab_admin_action_log ADD COLUMN IF NOT EXISTS span_id  VARCHAR(36);

COMMENT ON COLUMN ab_admin_action_log.trace_id IS
    'OTel W3C traceId (32-hex) of the request, captured on the request thread by AdminRoleInterceptor and passed to the @Async writer';
COMMENT ON COLUMN ab_admin_action_log.span_id IS
    'OTel spanId of the request, captured on the request thread';

CREATE INDEX IF NOT EXISTS idx_ab_admin_action_log_trace_id
    ON ab_admin_action_log (trace_id) WHERE trace_id IS NOT NULL;
