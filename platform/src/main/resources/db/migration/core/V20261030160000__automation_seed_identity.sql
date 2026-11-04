-- Keep plugin seed identity in the automation module, independent of editable display names.
ALTER TABLE ab_automation ADD COLUMN seed_plugin_id TEXT;
ALTER TABLE ab_automation ADD COLUMN seed_key TEXT;
ALTER TABLE ab_automation ADD CONSTRAINT chk_automation_seed_identity CHECK (
    (seed_plugin_id IS NULL AND seed_key IS NULL) OR
    (seed_plugin_id IS NOT NULL AND length(btrim(seed_plugin_id)) > 0
     AND seed_key IS NOT NULL AND length(btrim(seed_key)) > 0));
CREATE UNIQUE INDEX uq_automation_seed_identity ON ab_automation(tenant_id, seed_plugin_id, seed_key)
    WHERE deleted_flag IS NOT TRUE AND seed_plugin_id IS NOT NULL;
CREATE FUNCTION ab_automation_seed_identity_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.seed_plugin_id IS NOT NULL AND
       (NEW.seed_plugin_id IS DISTINCT FROM OLD.seed_plugin_id OR NEW.seed_key IS DISTINCT FROM OLD.seed_key) THEN
        RAISE EXCEPTION 'Automation seed identity is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_automation_seed_identity BEFORE UPDATE OF seed_plugin_id, seed_key ON ab_automation
    FOR EACH ROW EXECUTE FUNCTION ab_automation_seed_identity_guard();
