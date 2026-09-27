-- Preserve unknown historical actors without inventing attribution.
ALTER TABLE ab_application_release ADD COLUMN registered_by TEXT;
ALTER TABLE ab_application_release ADD CONSTRAINT ab_application_release_actor_format
    CHECK (registered_by IS NULL OR registered_by ~ '^(user|ci|system|test):[A-Za-z0-9._:-]{1,200}$');

-- New service registrations must have an actor; older registrations remain immutable.
CREATE FUNCTION ab_application_release_require_actor() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.registration_key IS NOT NULL AND NEW.registered_by IS NULL THEN
        RAISE EXCEPTION 'Application release registration actor is required';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ab_application_release_require_actor
    BEFORE INSERT ON ab_application_release
    FOR EACH ROW EXECUTE FUNCTION ab_application_release_require_actor();
