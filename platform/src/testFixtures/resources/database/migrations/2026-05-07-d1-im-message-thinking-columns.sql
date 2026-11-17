-- ============================================================================
-- ACP Phase D.1 — Anthropic Extended Thinking persistence (ab_im_message)
-- ============================================================================
--
-- Adds {@code thinking_content TEXT} and {@code thinking_signature TEXT} to
-- {@code ab_im_message} so the reasoning prose produced by Anthropic Extended
-- Thinking ({@code claude-sonnet-4-6+ / opus-4 / haiku-4}) survives a page
-- reload. Pre-D.1 the prose only existed inside the SSE stream — once the
-- stream finished the frontend dropped it on the floor when re-rendering
-- conversation history.
--
-- Closes auraboot-enterprise/docs/backlog/2026-05-06-acp-p0-p1-followups.md §D.1.
--
-- WHEN TO RUN:
--   - Production / shared-data environments only. Dev environments wipe data
--     via {@code reset-and-init.sh} per AGENTS.md "开发阶段声明".
--
-- HOW TO RUN:
--   psql "$DB_URL" -v ON_ERROR_STOP=1 \
--     -f migrations/2026-05-07-d1-im-message-thinking-columns.sql
--
-- IDEMPOTENCY:
--   {@code ADD COLUMN IF NOT EXISTS} is a no-op when the column already
--   exists; running this script twice is safe. Verified by re-running locally
--   before commit (D.1 acceptance check).
--
-- BACK-COMPAT:
--   Both columns are nullable with no DEFAULT. Existing INSERT statements that
--   do not mention these columns continue to work — PostgreSQL fills NULL.
--   No-thinking turns leave both columns NULL (we deliberately avoid empty
--   strings so analytics can distinguish "feature off" from "feature on +
--   produced no reasoning").
-- ============================================================================

ALTER TABLE ab_im_message
  ADD COLUMN IF NOT EXISTS thinking_content   TEXT,
  ADD COLUMN IF NOT EXISTS thinking_signature TEXT;
