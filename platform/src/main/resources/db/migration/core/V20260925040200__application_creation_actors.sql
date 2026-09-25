-- Unknown historical creators remain unknown; new control-plane writes supply an actor.
ALTER TABLE ab_application ADD COLUMN created_by TEXT;
ALTER TABLE ab_application ADD CONSTRAINT ab_application_creator_format
    CHECK (created_by IS NULL OR created_by ~ '^(user|ci|system|test):[A-Za-z0-9._:-]{1,200}$');
CREATE TRIGGER trg_application_creation_immutable
    BEFORE UPDATE OF created_by, created_at ON ab_application
    FOR EACH ROW EXECUTE FUNCTION ab_application_release_reject_mutation();
