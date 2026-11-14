-- ============================================================================
-- 2026-06-10 — Billing Resource Catalog (M1 slice-1)
-- ============================================================================
--
-- Creates the canonical registry of all billing/quota resource types used
-- by the quota and metering modules.  This table is read-only at runtime;
-- rows are seeded here and extended via the admin write path.
--
-- Enum values must match Java source verbatim:
--   MeteringMode      : com.auraboot.framework.billing.catalog.model.MeteringMode
--   QuotaMode         : com.auraboot.framework.billing.catalog.model.QuotaMode
--   ResourceCategory  : com.auraboot.framework.billing.catalog.model.ResourceCategory
--
-- Naming convention: prefix ab_billing_  (all billing-module tables share this prefix)
-- ID strategy: BIGINT PRIMARY KEY (snowflake via IdType.ASSIGN_ID — NOT SERIAL)
--   ↳ see AbUserAttribute / ab_user_attribute as canonical precedent
-- ============================================================================

CREATE TABLE IF NOT EXISTS ab_billing_resource_catalog (
    id                  BIGINT PRIMARY KEY,
    resource_code       VARCHAR(64)     NOT NULL,
    resource_name       VARCHAR(128)    NOT NULL,
    unit                VARCHAR(32)     NOT NULL,

    -- com.auraboot.framework.billing.catalog.model.ResourceCategory
    category            VARCHAR(32)     NOT NULL,

    -- com.auraboot.framework.billing.catalog.model.MeteringMode
    metering_mode       VARCHAR(32)     NOT NULL,

    -- com.auraboot.framework.billing.catalog.model.QuotaMode
    quota_mode          VARCHAR(32)     NOT NULL,

    -- Optional: raw-unit → billing-unit conversion factor
    -- e.g. 1_000_000 to convert token count → M-token billing unit
    -- NULL means the raw value is already in the billing unit
    conversion_factor   DECIMAL(24, 6),

    -- ACTIVE | DEPRECATED | RETIRED
    status              VARCHAR(32)     NOT NULL DEFAULT 'ACTIVE',

    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    -- CHECK constraints mirror Java enum values exactly
    CONSTRAINT chk_billing_rc_metering_mode CHECK (metering_mode IN (
        'SNAPSHOT', 'EVENT', 'CONFIG', 'HEARTBEAT'
    )),
    CONSTRAINT chk_billing_rc_quota_mode CHECK (quota_mode IN (
        'STOCK', 'PERIODIC', 'RATE', 'ENTITLEMENT', 'LICENSE'
    )),
    CONSTRAINT chk_billing_rc_category CHECK (category IN (
        'LOW_CODE', 'AUTOMATION', 'AI', 'INTEGRATION', 'STORAGE',
        'ACCOUNT', 'GOVERNANCE', 'MARKETPLACE', 'LICENSE'
    )),
    CONSTRAINT chk_billing_rc_status CHECK (status IN (
        'ACTIVE', 'DEPRECATED', 'RETIRED'
    ))
);

-- ─────────────────────────────────────────────────────────────────────────────
-- Unique index on resource_code (the stable external key)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE UNIQUE INDEX IF NOT EXISTS uq_billing_rc_resource_code
    ON ab_billing_resource_catalog (resource_code);

-- Category lookup (used by listActive ordering and future quota queries)
CREATE INDEX IF NOT EXISTS idx_billing_rc_category_code
    ON ab_billing_resource_catalog (category, resource_code);

COMMENT ON TABLE  ab_billing_resource_catalog IS 'Canonical registry of billing/quota resource types';
COMMENT ON COLUMN ab_billing_resource_catalog.resource_code
    IS 'Stable machine-readable code; referenced by quota definitions and metering events';
COMMENT ON COLUMN ab_billing_resource_catalog.conversion_factor
    IS 'Optional raw→billing unit multiplier (NULL = raw value is already in billing unit)';

-- ─────────────────────────────────────────────────────────────────────────────
-- Idempotent seed — 13 standard platform resources
-- Uses ON CONFLICT DO NOTHING so re-running the migration is safe.
-- IDs are assigned here as fixed BIGINT constants (stable, not snowflake) so
-- the seed is reproducible across fresh installs.
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ab_billing_resource_catalog
    (id, resource_code, resource_name, unit, category, metering_mode, quota_mode, conversion_factor, status)
VALUES
    -- Low-code
    (1000000001, 'APP_COUNT',               '应用数',           'COUNT', 'LOW_CODE',     'SNAPSHOT',  'STOCK',        NULL,           'ACTIVE'),
    (1000000002, 'FORM_COUNT',              '表单数',           'COUNT', 'LOW_CODE',     'SNAPSHOT',  'STOCK',        NULL,           'ACTIVE'),
    -- Automation
    (1000000003, 'WORKFLOW_EXECUTION',      '流程执行次数',     'COUNT', 'AUTOMATION',   'EVENT',     'PERIODIC',     NULL,           'ACTIVE'),
    -- AI
    (1000000004, 'AI_TOKEN',               'AI tokens',         'TOKEN', 'AI',           'EVENT',     'PERIODIC',     NULL,           'ACTIVE'),
    (1000000005, 'AI_COPILOT_CALL',        'Copilot 调用',     'COUNT', 'AI',           'EVENT',     'PERIODIC',     NULL,           'ACTIVE'),
    (1000000006, 'KNOWLEDGE_RETRIEVAL',    '知识库检索',       'COUNT', 'AI',           'EVENT',     'PERIODIC',     NULL,           'ACTIVE'),
    -- Integration
    (1000000007, 'API_CALL',               'API 调用',         'COUNT', 'INTEGRATION',  'EVENT',     'PERIODIC',     NULL,           'ACTIVE'),
    -- Storage
    (1000000008, 'STORAGE_GB',             '存储容量',         'GB',    'STORAGE',      'SNAPSHOT',  'STOCK',        NULL,           'ACTIVE'),
    -- Account
    (1000000009, 'SEAT',                   '席位',             'COUNT', 'ACCOUNT',      'SNAPSHOT',  'STOCK',        NULL,           'ACTIVE'),
    -- Governance
    (1000000010, 'AUDIT_RETENTION_DAY',    '审计保留',         'DAY',   'GOVERNANCE',   'CONFIG',    'ENTITLEMENT',  NULL,           'ACTIVE'),
    -- Marketplace
    (1000000011, 'PLUGIN_CALL',            '插件调用',         'COUNT', 'MARKETPLACE',  'EVENT',     'PERIODIC',     NULL,           'ACTIVE'),
    -- License (self-hosted)
    (1000000012, 'INSTANCE_COUNT',         '自托管实例',       'COUNT', 'LICENSE',      'HEARTBEAT', 'LICENSE',      NULL,           'ACTIVE'),
    (1000000013, 'NODE_COUNT',             '自托管节点',       'COUNT', 'LICENSE',      'HEARTBEAT', 'LICENSE',      NULL,           'ACTIVE')
ON CONFLICT (resource_code) DO NOTHING;

-- TODO(i18n): resource_name is stored as UTF-8 Chinese text for now.
-- Migrate to i18n keys (e.g. $i18n:billing.resource.APP_COUNT) once the
-- platform's LocalizedText / $i18n: layer is wired into catalog reads.
