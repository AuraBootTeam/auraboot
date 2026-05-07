-- ============================================================================
-- Admin Guard v2 — generic admin action audit log
-- ============================================================================
--
-- Closes backlog item #5 in {@code docs/backlog/2026-04-19-usp-memory-l1l2-followups.md}.
-- Adds {@code ab_admin_action_log} so {@code AdminRoleInterceptor.afterCompletion}
-- can record every {@code /api/admin/**} request (accepted or rejected) to a
-- single generic audit trail. USP retains its own {@code
-- ab_agent_user_soul_profile_admin_action} table for business-semantic fields
-- ({@code target_user_id}, {@code reason}); both tables coexist.
--
-- WHEN TO RUN:
--   - Auto-applied via {@code reset-and-init.sh} on dev environments.
--   - Production / shared envs: run via the project's migration runner.
--
-- HOW TO RUN (manual, if needed):
--   psql "$DB_URL" -v ON_ERROR_STOP=1 -f migrations/2026-05-07_admin_guard_v2.sql
--
-- IDEMPOTENCY:
--   {@code CREATE TABLE IF NOT EXISTS} + {@code CREATE INDEX IF NOT EXISTS}
--   makes this safe to re-run; skips work after first apply.
--
-- ROLE NOTE (#2):
--   The {@code platform_admin} role is intentionally NOT created here. Role
--   rows in {@code ab_role} require app-generated {@code id} (BIGINT) and
--   {@code pid} (VARCHAR(26) ULID) populated by {@code UniqueIdGenerator},
--   so they're seeded via the bootstrap template path
--   ({@code platform/src/main/resources/tenant-templates/default-bootstrap.json}),
--   which {@code TenantBootstrapServiceImpl#createRoles} consumes. See spec
--   {@code docs/plans/2026-05/2026-05-07-admin-guard-v2-and-followups-design.md}
--   §4.1 for the architectural rationale.
-- ============================================================================

-- (#5) ab_admin_action_log — generic admin operation audit trail
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
