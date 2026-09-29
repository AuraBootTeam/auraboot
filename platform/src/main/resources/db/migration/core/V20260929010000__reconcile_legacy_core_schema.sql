-- Reconcile long-lived databases created before historical core migrations became immutable.
--
-- This migration is deliberately additive/idempotent:
--   * fresh databases already have these columns and indexes, so it is a no-op;
--   * legacy databases receive structures that were added to already-applied migrations;
--   * organization columns are aligned with the canonical fresh-schema contract.

ALTER TABLE ab_behavior_quarantine
    ADD COLUMN IF NOT EXISTS replay_status VARCHAR(24) NOT NULL DEFAULT 'pending',
    ADD COLUMN IF NOT EXISTS replay_detail TEXT,
    ADD COLUMN IF NOT EXISTS replayed_behavior_event_id BIGINT,
    ADD COLUMN IF NOT EXISTS replayed_at TIMESTAMPTZ;

COMMENT ON COLUMN ab_behavior_quarantine.replay_status IS
    'Replay status: pending|replayed|duplicate|failed';
COMMENT ON COLUMN ab_behavior_quarantine.replayed_behavior_event_id IS
    'ab_behavior_event.id produced or matched by replay';

CREATE INDEX IF NOT EXISTS idx_ab_behavior_quarantine_tenant_replay
    ON ab_behavior_quarantine (tenant_id, replay_status, quarantined_at);
CREATE INDEX IF NOT EXISTS idx_ab_behavior_quarantine_retention
    ON ab_behavior_quarantine (quarantined_at, id);

ALTER TABLE ab_query_audit_log
    ADD COLUMN IF NOT EXISTS trace_id VARCHAR(36),
    ADD COLUMN IF NOT EXISTS span_id VARCHAR(36);

COMMENT ON COLUMN ab_query_audit_log.trace_id IS
    'OTel W3C traceId (32-hex) of the request; correlates audit -> distributed trace';

CREATE INDEX IF NOT EXISTS idx_ab_query_audit_log_trace_id
    ON ab_query_audit_log (trace_id)
    WHERE trace_id IS NOT NULL;

ALTER TABLE mt_org_department
    ALTER COLUMN org_dept_code TYPE VARCHAR(100),
    ALTER COLUMN org_dept_name TYPE VARCHAR(200),
    ALTER COLUMN org_dept_name SET NOT NULL,
    ALTER COLUMN org_dept_parent_id TYPE VARCHAR(26),
    ALTER COLUMN org_dept_manager_id TYPE VARCHAR(26),
    ALTER COLUMN org_dept_status TYPE VARCHAR(50);

ALTER TABLE mt_org_employee
    ALTER COLUMN org_emp_name TYPE VARCHAR(200),
    ALTER COLUMN org_emp_name SET NOT NULL,
    ALTER COLUMN org_emp_email TYPE VARCHAR(255),
    ALTER COLUMN org_emp_phone TYPE VARCHAR(50),
    ALTER COLUMN org_emp_dept_id TYPE VARCHAR(26),
    ALTER COLUMN org_emp_dept_id SET NOT NULL,
    ALTER COLUMN org_emp_position_id TYPE VARCHAR(26),
    ALTER COLUMN org_emp_position_id SET NOT NULL,
    ALTER COLUMN org_emp_status TYPE VARCHAR(50),
    ALTER COLUMN org_emp_type TYPE VARCHAR(50),
    ALTER COLUMN org_emp_member_id TYPE VARCHAR(26),
    ALTER COLUMN org_emp_user_id TYPE VARCHAR(26),
    ALTER COLUMN org_emp_report_to TYPE VARCHAR(26);
