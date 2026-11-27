-- Explicit empty selection is distinct from an existing role's legacy navigation rules.
CREATE UNIQUE INDEX IF NOT EXISTS ux_ab_role_navigation_owner ON ab_role(tenant_id,id);
CREATE TABLE ab_role_navigation_state (
    tenant_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    PRIMARY KEY(tenant_id,role_id),
    FOREIGN KEY(tenant_id,role_id) REFERENCES ab_role(tenant_id,id) ON DELETE CASCADE
);
CREATE TABLE ab_role_navigation_menu (
    tenant_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    menu_pid VARCHAR(32) NOT NULL,
    PRIMARY KEY(tenant_id,role_id,menu_pid),
    FOREIGN KEY(tenant_id,role_id) REFERENCES ab_role_navigation_state(tenant_id,role_id) ON DELETE CASCADE
);
CREATE INDEX ix_role_navigation_menu ON ab_role_navigation_menu(tenant_id,menu_pid);
COMMENT ON TABLE ab_role_navigation_state IS 'Tenant role navigation selection; absence retains marked legacy rules, presence permits an explicit empty selection.';
COMMENT ON TABLE ab_role_navigation_menu IS 'Navigation visibility only; never a source of business permission or record scope.';

CREATE TABLE ab_role_navigation_audit (
    pid VARCHAR(26) PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES ab_tenant(id),
    role_pid VARCHAR(32) NOT NULL,
    actor_id BIGINT NOT NULL,
    before_selection JSONB NOT NULL,
    after_selection JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_role_navigation_audit ON ab_role_navigation_audit(tenant_id,role_pid,created_at DESC);
