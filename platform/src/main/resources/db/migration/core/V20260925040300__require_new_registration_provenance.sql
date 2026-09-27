-- Enforce provenance on future writes without rewriting immutable historical rows.
CREATE OR REPLACE FUNCTION ab_application_release_require_actor() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.registration_key IS NULL OR NEW.registration_request_digest IS NULL OR NEW.registered_by IS NULL THEN
        RAISE EXCEPTION 'Application release registration key, request digest and actor are required';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION ab_application_require_creator() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.created_by IS NULL THEN
        RAISE EXCEPTION 'Application creator is required';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_application_require_creator BEFORE INSERT ON ab_application
    FOR EACH ROW EXECUTE FUNCTION ab_application_require_creator();
