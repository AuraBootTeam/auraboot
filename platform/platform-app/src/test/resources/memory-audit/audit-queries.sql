-- Spike-2 Phase 1: Memory prompt 装配审计 — read-only SQL templates
--
-- ⚠️  READ-ONLY. None of these queries write. All queries are parameterized.
-- ⚠️  Phase 2 runs MUST go against anonymized snapshot, not raw production PG.
--
-- Driver: docs/backlog/2026-05-27-spike-2-memory-prompt-audit-design.md §2
-- Schema reference: ab_agent_memory (schema.sql:5006), ab_agent_observation
-- (5049), ab_agent_memory_access_log (8232).
--
-- Parameters used:
--   :tenant_id      — bigint, current tenant
--   :time_window    — interval, e.g. '30 days'
--   :sample_limit   — int, number of (tenant, agent, user) triples to sample
--   :max_snippets   — int, ActiveMemoryService.MAX_SNIPPETS default

-- =========================================================================
-- Q1: Sample (tenant, agent, user) triples with active memory usage
-- =========================================================================
-- Returns triples meeting:
--   • ≥ 3 memory rows
--   • ≥ 1 access-log hit in the window (proves preRecall ran)
-- Uniformly sampled by tablesample bernoulli — set seed for reproducibility.
SELECT
    m.tenant_id,
    m.memory_agent_id    AS agent_code,
    m.scope_key          AS user_id,
    COUNT(*)             AS memory_count,
    MIN(m.created_at)    AS oldest_memory,
    MAX(m.updated_at)    AS newest_memory
FROM ab_agent_memory m
WHERE m.tenant_id = :tenant_id
  AND (m.deleted_flag IS NULL OR m.deleted_flag = FALSE)
  AND m.scope = 'user'
  AND m.scope_key IS NOT NULL
  AND EXISTS (
      SELECT 1 FROM ab_agent_memory_access_log al
      WHERE al.memory_pid = m.pid
        AND al.last_seen_at > NOW() - CAST(:time_window AS INTERVAL)
  )
GROUP BY m.tenant_id, m.memory_agent_id, m.scope_key
HAVING COUNT(*) >= 3
ORDER BY RANDOM()
LIMIT :sample_limit;

-- =========================================================================
-- Q2: Snippet bundle that ActiveMemoryService.preRecall would emit
-- =========================================================================
-- For one (tenant, agent, user) triple, pull the rows that would surface in
-- the prompt — combines (a) importance-ordered top-N and (b) any keyword-
-- match rows (caller passes :keyword for the keyword pass).
--
-- Mirrors loadScopedByImportance + searchScoped semantics; not literally the
-- same query (those use Mapper SQL).  Audit-side reads only what matters
-- for human conflict annotation.
WITH importance_pass AS (
    SELECT m.pid, m.memory_type, m.memory_title, m.memory_content,
           m.importance, m.created_at, m.updated_at, m.valid_until,
           'importance' AS pass
    FROM ab_agent_memory m
    WHERE m.tenant_id = :tenant_id
      AND m.memory_agent_id = :agent_code
      AND m.scope = 'user'
      AND m.scope_key = :user_id
      AND (m.deleted_flag IS NULL OR m.deleted_flag = FALSE)
      AND (m.valid_until IS NULL OR m.valid_until > NOW())
    ORDER BY m.importance DESC, m.updated_at DESC
    LIMIT :max_snippets
),
keyword_pass AS (
    SELECT m.pid, m.memory_type, m.memory_title, m.memory_content,
           m.importance, m.created_at, m.updated_at, m.valid_until,
           'keyword' AS pass
    FROM ab_agent_memory m
    WHERE m.tenant_id = :tenant_id
      AND m.memory_agent_id = :agent_code
      AND m.scope = 'user'
      AND m.scope_key = :user_id
      AND (m.deleted_flag IS NULL OR m.deleted_flag = FALSE)
      AND (m.valid_until IS NULL OR m.valid_until > NOW())
      AND :keyword <> ''
      AND (m.memory_title ILIKE '%' || :keyword || '%'
           OR m.memory_content ILIKE '%' || :keyword || '%')
    LIMIT :max_snippets
)
SELECT DISTINCT ON (pid) *
FROM (
    SELECT * FROM importance_pass
    UNION ALL
    SELECT * FROM keyword_pass
) merged
ORDER BY pid, pass DESC;  -- prefer 'keyword' label on ties (mirrors preRecall ordering)

-- =========================================================================
-- Q3: LLM extraction call volume — per agent_code histogram
-- =========================================================================
-- Sources: RunLifecycleService.saveRunMemory publishes observation_type =
-- 'memory_saved' for each run (success OR fallback).  Detail column is JSON
-- with task title; token counts are NOT recorded today (gap to file).
SELECT
    obs_agent_id            AS agent_code,
    COUNT(*)                AS extraction_calls,
    MIN(created_at)         AS first_call,
    MAX(created_at)         AS last_call,
    COUNT(*) FILTER (WHERE severity = 'warn')  AS llm_failure_fallbacks
FROM ab_agent_observation
WHERE tenant_id = :tenant_id
  AND observation_type = 'memory_saved'
  AND created_at > NOW() - CAST(:time_window AS INTERVAL)
GROUP BY obs_agent_id
ORDER BY extraction_calls DESC;

-- =========================================================================
-- Q4: Deduplicate hit-rate proxy
-- =========================================================================
-- No metric exists today for deduplicateMemories.  Proxy: per (tenant, agent,
-- user), count distinct normalized memory_content vs total rows.  Low
-- distinct-ratio = many near-duplicates exist (whether dedupe ran or not).
SELECT
    tenant_id, memory_agent_id AS agent_code, scope_key AS user_id,
    COUNT(*)                             AS total_rows,
    COUNT(DISTINCT LOWER(TRIM(memory_content)))  AS distinct_contents,
    ROUND(COUNT(DISTINCT LOWER(TRIM(memory_content)))::numeric
          / COUNT(*)::numeric, 3)              AS distinct_ratio
FROM ab_agent_memory
WHERE tenant_id = :tenant_id
  AND (deleted_flag IS NULL OR deleted_flag = FALSE)
  AND scope = 'user'
  AND scope_key IS NOT NULL
GROUP BY tenant_id, memory_agent_id, scope_key
HAVING COUNT(*) >= 3
ORDER BY distinct_ratio ASC, total_rows DESC
LIMIT :sample_limit;
