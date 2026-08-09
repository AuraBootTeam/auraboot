-- Immutable history guards for Contextual Authoring.

CREATE OR REPLACE FUNCTION ab_authoring_guard_release_update()
RETURNS TRIGGER AS $$
BEGIN
    IF ROW(
        NEW.pid, NEW.tenant_id, NEW.env_id, NEW.change_set_id,
        NEW.change_set_revision, NEW.previous_release_pid,
        NEW.manifest, NEW.manifest_checksum, NEW.created_by, NEW.created_at
    ) IS DISTINCT FROM ROW(
        OLD.pid, OLD.tenant_id, OLD.env_id, OLD.change_set_id,
        OLD.change_set_revision, OLD.previous_release_pid,
        OLD.manifest, OLD.manifest_checksum, OLD.created_by, OLD.created_at
    ) THEN
        RAISE EXCEPTION 'authoring release content is immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION ab_authoring_reject_history_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'authoring history is append-only: %', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_authoring_release_immutable_update ON ab_authoring_release;
CREATE TRIGGER trg_authoring_release_immutable_update
    BEFORE UPDATE ON ab_authoring_release
    FOR EACH ROW EXECUTE FUNCTION ab_authoring_guard_release_update();

DROP TRIGGER IF EXISTS trg_authoring_release_immutable_delete ON ab_authoring_release;
CREATE TRIGGER trg_authoring_release_immutable_delete
    BEFORE DELETE ON ab_authoring_release
    FOR EACH ROW EXECUTE FUNCTION ab_authoring_reject_history_mutation();

DROP TRIGGER IF EXISTS trg_authoring_release_item_append_only ON ab_authoring_release_item;
CREATE TRIGGER trg_authoring_release_item_append_only
    BEFORE UPDATE OR DELETE ON ab_authoring_release_item
    FOR EACH ROW EXECUTE FUNCTION ab_authoring_reject_history_mutation();

DROP TRIGGER IF EXISTS trg_authoring_change_item_append_only ON ab_authoring_change_item;
CREATE TRIGGER trg_authoring_change_item_append_only
    BEFORE UPDATE OR DELETE ON ab_authoring_change_item
    FOR EACH ROW EXECUTE FUNCTION ab_authoring_reject_history_mutation();

DROP TRIGGER IF EXISTS trg_authoring_audit_append_only ON ab_authoring_audit_event;
CREATE TRIGGER trg_authoring_audit_append_only
    BEFORE UPDATE OR DELETE ON ab_authoring_audit_event
    FOR EACH ROW EXECUTE FUNCTION ab_authoring_reject_history_mutation();
