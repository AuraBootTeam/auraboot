-- ============================================================================
-- GAP-271 backfill — ab_agent_skill.declared_effects NULL -> '[]'::jsonb
-- ============================================================================
--
-- Closes ACP Phase 1 -> Phase 2 readiness gap for the Declare-layer Effect
-- contract (see {@code docs/agent/contracts/effect-taxonomy.md}
-- "declared_effects 必填的两阶段策略"). Phase 1 (current) treats NULL as
-- equivalent to '[]'::jsonb at runtime; Phase 2 strict mode (owner-gated,
-- >=30 days after v1.4) will add NOT NULL on this column. Pre-Phase-2
-- prerequisite: 100% of existing rows must be backfilled to a concrete JSONB
-- array (empty array is the safe default — runtime treats it as "skill
-- declares no privileged effects"; YAML reimport later supplies the real set
-- via {@code SkillImporter}).
--
-- WHEN TO RUN:
--   - Production / shared-data environments only. Dev environments wipe data
--     via {@code reset-and-init.sh} which re-runs schema.sql and includes the
--     same idempotent UPDATE inline (see schema.sql lines 8071..8079), per
--     AGENTS.md "开发阶段声明" 红线 ("不考虑数据迁移; 直接改 schema.sql").
--
-- HOW TO RUN:
--   psql "$DB_URL" -v ON_ERROR_STOP=1 -f migrations/2026-05-07-gap-271-declared-effects-backfill.sql
--
-- IDEMPOTENCY:
--   The UPDATE filters {@code declared_effects IS NULL}; running this twice
--   is a no-op since the first run flips those rows to '[]'::jsonb. Safe to
--   re-run after subsequent SkillImporter passes; only newly-imported rows
--   that somehow landed with NULL would be touched.
--
-- WHY EMPTY ARRAY (not NOT NULL DEFAULT [] yet):
--   Phase 2 owner-decision adds the NOT NULL constraint. This migration
--   establishes the precondition for that future ALTER TABLE without itself
--   blocking existing writes that legitimately omit the column today.
--
-- AUDIT FIRST:
--   The first SELECT prints affected-row counts grouped by tenant. Owners
--   should confirm counts match expectations (skills imported pre-v1.4 only)
--   before running the UPDATE.
--
-- VERIFICATION:
--   Step 3 must return zero rows after a successful backfill — that is the
--   Phase 2 readiness signal. If non-zero, investigate which insert path is
--   still producing NULL declared_effects (likely a bypass of SkillImporter).
-- ============================================================================

-- Step 1: Audit — affected row counts grouped by tenant.
SELECT
    tenant_id,
    COUNT(*) AS to_backfill,
    MIN(created_at) AS earliest,
    MAX(created_at) AS latest
FROM ab_agent_skill
WHERE declared_effects IS NULL
GROUP BY tenant_id
ORDER BY tenant_id;

-- Step 2: Idempotent backfill — NULL rows -> '[]'::jsonb (the runtime-equivalent
-- empty effect set; YAML reimport via SkillImporter will overwrite with real
-- declared_effects when source contracts gain the field).
UPDATE ab_agent_skill
SET declared_effects = '[]'::jsonb
WHERE declared_effects IS NULL;

-- Step 3: Verify — must return zero rows after a successful backfill across
-- all tenants. Non-zero means some insert path bypasses SkillImporter and is
-- still producing NULL declared_effects; investigate before flipping Phase 2
-- NOT NULL constraint.
SELECT
    tenant_id,
    COUNT(*) AS still_null
FROM ab_agent_skill
WHERE declared_effects IS NULL
GROUP BY tenant_id
HAVING COUNT(*) > 0
ORDER BY tenant_id;
