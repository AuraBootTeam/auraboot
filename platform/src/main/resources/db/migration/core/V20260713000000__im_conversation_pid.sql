-- S0 (shared prerequisite for S1 embeddable CS channel and S3 conversation-to-FAQ loop):
-- give ab_im_conversation its own public record pid.
--
-- Scope is deliberately minimal: column + backfill + unique index only. No visitor,
-- agent-seat or FAQ semantics here — those belong to S1 / S3 respectively. The existing
-- IM public boundary still exposes the internal Long id; migrating that boundary to
-- pid-only is owned by S1 (framework/im/**).
--
-- NOTE: ab_im_conversation.bound_record_pid (V20260623001000) is a *different* column —
-- it is the pid of the business record a conversation is bound to, not the pid of the
-- conversation itself.

ALTER TABLE ab_im_conversation
    ADD COLUMN IF NOT EXISTS pid VARCHAR(26);

UPDATE ab_im_conversation
SET pid = SUBSTRING(REPLACE(gen_random_uuid()::text, '-', ''), 1, 26)
WHERE pid IS NULL OR pid = '';

ALTER TABLE ab_im_conversation
    ALTER COLUMN pid SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_ab_im_conversation_pid
    ON ab_im_conversation (pid);
