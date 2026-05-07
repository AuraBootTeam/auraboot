-- =============================================================================
-- Admin Guard v2 (2026-05-07)
-- - #2 platform_admin role
-- - #5 generic admin action audit log
-- =============================================================================

-- (#2) platform_admin role
INSERT INTO ab_role (code, name, system_role, status, created_at)
VALUES ('platform_admin', 'Platform Administrator', TRUE, 'active', NOW())
ON CONFLICT (code) DO NOTHING;

-- (#5) ab_admin_action_log generic audit table
CREATE TABLE IF NOT EXISTS ab_admin_action_log (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    actor_user_id VARCHAR(64) NOT NULL,
    actor_role VARCHAR(32) NOT NULL,
    path VARCHAR(512) NOT NULL,
    method VARCHAR(8) NOT NULL,
    status INTEGER NOT NULL,
    request_body_summary VARCHAR(2048),
    latency_ms INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_admin_action_log_tenant_time
    ON ab_admin_action_log (tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_admin_action_log_actor_time
    ON ab_admin_action_log (actor_user_id, created_at DESC);
