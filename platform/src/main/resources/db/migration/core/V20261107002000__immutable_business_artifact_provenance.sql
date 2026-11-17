CREATE FUNCTION ab_guard_business_artifact_provenance() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.artifact_authorization IS NOT NULL AND
       ROW(NEW.artifact_authorization, NEW.tenant_id, NEW.pid, NEW.file_name,
           NEW.local_path, NEW.cloud_key, NEW.file_size, NEW.mime_type, NEW.created_by)
       IS DISTINCT FROM
       ROW(OLD.artifact_authorization, OLD.tenant_id, OLD.pid, OLD.file_name,
           OLD.local_path, OLD.cloud_key, OLD.file_size, OLD.mime_type, OLD.created_by) THEN
        RAISE EXCEPTION 'Business artifact provenance and storage identity are immutable'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER ab_file_business_artifact_provenance
    BEFORE UPDATE ON ab_file
    FOR EACH ROW EXECUTE FUNCTION ab_guard_business_artifact_provenance();
