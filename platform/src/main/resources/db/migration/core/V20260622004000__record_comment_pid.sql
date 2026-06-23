ALTER TABLE ab_record_comment
    ADD COLUMN IF NOT EXISTS pid VARCHAR(26);

UPDATE ab_record_comment
SET pid = SUBSTRING(REPLACE(gen_random_uuid()::text, '-', ''), 1, 26)
WHERE pid IS NULL OR pid = '';

ALTER TABLE ab_record_comment
    ALTER COLUMN pid SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_ab_record_comment_pid
    ON ab_record_comment (pid);
