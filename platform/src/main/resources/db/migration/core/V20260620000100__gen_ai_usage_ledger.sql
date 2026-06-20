-- GenAiUsageRecord: durable LLM usage/cost ledger — the billing source of truth
-- (A-G6, P1; SoT §2.5). Kept separate from ab_ai_trace_span (diagnostic token
-- attributes, sampleable) so billing/quota is never summed from sampled spans.
-- Additive + idempotent.
CREATE TABLE IF NOT EXISTS ab_gen_ai_usage (
    id                   BIGSERIAL PRIMARY KEY,
    tenant_id            BIGINT NOT NULL,
    run_id               VARCHAR(64),
    trace_id             VARCHAR(36),
    span_id              VARCHAR(36),
    provider             VARCHAR(40),
    request_model        VARCHAR(120),
    response_model       VARCHAR(120),
    input_tokens         INT DEFAULT 0,
    output_tokens        INT DEFAULT 0,
    cache_read_tokens    INT DEFAULT 0,
    cache_write_tokens   INT DEFAULT 0,
    reasoning_tokens     INT DEFAULT 0,
    amount               DECIMAL(14,6) DEFAULT 0,
    currency             VARCHAR(8) DEFAULT 'USD',
    pricing_version      VARCHAR(32),
    created_at           TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE ab_gen_ai_usage IS 'Durable LLM usage/cost ledger (A-G6) — billing source of truth, not derived from sampled OTel spans';

CREATE INDEX IF NOT EXISTS idx_ab_gen_ai_usage_tenant_created ON ab_gen_ai_usage (tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_ab_gen_ai_usage_trace ON ab_gen_ai_usage (trace_id) WHERE trace_id IS NOT NULL;
