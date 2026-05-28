-- ============================================================================
-- 2026-05-29 — User Attributes for Semantic Layer RLS (B.3.1)
-- ============================================================================
--
-- Backing table for {@code AccessPolicy.user_attribute} resolution in the
-- semantic layer. Each row maps a (tenant, user, attribute_code) tuple to
-- a value, used by the AccessPolicyCompiler when injecting RLS WHERE
-- clauses like {@code region_code IN ({user.allowed_regions})}.
--
-- Multi-value attributes (e.g. allowed_regions = "CN, US") are stored as
-- a single comma-separated string; the compiler splits and binds each
-- value as a separate prepared-statement parameter.
--
-- PRD reference: ida/docs/16-prd-semantic-yml-dsl.md §3 access_policies
-- Reflection: ida/docs/25-session-final-reflection.md §2 (Phase 0 误判 #2 — this
-- table did NOT exist despite PRD §11.1 claiming "RoleAttributeService (已有)").
-- ============================================================================

CREATE TABLE IF NOT EXISTS ab_user_attribute (
    id              BIGINT PRIMARY KEY,
    pid             VARCHAR(32) NOT NULL,
    tenant_id       BIGINT NOT NULL,
    user_id         BIGINT NOT NULL,
    attribute_code  VARCHAR(64) NOT NULL,    -- e.g. "allowed_regions", "department_code"
    attribute_value TEXT,                    -- single value or comma-separated list
    description     VARCHAR(255),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by      BIGINT,
    updated_by      BIGINT,
    deleted_flag    BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_user_attribute_pid
    ON ab_user_attribute (pid) WHERE deleted_flag = FALSE;
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_attribute_code
    ON ab_user_attribute (tenant_id, user_id, attribute_code) WHERE deleted_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_user_attribute_user
    ON ab_user_attribute (tenant_id, user_id) WHERE deleted_flag = FALSE;
