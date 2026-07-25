-- ab_permission_audit_log is the busiest audit table in the product — 1017 rows in the
-- shared database while ab_admin_event_log had 0 — and it was the one audit surface with no
-- OTel anchor at all. It is also the table you need when the question is "why was this
-- refused", which is the most common thing anyone brings to a troubleshooting console.
--
-- The result: pasting a trace id into the eagle-eye console could never surface a
-- permission denial, so the single richest source of "why did this fail" was invisible to
-- the tool built for exactly that question. SoT 121 §6 recorded it as a known gap.
--
-- Additive, idempotent, nullable — same shape as V20260620000000 did for the other three.
--
-- Note the name collision this deliberately avoids: PermissionAuditLogMapper already has a
-- findByTraceId, and it searches evaluation_trace JSONB for a Rule Center *ruleTraceId*.
-- That is a different identifier from the OTel W3C traceId stored here. Both are kept, and
-- the new lookup is named findByOtelTraceId so the two can never be silently swapped.

ALTER TABLE ab_permission_audit_log ADD COLUMN IF NOT EXISTS trace_id VARCHAR(36);
ALTER TABLE ab_permission_audit_log ADD COLUMN IF NOT EXISTS span_id  VARCHAR(36);

COMMENT ON COLUMN ab_permission_audit_log.trace_id IS
    'OTel W3C traceId (32-hex) of the request; correlates a DENY to the distributed trace. Distinct from the Rule Center ruleTraceId inside evaluation_trace.';
COMMENT ON COLUMN ab_permission_audit_log.span_id IS
    'OTel spanId of the request; NULL when the row was written on an @Async thread, where only the trace id survives the hop.';

CREATE INDEX IF NOT EXISTS idx_ab_permission_audit_log_trace_id
    ON ab_permission_audit_log (trace_id) WHERE trace_id IS NOT NULL;
