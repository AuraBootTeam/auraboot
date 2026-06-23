ALTER TABLE ab_watch
    ADD COLUMN IF NOT EXISTS record_pid VARCHAR(64);

UPDATE ab_watch
SET record_pid = record_id::text
WHERE record_pid IS NULL
  AND record_id IS NOT NULL;

ALTER TABLE ab_watch
    ALTER COLUMN record_id DROP NOT NULL;

ALTER TABLE ab_watch
    DROP CONSTRAINT IF EXISTS uq_watch;

CREATE UNIQUE INDEX IF NOT EXISTS ux_ab_watch_record_id
    ON ab_watch (tenant_id, user_id, model_code, record_id)
    WHERE record_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_ab_watch_record_pid
    ON ab_watch (tenant_id, user_id, model_code, record_pid)
    WHERE record_pid IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_watch_record_pid
    ON ab_watch (tenant_id, model_code, record_pid);

ALTER TABLE ab_field_change_log
    ADD COLUMN IF NOT EXISTS record_pid VARCHAR(64);

UPDATE ab_field_change_log
SET record_pid = record_id::text
WHERE record_pid IS NULL
  AND record_id IS NOT NULL;

ALTER TABLE ab_field_change_log
    ALTER COLUMN record_id DROP NOT NULL;

CREATE INDEX IF NOT EXISTS idx_field_change_model_record_pid
    ON ab_field_change_log (tenant_id, model_code, record_pid);
