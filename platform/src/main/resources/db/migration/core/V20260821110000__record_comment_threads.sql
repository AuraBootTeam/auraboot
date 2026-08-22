ALTER TABLE ab_record_comment
    ADD COLUMN IF NOT EXISTS parent_pid VARCHAR(26),
    ADD COLUMN IF NOT EXISTS reply_to_user_pid VARCHAR(26);

CREATE INDEX IF NOT EXISTS idx_record_comment_parent
    ON ab_record_comment (tenant_id, parent_pid, created_at ASC)
    WHERE deleted_flag = FALSE;
