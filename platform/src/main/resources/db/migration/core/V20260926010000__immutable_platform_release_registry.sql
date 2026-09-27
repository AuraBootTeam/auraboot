-- Immutable content registration, separate from the historical deployment ledger.
CREATE TABLE ab_platform_release_registry (
    release_id VARCHAR(26) PRIMARY KEY CHECK (release_id ~ '^[0-9A-HJKMNP-TV-Z]{26}$'),
    platform_code TEXT NOT NULL CHECK (platform_code ~ '^[a-z][a-z0-9-]{1,99}$'),
    version TEXT NOT NULL CHECK (length(btrim(version)) > 0 AND version !~* '(snapshot|latest|workspace|branch)'),
    source_lock_identity TEXT NOT NULL CHECK (source_lock_identity ~ '^sha256:[0-9a-f]{64}$'),
    manifest_text TEXT NOT NULL CHECK (manifest_text IS JSON OBJECT WITH UNIQUE KEYS),
    manifest JSONB GENERATED ALWAYS AS (manifest_text::jsonb) STORED,
    digest TEXT NOT NULL CHECK (digest = 'sha256:' || encode(sha256(convert_to(manifest_text, 'UTF8')), 'hex')),
    registered_by TEXT NOT NULL CHECK (length(btrim(registered_by)) > 0),
    registered_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (platform_code, version)
);

CREATE FUNCTION ab_platform_registry_validate() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    body JSONB := NEW.manifest_text::jsonb;
    artifact JSONB;
    dimension TEXT;
    contracts JSONB;
BEGIN
    IF jsonb_typeof(body) IS DISTINCT FROM 'object'
       OR body->'schemaVersion' IS DISTINCT FROM '1'::jsonb
       OR body->>'releaseId' IS DISTINCT FROM NEW.release_id
       OR body->>'platform' IS DISTINCT FROM NEW.platform_code
       OR body->>'version' IS DISTINCT FROM NEW.version
       OR body->>'sourceLockIdentity' IS DISTINCT FROM NEW.source_lock_identity
       OR jsonb_typeof(body->'platformContracts') IS DISTINCT FROM 'object'
       OR jsonb_typeof(body->'artifacts') IS DISTINCT FROM 'array' THEN
        RAISE EXCEPTION 'Platform manifest identity or shape mismatch';
    END IF;
    IF EXISTS (SELECT 1 FROM unnest(ARRAY['releaseId','platform','version','sourceLockIdentity']) name
                WHERE jsonb_typeof(body->name) IS DISTINCT FROM 'string') THEN
        RAISE EXCEPTION 'Platform identity fields must be strings';
    END IF;
    IF (body - ARRAY['schemaVersion','releaseId','platform','version','sourceLockIdentity','platformContracts','artifacts']) <> '{}'::jsonb
       OR ((body->'platformContracts') - ARRAY['runtime','pluginApi','dslSchema']) <> '{}'::jsonb THEN
        RAISE EXCEPTION 'Unknown platform manifest fields';
    END IF;
    FOREACH dimension IN ARRAY ARRAY['runtime','pluginApi','dslSchema'] LOOP
        contracts := body->'platformContracts'->dimension;
        IF jsonb_typeof(contracts) IS DISTINCT FROM 'array' THEN
            RAISE EXCEPTION 'Explicit platform contract arrays required';
        END IF;
        IF jsonb_array_length(contracts) = 0 OR EXISTS (
            SELECT 1 FROM jsonb_array_elements(contracts) item
             WHERE CASE WHEN dimension = 'dslSchema'
                 THEN jsonb_typeof(item) <> 'number' OR NOT (item::text ~ '^[1-9][0-9]*$')
                 ELSE jsonb_typeof(item) <> 'string' OR length(btrim(item #>> '{}')) NOT BETWEEN 1 AND 200 END
        ) OR (SELECT count(*) <> count(DISTINCT item) FROM jsonb_array_elements(contracts) item) THEN
            RAISE EXCEPTION 'Invalid or duplicate platform contracts';
        END IF;
    END LOOP;
    IF jsonb_array_length(body->'artifacts') = 0 THEN
        RAISE EXCEPTION 'Platform artifacts required';
    END IF;
    FOR artifact IN SELECT value FROM jsonb_array_elements(body->'artifacts') LOOP
        IF EXISTS (SELECT 1 FROM unnest(ARRAY['type','id','version','digest','uri']) name
                    WHERE jsonb_typeof(artifact->name) IS DISTINCT FROM 'string')
           OR EXISTS (SELECT 1 FROM unnest(ARRAY['repository','commit']) name
                    WHERE jsonb_typeof(artifact->'source'->name) IS DISTINCT FROM 'string') THEN
            RAISE EXCEPTION 'Platform artifact identity fields must be strings';
        END IF;
        IF jsonb_typeof(artifact) IS DISTINCT FROM 'object'
           OR NOT COALESCE(artifact->>'type' IN ('runtime','maven','npm','plugin','config','migration','oci'), false)
           OR NOT COALESCE(length(btrim(artifact->>'id')) > 0, false)
           OR NOT COALESCE(length(btrim(artifact->>'version')) > 0 AND artifact->>'version' !~* '(snapshot|latest|workspace|branch)', false)
           OR NOT COALESCE(artifact->>'digest' ~ '^sha256:[0-9a-f]{64}$', false)
           OR NOT COALESCE(artifact->>'uri' ~ '^(maven|npm|oci|artifact):.', false)
           OR jsonb_typeof(artifact->'source') IS DISTINCT FROM 'object'
           OR NOT COALESCE(length(btrim(artifact->'source'->>'repository')) > 0, false)
           OR NOT COALESCE(artifact->'source'->>'commit' ~ '^[0-9a-f]{40}$', false) THEN
            RAISE EXCEPTION 'Invalid platform artifact';
        END IF;
        IF (artifact - ARRAY['type','id','version','digest','uri','source']) <> '{}'::jsonb
           OR ((artifact->'source') - ARRAY['repository','commit']) <> '{}'::jsonb THEN
            RAISE EXCEPTION 'Unknown platform artifact fields';
        END IF;
    END LOOP;
    IF EXISTS (SELECT 1 FROM jsonb_array_elements(body->'artifacts') item
               GROUP BY item->>'type', item->>'id' HAVING count(*) > 1) THEN
        RAISE EXCEPTION 'Duplicate platform artifact coordinate';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_platform_registry_validate BEFORE INSERT ON ab_platform_release_registry
    FOR EACH ROW EXECUTE FUNCTION ab_platform_registry_validate();

CREATE FUNCTION ab_platform_registry_reject_mutation() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Platform release registration is immutable';
END $$;
CREATE TRIGGER trg_platform_registry_immutable BEFORE UPDATE OR DELETE ON ab_platform_release_registry
    FOR EACH ROW EXECUTE FUNCTION ab_platform_registry_reject_mutation();
CREATE TRIGGER trg_platform_registry_no_truncate BEFORE TRUNCATE ON ab_platform_release_registry
    FOR EACH STATEMENT EXECUTE FUNCTION ab_platform_registry_reject_mutation();

COMMENT ON TABLE ab_platform_release_registry IS
    'Registered immutable platform bytes, not admission, deployment observation or tenant binding authority.';
