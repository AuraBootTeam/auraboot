-- Read legacy TEXT task metadata without letting malformed payloads break tenant operations views.
-- PostgreSQL 15 compatible; no task data is rewritten or deleted.
CREATE FUNCTION agent_task_metadata_v1(value TEXT) RETURNS JSONB
LANGUAGE plpgsql IMMUTABLE STRICT PARALLEL SAFE AS $$
DECLARE parsed JSONB;
BEGIN
    parsed := value::JSONB;
    IF jsonb_typeof(parsed) <> 'object' THEN RETURN NULL; END IF;
    RETURN parsed;
EXCEPTION WHEN data_exception THEN
    RETURN NULL;
END;
$$;
