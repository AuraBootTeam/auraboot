-- ============================================================================
-- Phase C.2 — cross-tenant sub-agent ACL: grant + audit tables
-- ============================================================================
--
-- Adds the two tables introduced by the C.2 design doc
-- ({@code docs/superpowers/specs/2026-05-07-c2-cross-tenant-acl-design.md}):
--   * {@code ab_cross_tenant_grant}        — single source of truth for which
--                                             {@code (parent_tenant, child_tenant)}
--                                             pairs may spawn cross-tenant child
--                                             runs. Default-deny: a missing /
--                                             expired / revoked row blocks the
--                                             spawn.
--   * {@code ab_cross_tenant_spawn_audit}  — one row per spawn decision so an
--                                             operator can prove every grant was
--                                             actually exercised (or detect a
--                                             flood of denied attempts).
--
-- WHEN TO RUN:
--   - Production / shared-data environments. Dev environments wipe data via
--     {@code reset-and-init.sh} which re-applies schema.sql; this migration
--     stays available for any environment that opts out of the reset path.
--
-- HOW TO RUN:
--   psql "$DB_URL" -v ON_ERROR_STOP=1 \
--        -f migrations/2026-05-07-c2-cross-tenant-grant.sql
--
-- IDEMPOTENCY:
--   All DDL uses {@code IF NOT EXISTS}. Re-running this script a second time
--   prints PostgreSQL "relation/index already exists, skipping" notices and
--   leaves the schema untouched. The partial unique index keys on the same
--   {@code (parent_tenant_id, child_tenant_id, grant_type) WHERE revoked_at
--   IS NULL} expression as schema.sql, so subsequent runs do not duplicate
--   it.
--
-- WORKTREE-ISOLATION SAFETY:
--   This migration is purely additive ({@code CREATE TABLE / CREATE INDEX
--   IF NOT EXISTS}); applying it from one worktree does not collide with
--   sibling worktrees that have not yet seen the C.2 source code (the
--   sibling app simply does not query the new tables). No {@code DROP},
--   no column-type change, no constraint flip — safe to apply on the
--   shared host PG per the worktree-isolation rule.
-- ============================================================================

CREATE TABLE IF NOT EXISTS ab_cross_tenant_grant (
    id               BIGSERIAL PRIMARY KEY,
    parent_tenant_id BIGINT NOT NULL,
    child_tenant_id  BIGINT NOT NULL,
    grant_type       VARCHAR(20) NOT NULL,
    granted_by       BIGINT NOT NULL,
    granted_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ,
    revoked_at       TIMESTAMPTZ,
    revoked_by       BIGINT,
    note             TEXT
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_cross_tenant_grant_active_unique
    ON ab_cross_tenant_grant (parent_tenant_id, child_tenant_id, grant_type)
    WHERE revoked_at IS NULL;

CREATE TABLE IF NOT EXISTS ab_cross_tenant_spawn_audit (
    id               BIGSERIAL PRIMARY KEY,
    grant_id         BIGINT REFERENCES ab_cross_tenant_grant(id),
    parent_tenant_id BIGINT NOT NULL,
    child_tenant_id  BIGINT NOT NULL,
    parent_run_pid   VARCHAR(26) NOT NULL,
    child_run_pid    VARCHAR(26),
    decision         VARCHAR(32) NOT NULL,
    spawn_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    error_message    TEXT
);

CREATE INDEX IF NOT EXISTS ix_xtg_audit_parent_time
    ON ab_cross_tenant_spawn_audit(parent_tenant_id, spawn_at);
CREATE INDEX IF NOT EXISTS ix_xtg_audit_child_time
    ON ab_cross_tenant_spawn_audit(child_tenant_id,  spawn_at);
