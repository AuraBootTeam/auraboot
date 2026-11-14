-- ============================================================================
-- Phase D.5 backfill — historical sender_type='system'+sender_id=0 → 'agent'
-- ============================================================================
--
-- Closes v3.3 Q8 historical drift in {@code ab_im_message}. After Phase D.2
-- (ImAiService refactor) and Phase B.1 (AuraBotTurnPersistence) AuraBot AI
-- response rows are written with {@code sender_type='agent'} +
-- {@code sender_id=<aurabot_agent_id>}. Pre-D.2 rows are
-- {@code sender_type='system'} + {@code sender_id=0} — analytics + frontend
-- renderers have to special-case both shapes until backfilled.
--
-- WHEN TO RUN:
--   - Production / shared-data environments only. Dev environments wipe data
--     via {@code reset-and-init.sh}, which makes backfill moot per AGENTS.md
--     "开发阶段声明" 红线 ("不考虑数据迁移; 直接改 schema.sql").
--
-- HOW TO RUN:
--   psql "$DB_URL" -v ON_ERROR_STOP=1 -f migrations/2026-04-30-d5-sender-type-backfill.sql
--
-- IDEMPOTENCY:
--   The UPDATE filters {@code sender_type='system' AND sender_id=0 AND
--   message_type='ai_response'}; running this twice is a no-op since the
--   first run flips those rows to {@code sender_type='agent'}.
--
-- SCOPE FILTER (anti-clobber):
--   {@code message_type='ai_response'} is the discriminator. Other system
--   messages (recall notices, system join/leave events, error notifications)
--   keep {@code message_type='system'} or other values and are left alone.
--
-- AUDIT FIRST:
--   The first SELECT prints affected-row counts grouped by tenant. Owners
--   should review tenant-by-tenant counts before running the UPDATE; pause
--   if any single tenant has an order-of-magnitude higher count than expected
--   (could indicate a non-AuraBot system message stream that escaped the
--   filter).
-- ============================================================================

-- Step 1: Audit — affected row counts grouped by tenant.
SELECT
    m.tenant_id,
    COUNT(*) AS to_backfill,
    MIN(m.created_at) AS earliest,
    MAX(m.created_at) AS latest,
    -- Whether the tenant has an aurabot AgentDefinition row to point at:
    (SELECT id FROM ab_agent_definition d
        WHERE d.tenant_id = m.tenant_id AND d.agent_code = 'aurabot' LIMIT 1) AS aurabot_agent_id
FROM ab_im_message m
WHERE m.sender_type = 'system'
  AND m.sender_id = 0
  AND m.message_type = 'ai_response'
GROUP BY m.tenant_id
ORDER BY m.tenant_id;

-- Step 2: Per-tenant UPDATE.
--
-- Skips tenants without an active aurabot AgentDefinition row — those need
-- the AgentDefinition seeded first (AuraBotAgentResolver.resolve() does this
-- lazily on first runtime call, so cold-DB tenants typically have one once
-- the chokepoint has been exercised at least once).
DO $$
DECLARE
    rec RECORD;
    aurabot_id BIGINT;
    updated_count INT;
BEGIN
    FOR rec IN
        SELECT DISTINCT tenant_id FROM ab_im_message
        WHERE sender_type = 'system' AND sender_id = 0 AND message_type = 'ai_response'
    LOOP
        SELECT id INTO aurabot_id FROM ab_agent_definition
        WHERE tenant_id = rec.tenant_id AND agent_code = 'aurabot' LIMIT 1;
        IF aurabot_id IS NULL THEN
            RAISE NOTICE 'Tenant %: no aurabot AgentDefinition row; skipping (seed via AuraBotAgentResolver first)', rec.tenant_id;
            CONTINUE;
        END IF;
        UPDATE ab_im_message SET
            sender_type = 'agent',
            sender_id = aurabot_id
        WHERE tenant_id = rec.tenant_id
          AND sender_type = 'system'
          AND sender_id = 0
          AND message_type = 'ai_response';
        GET DIAGNOSTICS updated_count = ROW_COUNT;
        RAISE NOTICE 'Tenant %: backfilled % rows -> agent_id %', rec.tenant_id, updated_count, aurabot_id;
    END LOOP;
END $$;

-- Step 3: Verify — should return 0 rows after a successful backfill across
-- all tenants that have an aurabot AgentDefinition.
SELECT
    m.tenant_id,
    COUNT(*) AS still_legacy
FROM ab_im_message m
WHERE m.sender_type = 'system'
  AND m.sender_id = 0
  AND m.message_type = 'ai_response'
GROUP BY m.tenant_id
HAVING COUNT(*) > 0
ORDER BY m.tenant_id;
