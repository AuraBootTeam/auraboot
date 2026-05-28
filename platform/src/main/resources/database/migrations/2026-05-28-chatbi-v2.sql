-- ============================================================================
-- 2026-05-28 — ChatBI v2 (IDA P0-2)
-- ============================================================================
--
-- PRD: ida/docs/17-prd-chatbi-v2.md §5
--
-- Introduces 4 tables backing the v2 NL → Token → SemanticQueryRequest pipe.
-- The 5th table (chatbi_disambiguation_log) lands in W3 alongside the
-- DisambiguationService.
--
-- Notes:
--   * No worksheet table — v2 reuses ab_semantic_model as the catalog.
--   * tenant_id NULL is *meaningful* for chatbi_token_dict (global default).
--   * All tables use BIGINT PK + VARCHAR(32) ULID pid (where applicable).
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. chatbi_answer — archived single answer
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS chatbi_answer (
    id                      BIGINT PRIMARY KEY,
    pid                     VARCHAR(32) NOT NULL,
    tenant_id               BIGINT NOT NULL,
    user_id                 BIGINT NOT NULL,
    conversation_pid        VARCHAR(32),
    semantic_model_pid      VARCHAR(32) NOT NULL,
    nl_query                TEXT NOT NULL,
    tokens_json             JSONB NOT NULL,
    semantic_request_json   JSONB NOT NULL,
    sql_hash                VARCHAR(64),
    viz_type                VARCHAR(32),
    viz_config_json         JSONB,
    row_count               INTEGER,
    duration_ms             INTEGER,
    llm_used                VARCHAR(64),
    llm_cost_cents          NUMERIC(10, 4),
    status                  VARCHAR(16) NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_chatbi_answer_pid
    ON chatbi_answer (pid);
CREATE INDEX IF NOT EXISTS idx_chatbi_answer_user
    ON chatbi_answer (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_chatbi_answer_model
    ON chatbi_answer (semantic_model_pid);

-- ----------------------------------------------------------------------------
-- 2. chatbi_conversation — multi-turn state
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS chatbi_conversation (
    id                      BIGINT PRIMARY KEY,
    pid                     VARCHAR(32) NOT NULL,
    tenant_id               BIGINT NOT NULL,
    user_id                 BIGINT NOT NULL,
    semantic_model_pid      VARCHAR(32),
    messages_json           JSONB NOT NULL,
    context_reset_at        TIMESTAMPTZ,
    token_budget_used       INTEGER NOT NULL DEFAULT 0,
    status                  VARCHAR(16) NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_chatbi_conversation_pid
    ON chatbi_conversation (pid);
CREATE INDEX IF NOT EXISTS idx_chatbi_conversation_user
    ON chatbi_conversation (user_id, created_at DESC);

-- ----------------------------------------------------------------------------
-- 3. chatbi_llm_audit — cost + latency audit
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS chatbi_llm_audit (
    id                      BIGINT PRIMARY KEY,
    tenant_id               BIGINT NOT NULL,
    answer_pid              VARCHAR(32) NOT NULL,
    conversation_pid        VARCHAR(32),
    model                   VARCHAR(64) NOT NULL,
    prompt_tokens           INTEGER,
    completion_tokens       INTEGER,
    total_tokens            INTEGER,
    cost_cents              NUMERIC(10, 4),
    latency_ms              INTEGER,
    success                 BOOLEAN,
    error_code              VARCHAR(64),
    ts                      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_chatbi_audit_tenant_ts
    ON chatbi_llm_audit (tenant_id, ts DESC);
CREATE INDEX IF NOT EXISTS idx_chatbi_audit_model
    ON chatbi_llm_audit (model);
CREATE INDEX IF NOT EXISTS idx_chatbi_audit_answer
    ON chatbi_llm_audit (answer_pid);

-- ----------------------------------------------------------------------------
-- 4. chatbi_token_dict — operator-maintained synonyms
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS chatbi_token_dict (
    id                      BIGINT PRIMARY KEY,
    tenant_id               BIGINT,
    term                    VARCHAR(128) NOT NULL,
    resolves_to_type        VARCHAR(16) NOT NULL,
    resolves_to_code        VARCHAR(128) NOT NULL,
    priority                INTEGER NOT NULL DEFAULT 0,
    source                  VARCHAR(16),
    approved_by_user_id     BIGINT
);
-- Composite unique (NULL tenant_id is treated as distinct in PG — fine for
-- the "global default" pattern, and tenant overrides are guarded by priority).
CREATE UNIQUE INDEX IF NOT EXISTS uk_chatbi_token_dict_term
    ON chatbi_token_dict (tenant_id, term, resolves_to_type);
CREATE INDEX IF NOT EXISTS idx_chatbi_token_dict_term
    ON chatbi_token_dict (term);
