-- ============================================================================
-- 2026-06-12 - Product(Platform)Account Registry + Tenant binding (billing customer portal, Phase A)
-- ============================================================================
-- Design: aura-billing/docs/design/2026-06-12-billing-endgame-domain-model-and-customer-portal.md
--   §6.1 ab_platform_account              = Product(Platform)Account Registry (identity only, NO customer_id — D1 boundary)
--   §6.2 ab_tenant_platform_account_binding = Tenant <-> Product(Platform)Account (D4/D9)
--
-- These are OSS platform-framework tables: the identity spine that connects a
-- logged-in Tenant to the commercial billing graph. They live in OSS because
-- account_id is consumed by OSS quota/metering and the OSS<-enterprise boundary
-- forbids OSS referencing enterprise. Registry stores identity ONLY; commercial
-- ownership (customer) lives enterprise-side (see ab_billing_customer_product_account_binding).
--
-- Stored statuses/roles are constrained here. Add Java enums in the account
-- service slice before introducing ORM writes against these tables.
--
-- Naming convention: prefix ab_platform_ / ab_tenant_platform_
-- ID strategy: BIGINT PRIMARY KEY (snowflake via IdType.ASSIGN_ID - NOT SERIAL)
-- ============================================================================

-- ---------------------------------------------------------------------------
-- Product(Platform)Account Registry — the canonical home for the previously
-- bare account_id used across quota/metering/subscription/invoice.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ab_platform_account (
    id              BIGINT       PRIMARY KEY,          -- = account_id (snowflake, IdType.ASSIGN_ID)
    account_code    VARCHAR(128) NOT NULL,             -- human-readable: PA-YYYYMMDD-xxxx
    account_name    VARCHAR(255),
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE / SUSPENDED / CLOSED
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_platform_account_code UNIQUE (account_code),
    CONSTRAINT chk_platform_account_status CHECK (status IN (
        'ACTIVE', 'SUSPENDED', 'CLOSED'
    ))
);

-- ---------------------------------------------------------------------------
-- Tenant <-> Product(Platform)Account binding (terminal M:N; v1 collapses to
-- one ACTIVE + default-for-self-service per tenant via partial unique index).
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ab_tenant_platform_account_binding (
    id                            BIGINT       PRIMARY KEY,
    tenant_id                     BIGINT       NOT NULL,   -- value-ref ab_tenant.id
    account_id                    BIGINT       NOT NULL,   -- value-ref ab_platform_account.id
    binding_role                  VARCHAR(32)  NOT NULL DEFAULT 'OWNER',  -- OWNER / ADMIN / VIEWER / RESELLER / PAYER_ADMIN (v1: OWNER only)
    is_default_for_self_service   BOOLEAN      NOT NULL DEFAULT TRUE,
    status                        VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE / SUSPENDED / INACTIVE
    effective_from                TIMESTAMPTZ,
    effective_to                  TIMESTAMPTZ,
    created_at                    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at                    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_tpab_role CHECK (binding_role IN (
        'OWNER', 'ADMIN', 'VIEWER', 'RESELLER', 'PAYER_ADMIN'
    )),
    CONSTRAINT chk_tpab_status CHECK (status IN (
        'ACTIVE', 'SUSPENDED', 'INACTIVE'
    )),
    CONSTRAINT chk_tpab_effective_window CHECK (
        effective_to IS NULL OR effective_from IS NULL OR effective_to >= effective_from
    )
);

-- v1 constraint (D4): at most ONE active default Product(Platform)Account per tenant.
-- Postgres partial unique index. (MySQL equivalent = generated column + unique; see design §6.2.)
CREATE UNIQUE INDEX IF NOT EXISTS uq_tpab_tenant_active_default
    ON ab_tenant_platform_account_binding (tenant_id)
    WHERE status = 'ACTIVE' AND is_default_for_self_service;

-- Lookup index for the resolver (tenant_id -> binding).
CREATE INDEX IF NOT EXISTS idx_tpab_tenant ON ab_tenant_platform_account_binding (tenant_id);
CREATE INDEX IF NOT EXISTS idx_tpab_account ON ab_tenant_platform_account_binding (account_id);

COMMENT ON TABLE  ab_platform_account IS 'Product platform account registry; canonical identity for account_id used by OSS billing/quota/metering';
COMMENT ON COLUMN ab_platform_account.id IS 'Canonical account_id; snowflake assigned by application code';
COMMENT ON COLUMN ab_platform_account.account_code IS 'Human-readable stable account code';

COMMENT ON TABLE  ab_tenant_platform_account_binding IS 'Tenant to product platform account binding used by self-service billing resolution';
COMMENT ON COLUMN ab_tenant_platform_account_binding.is_default_for_self_service IS 'Marks the default account resolver target for tenant self-service flows';
