-- P1 trace correlation (A-G1 / A-G2): give the audit tables and the self-built
-- AI-trace read model an OTel trace anchor so the two trace systems (OTel infra
-- tracing + self-built ab_ai_trace) and the business audit trail can be joined by
-- a single trace_id. Columns are populated by the seam (S0) once wired; until then
-- they are nullable and harmless. Additive + idempotent (IF NOT EXISTS).
--
-- Context: docs/backlog/2026-06-19-unified-telemetry-analytics-platform-architecture.md
--          §2.3 (correlation keys), §4.2 A-G1/A-G2.

-- Audit trail: anchor each audit row to the request's OTel trace/span.
ALTER TABLE ab_command_audit_log ADD COLUMN IF NOT EXISTS trace_id VARCHAR(36);
ALTER TABLE ab_command_audit_log ADD COLUMN IF NOT EXISTS span_id  VARCHAR(36);
ALTER TABLE ab_admin_event_log   ADD COLUMN IF NOT EXISTS trace_id VARCHAR(36);
ALTER TABLE ab_admin_event_log   ADD COLUMN IF NOT EXISTS span_id  VARCHAR(36);
ALTER TABLE ab_query_audit_log   ADD COLUMN IF NOT EXISTS trace_id VARCHAR(36);
ALTER TABLE ab_query_audit_log   ADD COLUMN IF NOT EXISTS span_id  VARCHAR(36);

COMMENT ON COLUMN ab_command_audit_log.trace_id IS 'OTel W3C traceId (32-hex) of the request; correlates audit -> distributed trace';
COMMENT ON COLUMN ab_admin_event_log.trace_id   IS 'OTel W3C traceId (32-hex) of the request; correlates audit -> distributed trace';
COMMENT ON COLUMN ab_query_audit_log.trace_id   IS 'OTel W3C traceId (32-hex) of the request; correlates audit -> distributed trace';

-- Self-built AI trace: keep the existing UUID trace_id as primary identity, but
-- also stamp the OTel traceId so /aurabot/traces can cross-link to Jaeger.
ALTER TABLE ab_ai_trace ADD COLUMN IF NOT EXISTS otel_trace_id VARCHAR(32);
COMMENT ON COLUMN ab_ai_trace.otel_trace_id IS 'OTel W3C traceId (32-hex) captured at turn start; bridges self-built UUID trace_id to the OTel trace';

-- Correlation lookup indexes (trace_id is high-selectivity; partial to skip nulls).
CREATE INDEX IF NOT EXISTS idx_ab_command_audit_log_trace_id ON ab_command_audit_log (trace_id) WHERE trace_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_ab_admin_event_log_trace_id   ON ab_admin_event_log   (trace_id) WHERE trace_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_ab_query_audit_log_trace_id   ON ab_query_audit_log   (trace_id) WHERE trace_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_ab_ai_trace_otel_trace_id     ON ab_ai_trace          (otel_trace_id) WHERE otel_trace_id IS NOT NULL;
