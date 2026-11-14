-- ============================================================================
-- 2026-05-30 — ChatBI v2 Disambiguation Log (B1 W3-M1)
-- ============================================================================
--
-- Records every disambiguation prompt sent to the user along with the user's
-- eventual choice. Two analytic uses:
--
--   1. Prompt-template tuning: hot disambiguation terms surface common LLM
--      gaps; gather them into the chatbi_token_dict so future questions
--      resolve directly.
--   2. UX quality monitoring: high disambiguation rate (> 30%/hour per PRD 17
--      §12) signals prompt regression and pages the team.
--
-- PRD reference: ida/docs/17-prd-chatbi-v2.md §5 (table spec) + §7.3 (trigger
-- rules: top1 < 0.5 → low confidence; top1 - top2 < 0.15 → ambiguous).
-- ============================================================================

CREATE TABLE IF NOT EXISTS chatbi_disambiguation_log (
    id              BIGINT PRIMARY KEY,
    pid             VARCHAR(32) NOT NULL,
    tenant_id       BIGINT NOT NULL,
    answer_pid      VARCHAR(32) NOT NULL,
    -- The ambiguous user-question term ("销售额" / "amount" / "本月" / etc.).
    -- VARCHAR(256) to allow Chinese plus English compound terms.
    ambiguous_term  VARCHAR(256) NOT NULL,
    -- JSON array of candidate records emitted by DisambiguationService:
    --   [{type, code, label, score}, ...]
    -- Stored as JSONB so /api/chatbi/disambiguate can read back without
    -- a join, and ad-hoc analytics can use ->>'code'.
    candidates_json JSONB NOT NULL,
    -- Final code the user selected (matches a candidates_json[i].code).
    -- NULL until the user replies; populated when /api/chatbi/disambiguate
    -- posts the choice. Used for prompt-template improvement feedback.
    user_choice     VARCHAR(128),
    -- Trigger reason: LOW_CONFIDENCE (top1 < 0.5) or AMBIGUOUS (top1-top2 < 0.15)
    trigger_reason  VARCHAR(32) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at     TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_chatbi_disambiguation_pid
    ON chatbi_disambiguation_log (pid);
CREATE INDEX IF NOT EXISTS idx_chatbi_disambiguation_answer
    ON chatbi_disambiguation_log (tenant_id, answer_pid);
CREATE INDEX IF NOT EXISTS idx_chatbi_disambiguation_term
    ON chatbi_disambiguation_log (tenant_id, ambiguous_term);
-- Time-bounded scan for the prompt-quality dashboard.
CREATE INDEX IF NOT EXISTS idx_chatbi_disambiguation_created
    ON chatbi_disambiguation_log (tenant_id, created_at DESC);
