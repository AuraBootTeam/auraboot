-- Allow external_event as an automation trigger type so that the open
-- platform event ingress can create automations that consume externally
-- pushed events (in addition to the existing record/schedule/webhook types).
ALTER TABLE ab_automation DROP CONSTRAINT IF EXISTS chk_automation_trigger_type;
ALTER TABLE ab_automation ADD CONSTRAINT chk_automation_trigger_type
    CHECK ((trigger_type)::text = ANY (ARRAY[
        'on_record_create'::character varying,
        'on_record_update'::character varying,
        'on_field_change'::character varying,
        'on_state_change'::character varying,
        'scheduled'::character varying,
        'webhook'::character varying,
        'on_workflow_event'::character varying,
        'on_inactivity'::character varying,
        'external_event'::character varying
    ]));
