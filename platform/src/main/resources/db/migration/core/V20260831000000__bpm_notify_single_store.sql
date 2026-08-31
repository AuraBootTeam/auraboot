-- Make ab_bpm_notify_record the only BPM CC store.
-- Historical engine CC rows are intentionally not migrated (development-stage data).
DROP TABLE IF EXISTS se_notification_instance;

ALTER TABLE ab_bpm_notify_record
    ADD COLUMN IF NOT EXISTS title VARCHAR(255);
ALTER TABLE ab_bpm_notify_record
    ADD COLUMN IF NOT EXISTS source_type VARCHAR(32) NOT NULL DEFAULT 'LEGACY';
ALTER TABLE ab_bpm_notify_record
    ADD COLUMN IF NOT EXISTS source_ref VARCHAR(128);
ALTER TABLE ab_bpm_notify_record
    ADD COLUMN IF NOT EXISTS dedup_key VARCHAR(160);

CREATE UNIQUE INDEX IF NOT EXISTS uk_bpm_notify_dedup
    ON ab_bpm_notify_record(tenant_id, dedup_key)
    WHERE deleted_flag = FALSE AND dedup_key IS NOT NULL;

DROP INDEX IF EXISTS idx_bpm_notify_recipient;
CREATE INDEX idx_bpm_notify_recipient
    ON ab_bpm_notify_record(tenant_id, recipient_user_id, notify_type, is_read, created_at DESC)
    WHERE deleted_flag = FALSE;
