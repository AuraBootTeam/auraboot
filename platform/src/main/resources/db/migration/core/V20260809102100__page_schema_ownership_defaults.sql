-- Keep PageSchema ownership truthful for import paths that only stamp template/plugin provenance.
-- Explicit non-TENANT ownership remains authoritative; ordinary user-created pages stay TENANT.

CREATE OR REPLACE FUNCTION ab_page_schema_apply_ownership_default()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF COALESCE(NEW.ownership_scope, 'TENANT') = 'TENANT' THEN
            IF NEW.is_template = TRUE THEN
                NEW.ownership_scope := 'PLATFORM';
            ELSIF NEW.plugin_pid IS NOT NULL THEN
                NEW.ownership_scope := 'APPLICATION';
            ELSE
                NEW.ownership_scope := 'TENANT';
            END IF;
        END IF;
    ELSIF NEW.ownership_scope IS NOT DISTINCT FROM OLD.ownership_scope
            AND (NEW.is_template IS DISTINCT FROM OLD.is_template
                 OR NEW.plugin_pid IS DISTINCT FROM OLD.plugin_pid) THEN
        IF NEW.is_template = TRUE THEN
            NEW.ownership_scope := 'PLATFORM';
        ELSIF NEW.plugin_pid IS NOT NULL THEN
            NEW.ownership_scope := 'APPLICATION';
        ELSE
            NEW.ownership_scope := 'TENANT';
        END IF;
    END IF;

    IF NEW.ownership_ref IS NULL AND NEW.plugin_pid IS NOT NULL THEN
        NEW.ownership_ref := NEW.plugin_pid;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_page_schema_ownership_default ON ab_page_schema;
CREATE TRIGGER trg_page_schema_ownership_default
    BEFORE INSERT OR UPDATE OF is_template, plugin_pid, ownership_scope, ownership_ref
    ON ab_page_schema
    FOR EACH ROW EXECUTE FUNCTION ab_page_schema_apply_ownership_default();

COMMENT ON FUNCTION ab_page_schema_apply_ownership_default() IS
    'Derives PLATFORM/APPLICATION PageSchema ownership from template/plugin provenance';
