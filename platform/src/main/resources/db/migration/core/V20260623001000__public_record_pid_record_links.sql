ALTER TABLE ab_inbox_item
    ADD COLUMN IF NOT EXISTS record_pid VARCHAR(64);

UPDATE ab_inbox_item
SET record_pid = COALESCE(
        NULLIF(card_payload ->> 'sourceRecordPid', ''),
        NULLIF(card_payload ->> 'recordPid', '')
    )
WHERE record_pid IS NULL
  AND card_payload IS NOT NULL;

DO $$
DECLARE
    row_rec RECORD;
    model_table TEXT;
    resolved_pid TEXT;
BEGIN
    FOR row_rec IN
        SELECT id, tenant_id, model_code, record_id
        FROM ab_inbox_item
        WHERE record_pid IS NULL
          AND record_id IS NOT NULL
          AND model_code IS NOT NULL
    LOOP
        SELECT table_name
        INTO model_table
        FROM ab_meta_model
        WHERE tenant_id = row_rec.tenant_id
          AND code = row_rec.model_code
          AND deleted_flag = FALSE
        ORDER BY is_current DESC, id DESC
        LIMIT 1;

        IF model_table IS NOT NULL AND model_table ~ '^[A-Za-z_][A-Za-z0-9_]*$' THEN
            BEGIN
                EXECUTE format(
                    'SELECT pid::text FROM %I WHERE tenant_id = $1 AND id = $2 LIMIT 1',
                    model_table
                )
                INTO resolved_pid
                USING row_rec.tenant_id, row_rec.record_id;

                IF resolved_pid IS NOT NULL THEN
                    UPDATE ab_inbox_item
                    SET record_pid = resolved_pid
                    WHERE id = row_rec.id;
                END IF;
            EXCEPTION WHEN undefined_table OR undefined_column THEN
                NULL;
            END;
        END IF;
    END LOOP;
END $$;

CREATE INDEX IF NOT EXISTS idx_inbox_item_record_pid
    ON ab_inbox_item(tenant_id, model_code, record_pid)
    WHERE record_pid IS NOT NULL;

ALTER TABLE ab_inbox_item
    DROP COLUMN IF EXISTS record_id;

ALTER TABLE ab_im_conversation
    ADD COLUMN IF NOT EXISTS bound_record_pid VARCHAR(64);

DO $$
DECLARE
    row_rec RECORD;
    model_table TEXT;
    resolved_pid TEXT;
BEGIN
    FOR row_rec IN
        SELECT id, tenant_id, bound_model_code, bound_record_id
        FROM ab_im_conversation
        WHERE bound_record_pid IS NULL
          AND bound_record_id IS NOT NULL
          AND bound_model_code IS NOT NULL
    LOOP
        SELECT table_name
        INTO model_table
        FROM ab_meta_model
        WHERE tenant_id = row_rec.tenant_id
          AND code = row_rec.bound_model_code
          AND deleted_flag = FALSE
        ORDER BY is_current DESC, id DESC
        LIMIT 1;

        IF model_table IS NOT NULL AND model_table ~ '^[A-Za-z_][A-Za-z0-9_]*$' THEN
            BEGIN
                EXECUTE format(
                    'SELECT pid::text FROM %I WHERE tenant_id = $1 AND id = $2 LIMIT 1',
                    model_table
                )
                INTO resolved_pid
                USING row_rec.tenant_id, row_rec.bound_record_id;

                IF resolved_pid IS NOT NULL THEN
                    UPDATE ab_im_conversation
                    SET bound_record_pid = resolved_pid
                    WHERE id = row_rec.id;
                END IF;
            EXCEPTION WHEN undefined_table OR undefined_column THEN
                NULL;
            END;
        END IF;
    END LOOP;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uk_ab_im_conv_bound_pid
    ON ab_im_conversation(tenant_id, bound_model_code, bound_record_pid)
    WHERE bound_model_code IS NOT NULL
      AND bound_record_pid IS NOT NULL;

DROP INDEX IF EXISTS uk_ab_im_conv_bound;

ALTER TABLE ab_im_conversation
    DROP COLUMN IF EXISTS bound_record_id;

ALTER TABLE ab_email_record_link
    ADD COLUMN IF NOT EXISTS record_pid VARCHAR(100);

UPDATE ab_email_record_link
SET record_pid = record_id
WHERE record_pid IS NULL
  AND record_id IS NOT NULL;

ALTER TABLE ab_email_record_link
    ALTER COLUMN record_pid SET NOT NULL;

DROP INDEX IF EXISTS idx_email_record_link_record;

CREATE INDEX IF NOT EXISTS idx_email_record_link_record_pid
    ON ab_email_record_link(tenant_id, model_code, record_pid);

ALTER TABLE ab_email_record_link
    DROP COLUMN IF EXISTS record_id;

ALTER TABLE ab_email_sequence_enrollment
    ADD COLUMN IF NOT EXISTS record_pid VARCHAR(100);

UPDATE ab_email_sequence_enrollment
SET record_pid = record_id
WHERE record_pid IS NULL
  AND record_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_email_enrollment_record_pid
    ON ab_email_sequence_enrollment(tenant_id, model_code, record_pid)
    WHERE record_pid IS NOT NULL;

ALTER TABLE ab_email_sequence_enrollment
    DROP COLUMN IF EXISTS record_id;

ALTER TABLE ab_automation_log
    ADD COLUMN IF NOT EXISTS trigger_record_pid VARCHAR(64);

UPDATE ab_automation_log
SET trigger_record_pid = trigger_record_id
WHERE trigger_record_pid IS NULL
  AND trigger_record_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_automation_log_trigger_record_pid
    ON ab_automation_log(tenant_id, trigger_record_pid)
    WHERE trigger_record_pid IS NOT NULL;

ALTER TABLE ab_automation_log
    DROP COLUMN IF EXISTS trigger_record_id;

ALTER TABLE ab_automation_debug_session
    ADD COLUMN IF NOT EXISTS record_pid VARCHAR(255);

UPDATE ab_automation_debug_session
SET record_pid = record_id
WHERE record_pid IS NULL
  AND record_id IS NOT NULL;

ALTER TABLE ab_automation_debug_session
    DROP COLUMN IF EXISTS record_id;

ALTER TABLE ab_agent_action
    ADD COLUMN IF NOT EXISTS target_record_pid VARCHAR(64),
    ADD COLUMN IF NOT EXISTS target_record_pids JSONB;

UPDATE ab_agent_action
SET target_record_pid = target_record_id
WHERE target_record_pid IS NULL
  AND target_record_id IS NOT NULL;

UPDATE ab_agent_action
SET target_record_pids = target_record_ids
WHERE target_record_pids IS NULL
  AND target_record_ids IS NOT NULL;

ALTER TABLE ab_agent_action
    DROP COLUMN IF EXISTS target_record_id,
    DROP COLUMN IF EXISTS target_record_ids;

ALTER TABLE ab_ai_action_audit_log
    ADD COLUMN IF NOT EXISTS record_pid VARCHAR(64);

UPDATE ab_ai_action_audit_log
SET record_pid = record_id
WHERE record_pid IS NULL
  AND record_id IS NOT NULL;

ALTER TABLE ab_ai_action_audit_log
    DROP COLUMN IF EXISTS record_id;
