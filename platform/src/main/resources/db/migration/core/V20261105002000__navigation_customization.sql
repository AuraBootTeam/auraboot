CREATE TABLE ab_navigation_customization (
    tenant_id BIGINT NOT NULL REFERENCES ab_tenant(id),
    menu_pid VARCHAR(26) NOT NULL,
    definition JSONB NOT NULL CHECK (jsonb_typeof(definition) = 'object'),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, menu_pid)
);
CREATE TABLE ab_navigation_audit (
    pid VARCHAR(26) PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES ab_tenant(id),
    menu_pid VARCHAR(26) NOT NULL,
    actor_id BIGINT NOT NULL,
    action VARCHAR(24) NOT NULL,
    before_definition JSONB,
    after_definition JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_navigation_audit_tenant ON ab_navigation_audit(tenant_id, created_at DESC);
