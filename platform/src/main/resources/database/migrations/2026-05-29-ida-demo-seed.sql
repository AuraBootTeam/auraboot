-- ============================================================================
-- 2026-05-29 — IDA P0 demo seed (ord_sales_order + user_attribute)
-- ============================================================================
--
-- Provides small fixture data so the canonical sales.semantic.yml in
-- platform/src/test/resources/semantic/valid/ executes end-to-end against a
-- fresh --e2e stack without manual psql seeding. Used by:
--
-- - B1.1.5 RLS sales.yml smoke (ida/docs/25 §B.3.1)
-- - Customer demo script ida/docs/19 §7 ("admin sees CN+US, not EU")
-- - Backlog M6 UAT acceptance
--
-- Idempotent: ON CONFLICT DO NOTHING. Re-running is safe.
--
-- NOTE: ord_sales_order is created here as a stand-in physical table so
-- sales.semantic.yml's model_ref resolves. Real MetaModel-managed tables
-- follow a different DDL path (autoCreateDefaultPages etc.); this fixture
-- is for SMOKE ONLY and should not be referenced in production.
-- ============================================================================

CREATE TABLE IF NOT EXISTS ord_sales_order (
    id           BIGINT PRIMARY KEY,
    tenant_id    BIGINT NOT NULL,
    customer_id  BIGINT,
    product_id   BIGINT,
    order_date   TIMESTAMPTZ NOT NULL,
    region_code  VARCHAR(16),
    status       VARCHAR(32),
    channel_code VARCHAR(16),
    amount       NUMERIC(12,2)
);

CREATE INDEX IF NOT EXISTS idx_ord_sales_tenant_date
    ON ord_sales_order (tenant_id, order_date DESC);
