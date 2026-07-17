ALTER TABLE ab_permission_audit_log
    ADD COLUMN IF NOT EXISTS record_pid VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_ab_perm_audit_resource_record_pid
    ON ab_permission_audit_log (tenant_id, resource_code, record_pid, created_at)
    WHERE record_pid IS NOT NULL;
