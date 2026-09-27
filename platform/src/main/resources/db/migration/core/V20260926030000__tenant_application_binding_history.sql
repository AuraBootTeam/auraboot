-- Storage invariants only. Registration is not permission to activate a tenant.
CREATE TABLE ab_tenant_application_binding (
    tenant_id BIGINT NOT NULL REFERENCES ab_tenant(id),
    application_id BIGINT NOT NULL REFERENCES ab_application(id),
    current_release_id VARCHAR(26) NOT NULL,
    status TEXT NOT NULL DEFAULT 'shadow' CHECK (status IN ('shadow', 'active')),
    binding_version BIGINT NOT NULL DEFAULT 1 CHECK (binding_version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, application_id),
    FOREIGN KEY (application_id, current_release_id)
        REFERENCES ab_application_release(application_id, release_id)
);

CREATE TABLE ab_tenant_application_binding_history (
    tenant_id BIGINT NOT NULL,
    application_id BIGINT NOT NULL,
    binding_version BIGINT NOT NULL CHECK (binding_version > 0),
    previous_release_id VARCHAR(26),
    previous_status TEXT CHECK (previous_status IN ('shadow', 'active')),
    current_release_id VARCHAR(26) NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('shadow', 'active')),
    changed_at TIMESTAMPTZ NOT NULL,
    database_actor TEXT NOT NULL,
    PRIMARY KEY (tenant_id, application_id, binding_version),
    FOREIGN KEY (tenant_id, application_id) REFERENCES ab_tenant_application_binding(tenant_id, application_id),
    FOREIGN KEY (application_id, current_release_id) REFERENCES ab_application_release(application_id, release_id),
    FOREIGN KEY (application_id, previous_release_id) REFERENCES ab_application_release(application_id, release_id),
    CHECK ((binding_version = 1 AND previous_release_id IS NULL AND previous_status IS NULL)
        OR (binding_version > 1 AND previous_release_id IS NOT NULL AND previous_status IS NOT NULL))
);

CREATE FUNCTION ab_tenant_binding_guard() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'Tenant binding removal requires a future reference-aware retirement protocol';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.binding_version <> 1 OR NEW.status <> 'shadow' THEN
            RAISE EXCEPTION 'Tenant binding must start at shadow version one';
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
    END IF;
    NEW.updated_at := clock_timestamp();
    RETURN NEW;
END $$;
CREATE TRIGGER trg_tenant_binding_guard BEFORE INSERT OR UPDATE OR DELETE ON ab_tenant_application_binding
    FOR EACH ROW EXECUTE FUNCTION ab_tenant_binding_guard();
CREATE TRIGGER trg_tenant_binding_no_truncate BEFORE TRUNCATE ON ab_tenant_application_binding
    FOR EACH STATEMENT EXECUTE FUNCTION ab_tenant_binding_guard();

CREATE FUNCTION ab_tenant_binding_history_guard() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    IF TG_OP <> 'INSERT' OR pg_trigger_depth() <> 2 THEN
        RAISE EXCEPTION 'Tenant binding history is an immutable transition projection';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_tenant_binding_history_guard BEFORE INSERT OR UPDATE OR DELETE ON ab_tenant_application_binding_history
    FOR EACH ROW EXECUTE FUNCTION ab_tenant_binding_history_guard();
CREATE TRIGGER trg_tenant_binding_history_no_truncate BEFORE TRUNCATE ON ab_tenant_application_binding_history
    FOR EACH STATEMENT EXECUTE FUNCTION ab_tenant_binding_history_guard();

CREATE FUNCTION ab_tenant_binding_project_history() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    INSERT INTO ab_tenant_application_binding_history
        (tenant_id, application_id, binding_version, previous_release_id, previous_status,
         current_release_id, status, changed_at, database_actor)
    VALUES (NEW.tenant_id, NEW.application_id, NEW.binding_version,
        CASE WHEN TG_OP = 'UPDATE' THEN OLD.current_release_id END,
        CASE WHEN TG_OP = 'UPDATE' THEN OLD.status END,
        NEW.current_release_id, NEW.status, NEW.updated_at, current_user);
    RETURN NEW;
END $$;
CREATE TRIGGER trg_tenant_binding_project_history AFTER INSERT OR UPDATE ON ab_tenant_application_binding
    FOR EACH ROW EXECUTE FUNCTION ab_tenant_binding_project_history();

COMMENT ON TABLE ab_tenant_application_binding IS 'Exact tenant release pointer; updates require control-plane admission and version CAS';
COMMENT ON COLUMN ab_tenant_application_binding_history.database_actor IS 'Database principal only; does not identify the authenticated business operator';
