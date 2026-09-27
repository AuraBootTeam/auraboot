-- Minimal product control plane: immutable publication, one exact stable target, and active binding admission.
CREATE TABLE ab_application_release_publication (
    release_id VARCHAR(26) PRIMARY KEY,
    application_id BIGINT NOT NULL,
    published_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    published_by TEXT NOT NULL CHECK (published_by ~ '^(user|ci|system|test):[A-Za-z0-9._:-]{1,200}$'),
    operation_id VARCHAR(26) NOT NULL UNIQUE CHECK (operation_id ~ '^[0-9A-HJKMNP-TV-Z]{26}$'),
    FOREIGN KEY (application_id, release_id)
        REFERENCES ab_application_release(application_id, release_id),
    UNIQUE (application_id, release_id)
);

CREATE TABLE ab_application_channel_target (
    application_id BIGINT NOT NULL REFERENCES ab_application(id),
    channel TEXT NOT NULL CHECK (channel = 'stable'),
    release_id VARCHAR(26) NOT NULL,
    target_version BIGINT NOT NULL DEFAULT 1 CHECK (target_version > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_by TEXT NOT NULL CHECK (updated_by ~ '^(user|ci|system|test):[A-Za-z0-9._:-]{1,200}$'),
    operation_id VARCHAR(26) NOT NULL UNIQUE CHECK (operation_id ~ '^[0-9A-HJKMNP-TV-Z]{26}$'),
    PRIMARY KEY (application_id, channel),
    FOREIGN KEY (application_id, release_id)
        REFERENCES ab_application_release_publication(application_id, release_id)
);

CREATE TABLE ab_application_channel_target_history (
    application_id BIGINT NOT NULL,
    channel TEXT NOT NULL CHECK (channel = 'stable'),
    target_version BIGINT NOT NULL CHECK (target_version > 0),
    previous_release_id VARCHAR(26),
    release_id VARCHAR(26) NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL,
    changed_by TEXT NOT NULL CHECK (changed_by ~ '^(user|ci|system|test):[A-Za-z0-9._:-]{1,200}$'),
    operation_id VARCHAR(26) NOT NULL UNIQUE CHECK (operation_id ~ '^[0-9A-HJKMNP-TV-Z]{26}$'),
    PRIMARY KEY (application_id, channel, target_version),
    FOREIGN KEY (application_id, channel)
        REFERENCES ab_application_channel_target(application_id, channel),
    FOREIGN KEY (application_id, release_id)
        REFERENCES ab_application_release_publication(application_id, release_id),
    FOREIGN KEY (application_id, previous_release_id)
        REFERENCES ab_application_release_publication(application_id, release_id),
    CHECK ((target_version = 1 AND previous_release_id IS NULL)
        OR (target_version > 1 AND previous_release_id IS NOT NULL))
);

CREATE FUNCTION ab_application_publication_immutable() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Application publication is immutable';
END $$;
CREATE TRIGGER trg_application_publication_immutable
    BEFORE UPDATE OR DELETE ON ab_application_release_publication
    FOR EACH ROW EXECUTE FUNCTION ab_application_publication_immutable();
CREATE TRIGGER trg_application_publication_no_truncate
    BEFORE TRUNCATE ON ab_application_release_publication
    FOR EACH STATEMENT EXECUTE FUNCTION ab_application_publication_immutable();

CREATE FUNCTION ab_application_channel_target_guard() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'Application channel target removal is not supported';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.target_version <> 1 THEN
            RAISE EXCEPTION 'Application channel target must start at version one';
        END IF;
    ELSE
        IF NEW.application_id IS DISTINCT FROM OLD.application_id OR NEW.channel IS DISTINCT FROM OLD.channel THEN
            RAISE EXCEPTION 'Application channel identity is immutable';
        END IF;
        IF NEW.target_version <> OLD.target_version + 1 OR NEW.release_id = OLD.release_id THEN
            RAISE EXCEPTION 'Application channel update requires a new release and next target version';
        END IF;
    END IF;
    NEW.updated_at := clock_timestamp();
    RETURN NEW;
END $$;
CREATE TRIGGER trg_application_channel_target_guard
    BEFORE INSERT OR UPDATE OR DELETE ON ab_application_channel_target
    FOR EACH ROW EXECUTE FUNCTION ab_application_channel_target_guard();
CREATE TRIGGER trg_application_channel_target_no_truncate
    BEFORE TRUNCATE ON ab_application_channel_target
    FOR EACH STATEMENT EXECUTE FUNCTION ab_application_channel_target_guard();

CREATE FUNCTION ab_application_channel_history_guard() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP <> 'INSERT' OR pg_trigger_depth() <> 2 THEN
        RAISE EXCEPTION 'Application channel history is an immutable projection';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_application_channel_history_guard
    BEFORE INSERT OR UPDATE OR DELETE ON ab_application_channel_target_history
    FOR EACH ROW EXECUTE FUNCTION ab_application_channel_history_guard();
CREATE TRIGGER trg_application_channel_history_no_truncate
    BEFORE TRUNCATE ON ab_application_channel_target_history
    FOR EACH STATEMENT EXECUTE FUNCTION ab_application_channel_history_guard();

CREATE FUNCTION ab_application_channel_project_history() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO ab_application_channel_target_history
        (application_id,channel,target_version,previous_release_id,release_id,changed_at,changed_by,operation_id)
    VALUES (NEW.application_id,NEW.channel,NEW.target_version,
        CASE WHEN TG_OP = 'UPDATE' THEN OLD.release_id END,
        NEW.release_id,NEW.updated_at,NEW.updated_by,NEW.operation_id);
    RETURN NEW;
END $$;
CREATE TRIGGER trg_application_channel_project_history
    AFTER INSERT OR UPDATE ON ab_application_channel_target
    FOR EACH ROW EXECUTE FUNCTION ab_application_channel_project_history();

CREATE FUNCTION ab_assert_stable_published_release(target_application_id BIGINT, target_release_id VARCHAR)
RETURNS VOID LANGUAGE plpgsql STABLE SET search_path FROM CURRENT AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM ab_application_channel_target
        WHERE application_id = target_application_id AND channel = 'stable' AND release_id = target_release_id
    ) THEN
        RAISE EXCEPTION 'Active tenant binding requires the exact published stable release';
    END IF;
END $$;

CREATE OR REPLACE FUNCTION ab_tenant_binding_guard() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'Tenant binding removal requires a future reference-aware retirement protocol';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.binding_version <> 1 OR NEW.status NOT IN ('shadow', 'active') THEN
            RAISE EXCEPTION 'Tenant binding must start at version one';
        END IF;
        IF NEW.status = 'active' THEN
            PERFORM ab_assert_stable_published_release(NEW.application_id, NEW.current_release_id);
        END IF;
        NEW.created_at := clock_timestamp();
    ELSE
        IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR NEW.application_id IS DISTINCT FROM OLD.application_id
           OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
            RAISE EXCEPTION 'Tenant binding identity is immutable';
        END IF;
        IF NEW.binding_version <> OLD.binding_version + 1 THEN
            RAISE EXCEPTION 'Tenant binding version must advance by one';
        END IF;
        IF NEW.current_release_id = OLD.current_release_id AND NEW.status = OLD.status THEN
            RAISE EXCEPTION 'Tenant binding transition must change its release or status';
        END IF;
        IF NEW.status = 'active'
           AND (OLD.status IS DISTINCT FROM 'active' OR NEW.current_release_id IS DISTINCT FROM OLD.current_release_id) THEN
            PERFORM ab_assert_stable_published_release(NEW.application_id, NEW.current_release_id);
        END IF;
    END IF;
    NEW.updated_at := clock_timestamp();
    RETURN NEW;
END $$;

COMMENT ON TABLE ab_application_release_publication IS
    'Immutable first-closure publication fact; revocation and retirement are future separate authorities';
COMMENT ON TABLE ab_application_channel_target IS
    'Exact stable release selected by the control plane; request handling resolves tenant binding instead';
COMMENT ON TABLE ab_application_channel_target_history IS
    'Immutable audit projection for stable target changes; it is not a rollout workflow';
