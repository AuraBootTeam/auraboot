-- Historical rows keep unknown operator metadata; new transitions require explicit control-plane context.
ALTER TABLE ab_tenant_application_binding_history
    ADD COLUMN business_actor TEXT CHECK (business_actor ~ '^(user|ci|system|test):[A-Za-z0-9._:-]{1,200}$'),
    ADD COLUMN operation_id VARCHAR(26) CHECK (operation_id ~ '^[0-9A-HJKMNP-TV-Z]{26}$'),
    ADD CONSTRAINT uq_tenant_binding_operation UNIQUE (tenant_id, application_id, operation_id);

CREATE OR REPLACE FUNCTION ab_tenant_binding_project_history() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE
    actor TEXT := current_setting('aura.binding.actor', true);
    operation TEXT := current_setting('aura.binding.operation', true);
BEGIN
    IF actor IS NULL OR actor !~ '^(user|ci|system|test):[A-Za-z0-9._:-]{1,200}$'
       OR operation IS NULL OR operation !~ '^[0-9A-HJKMNP-TV-Z]{26}$' THEN
        RAISE EXCEPTION 'Binding transition requires business actor and operation identity';
    END IF;
    INSERT INTO ab_tenant_application_binding_history
        (tenant_id, application_id, binding_version, previous_release_id, previous_status,
         current_release_id, status, changed_at, database_actor, business_actor, operation_id)
    VALUES (NEW.tenant_id, NEW.application_id, NEW.binding_version,
        CASE WHEN TG_OP = 'UPDATE' THEN OLD.current_release_id END,
        CASE WHEN TG_OP = 'UPDATE' THEN OLD.status END,
        NEW.current_release_id, NEW.status, NEW.updated_at, current_user, actor, operation);
    RETURN NEW;
END $$;

COMMENT ON COLUMN ab_tenant_application_binding_history.business_actor IS 'Operator asserted by the trusted control connection; legacy unknown values remain null';
COMMENT ON COLUMN ab_tenant_application_binding_history.operation_id IS 'Control-plane operation identity, unique per tenant and application';
