-- A public-PID share is a single mutable collaboration relationship.
-- Permission changes must update that relationship, not accumulate duplicate grants.
CREATE UNIQUE INDEX IF NOT EXISTS uq_ab_record_share_public_subject
    ON ab_record_share (tenant_id, resource_code, record_pid, subject_type, subject_pid)
    WHERE record_pid IS NOT NULL AND subject_pid IS NOT NULL;
