-- Run with psql -v ON_ERROR_STOP=1 after Flyway migrate on an isolated database.
-- Exercises the real table constraint; all fixture rows are rolled back.
BEGIN;
DO $$
DECLARE
    trigger_name text;
    fixture_id bigint := -910000;
    rejected_constraint text;
BEGIN
    FOREACH trigger_name IN ARRAY ARRAY[
        'on_record_create', 'on_record_update', 'on_field_change',
        'on_state_change', 'scheduled', 'webhook', 'on_workflow_event',
        'on_inactivity', 'external_event'
    ] LOOP
        fixture_id := fixture_id - 1;
        INSERT INTO ab_automation (id, pid, tenant_id, name, trigger_type)
        VALUES (fixture_id, 'trigger-check-' || abs(fixture_id), -910000,
                'Migration trigger contract', trigger_name);
    END LOOP;
    IF (SELECT count(*) FROM ab_automation WHERE tenant_id = -910000
        AND id BETWEEN -910009 AND -910001) <> 9 THEN
        RAISE EXCEPTION 'Expected all nine trigger types to persist';
    END IF;
    BEGIN
        INSERT INTO ab_automation (id, pid, tenant_id, name, trigger_type)
        VALUES (-910010, 'trigger-check-invalid', -910000,
                'Invalid trigger must fail', 'unknown_event');
        RAISE EXCEPTION 'Invalid trigger was accepted';
    EXCEPTION WHEN check_violation THEN
        GET STACKED DIAGNOSTICS rejected_constraint = CONSTRAINT_NAME;
        IF rejected_constraint <> 'chk_automation_trigger_type' THEN
            RAISE EXCEPTION 'Rejected by unexpected constraint: %', rejected_constraint;
        END IF;
    END;
END $$;
ROLLBACK;
SELECT 'PASS: nine valid trigger types persisted; invalid trigger rejected' AS result;
