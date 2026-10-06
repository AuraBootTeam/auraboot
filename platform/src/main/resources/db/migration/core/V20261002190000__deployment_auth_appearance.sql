-- Deployment control-plane state. Tenant filtering is scoped explicitly at service call sites.
CREATE TABLE ab_auth_appearance_state (
    id BIGINT PRIMARY KEY CHECK (id = 1),
    version BIGINT NOT NULL DEFAULT 0,
    published_version BIGINT NOT NULL DEFAULT 0,
    draft JSONB,
    published JSONB
);
INSERT INTO ab_auth_appearance_state(id) VALUES (1);
CREATE TABLE ab_auth_appearance_revision (
    version BIGINT PRIMARY KEY,
    action VARCHAR(32) NOT NULL CHECK (action IN ('save', 'publish', 'rollback')),
    snapshot JSONB NOT NULL,
    actor_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
