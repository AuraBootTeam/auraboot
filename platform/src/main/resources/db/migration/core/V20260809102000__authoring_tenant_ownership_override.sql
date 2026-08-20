-- Ownership boundary for inherited PageSchema authoring.
-- Shared platform/application resources remain immutable inputs. Tenant authoring writes a
-- tenant-owned override lineage and publishes through the tenant/env release channel.

ALTER TABLE ab_page_schema
    ADD COLUMN ownership_scope VARCHAR(16) NOT NULL DEFAULT 'TENANT',
    ADD COLUMN ownership_ref VARCHAR(64);

UPDATE ab_page_schema
SET ownership_scope = CASE
        WHEN is_template = TRUE THEN 'PLATFORM'
        WHEN plugin_pid IS NOT NULL THEN 'APPLICATION'
        ELSE 'TENANT'
    END,
    ownership_ref = CASE
        WHEN plugin_pid IS NOT NULL THEN plugin_pid
        ELSE ownership_ref
    END;

ALTER TABLE ab_page_schema
    ADD CONSTRAINT chk_page_schema_ownership_scope
        CHECK (ownership_scope IN ('PLATFORM', 'APPLICATION', 'TENANT'));

CREATE INDEX idx_page_schema_ownership
    ON ab_page_schema (tenant_id, env_id, ownership_scope, page_key)
    WHERE deleted_flag = FALSE AND is_current = TRUE;

CREATE TABLE ab_authoring_tenant_override (
    id                      BIGSERIAL PRIMARY KEY,
    pid                     VARCHAR(26) UNIQUE NOT NULL,
    tenant_id               BIGINT NOT NULL,
    env_id                  BIGINT NOT NULL,
    source_resource_type    VARCHAR(40) NOT NULL,
    source_resource_pid     VARCHAR(64) NOT NULL,
    source_ownership_scope  VARCHAR(16) NOT NULL,
    base_source_version     BIGINT NOT NULL,
    base_source_checksum    VARCHAR(64) NOT NULL,
    status                  VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_by              BIGINT NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    row_version             BIGINT NOT NULL DEFAULT 1,
    CONSTRAINT fk_authoring_tenant_override_env
        FOREIGN KEY (env_id) REFERENCES ab_environment(id),
    CONSTRAINT uq_authoring_tenant_override_source
        UNIQUE (tenant_id, env_id, source_resource_type, source_resource_pid),
    CONSTRAINT chk_authoring_tenant_override_source_scope
        CHECK (source_ownership_scope IN ('PLATFORM', 'APPLICATION')),
    CONSTRAINT chk_authoring_tenant_override_status
        CHECK (status IN ('ACTIVE', 'STALE', 'REBASED', 'SUPERSEDED')),
    CONSTRAINT chk_authoring_tenant_override_version
        CHECK (base_source_version > 0 AND row_version > 0)
);

CREATE INDEX idx_authoring_tenant_override_tenant
    ON ab_authoring_tenant_override (tenant_id, env_id, status, updated_at DESC);

ALTER TABLE ab_authoring_change_set
    ADD COLUMN origin VARCHAR(40) NOT NULL DEFAULT 'DESIGN_STUDIO',
    ADD CONSTRAINT chk_authoring_change_set_origin
        CHECK (origin IN ('DESIGN_STUDIO', 'ENV_PROMOTION',
                          'PRODUCTION_CONTEXTUAL_HOTFIX', 'TENANT_OVERRIDE'));

ALTER TABLE ab_authoring_resource_draft
    ADD COLUMN ownership_scope VARCHAR(16) NOT NULL DEFAULT 'TENANT',
    ADD COLUMN source_ownership_scope VARCHAR(16) NOT NULL DEFAULT 'TENANT',
    ADD COLUMN source_resource_pid VARCHAR(64),
    ADD COLUMN override_pid VARCHAR(26),
    ADD CONSTRAINT chk_authoring_draft_ownership_scope
        CHECK (ownership_scope IN ('TENANT')),
    ADD CONSTRAINT chk_authoring_draft_source_ownership_scope
        CHECK (source_ownership_scope IN ('PLATFORM', 'APPLICATION', 'TENANT')),
    ADD CONSTRAINT chk_authoring_draft_override_lineage
        CHECK ((source_ownership_scope = 'TENANT' AND override_pid IS NULL)
            OR (source_ownership_scope IN ('PLATFORM', 'APPLICATION')
                AND source_resource_pid IS NOT NULL AND override_pid IS NOT NULL));

ALTER TABLE ab_authoring_change_item
    ADD COLUMN ownership_scope VARCHAR(16) NOT NULL DEFAULT 'TENANT',
    ADD COLUMN source_resource_pid VARCHAR(64),
    ADD COLUMN override_pid VARCHAR(26),
    ADD CONSTRAINT chk_authoring_change_item_ownership_scope
        CHECK (ownership_scope IN ('TENANT'));

ALTER TABLE ab_authoring_release_item
    ADD COLUMN ownership_scope VARCHAR(16) NOT NULL DEFAULT 'TENANT',
    ADD COLUMN source_resource_pid VARCHAR(64),
    ADD COLUMN override_pid VARCHAR(26),
    ADD CONSTRAINT chk_authoring_release_item_ownership_scope
        CHECK (ownership_scope IN ('TENANT'));

COMMENT ON TABLE ab_authoring_tenant_override IS
    'Tenant-owned lineage for inherited platform/application PageSchema authoring';
COMMENT ON COLUMN ab_page_schema.ownership_scope IS
    'Declared source ownership: PLATFORM, APPLICATION, or TENANT';
COMMENT ON COLUMN ab_authoring_resource_draft.override_pid IS
    'Tenant override lineage pid; shared source PageSchema rows are never mutated by authoring';
