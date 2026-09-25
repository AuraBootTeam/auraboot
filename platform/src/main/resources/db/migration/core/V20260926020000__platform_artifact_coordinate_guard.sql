-- Preserve platform-scoped artifact coordinates across registered platform versions.
-- Refuse historical conflicts rather than rewriting immutable manifests.
LOCK TABLE ab_platform_release_registry IN SHARE ROW EXCLUSIVE MODE;
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM ab_platform_release_registry release,
             LATERAL jsonb_array_elements(release.manifest->'artifacts') artifact
        GROUP BY release.platform_code, artifact->>'type', artifact->>'id', artifact->>'version'
        HAVING count(DISTINCT artifact->>'digest') > 1
    ) THEN
        RAISE EXCEPTION 'Existing platform artifact coordinate conflict';
    END IF;
END $$;

CREATE FUNCTION ab_platform_registry_guard_coordinates() RETURNS TRIGGER
LANGUAGE plpgsql VOLATILE SET search_path FROM CURRENT AS $$
BEGIN
    -- The query after this lock must see the transaction that released it.
    -- Refuse snapshot isolation; callers must retry the entire registration at READ COMMITTED.
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'Platform registration requires READ COMMITTED isolation';
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended('platform-artifacts:' || NEW.platform_code, 0));
    IF EXISTS (
        SELECT 1 FROM ab_platform_release_registry release,
             LATERAL jsonb_array_elements(release.manifest->'artifacts') existing,
             LATERAL jsonb_array_elements(NEW.manifest_text::jsonb->'artifacts') incoming
        WHERE release.platform_code = NEW.platform_code
          AND existing->>'type' = incoming->>'type'
          AND existing->>'id' = incoming->>'id'
          AND existing->>'version' = incoming->>'version'
          AND existing->>'digest' IS DISTINCT FROM incoming->>'digest'
    ) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'Platform artifact coordinate cannot change its digest',
            CONSTRAINT = 'platform_artifact_coordinate_immutable';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_platform_registry_coordinates BEFORE INSERT ON ab_platform_release_registry
    FOR EACH ROW EXECUTE FUNCTION ab_platform_registry_guard_coordinates();
