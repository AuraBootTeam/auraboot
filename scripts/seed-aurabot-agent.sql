-- ============================================================
-- AuraBot Agent Definition Seed (GAP-296)
-- Run: psql -h localhost -U ghj -d aura_boot -f scripts/seed-aurabot-agent.sql
-- Idempotent: ON CONFLICT DO NOTHING per (tenant_id, agent_code) unique index
-- Per-tenant: inserts one aurabot row for each active, non-deleted tenant
-- ============================================================
--
-- AuraBotAgentResolverImpl carries an inline lazy-seed fallback (LAZY_SEED_AURABOT)
-- so legacy tenants without a bootstrap row still resolve. This script is the
-- *positive* bootstrap path: every tenant gets an aurabot row at reset/init time
-- so resolver hot-paths never hit the lazy branch.
--
-- The agent definition values MUST stay in sync with
-- AuraBotAgentResolverImpl.LAZY_SEED_AURABOT — divergence would let the same
-- agent_code resolve to two different row shapes depending on which path
-- created the row (bootstrap vs lazy).

INSERT INTO ab_agent_definition (
    pid, tenant_id, agent_code, name, description,
    agent_type, model, system_prompt,
    max_tools, max_concurrent_runs, execution_timeout_seconds,
    status, visibility, deleted_flag,
    created_at, updated_at
)
SELECT
    'aurabot_' || t.id,
    t.id,
    'aurabot',
    'AuraBot',
    'Platform-native AI assistant with full access to all models, commands, queries, and platform tools.',
    'reactive',
    'claude-sonnet-4-6',
    'You are AuraBot, the intelligent assistant embedded in this platform. You have full access to all data models, commands, queries, and platform tools. Help users accomplish their business tasks efficiently, accurately, and with clear explanations.',
    20, 3, 300,
    'active', 'tenant', FALSE,
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM ab_tenant t
WHERE (t.deleted_flag = FALSE OR t.deleted_flag IS NULL)
ON CONFLICT DO NOTHING;
