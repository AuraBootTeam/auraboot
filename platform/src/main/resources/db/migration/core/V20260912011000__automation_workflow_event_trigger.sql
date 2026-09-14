-- Rename the platform automation trigger protocol from the extracted product
-- name to the provider-neutral workflow capability name.
ALTER TABLE ab_automation
    DROP CONSTRAINT IF EXISTS chk_automation_trigger_type;

UPDATE ab_automation
SET trigger_type = 'on_workflow_event'
WHERE trigger_type = 'on_bpm_event';

ALTER TABLE ab_automation
    ADD CONSTRAINT chk_automation_trigger_type CHECK (trigger_type IN (
        'on_record_create',
        'on_record_update',
        'on_field_change',
        'on_state_change',
        'scheduled',
        'webhook',
        'on_workflow_event',
        'on_inactivity'
    ));
