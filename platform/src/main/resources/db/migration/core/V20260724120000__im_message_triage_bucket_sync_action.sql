-- Align ab_im_message.triage_bucket CHECK with the TriageBucket enum.
--
-- The baseline (V20260618000000) constraint allowed only light_chat / contextual_answer /
-- acp_run, but TriageBucket also defines SYNC_ACTION (stored as 'sync_action'). A write
-- message (e.g. "create a customer") triages to sync_action, so persisting the inbound
-- message to ab_im_message violated this CHECK and crashed the whole conversation turn —
-- every agent WRITE run through a conversation (colleague chat) failed at persistInbound
-- with "violates check constraint ab_im_message_triage_bucket_check". Add the missing value.
ALTER TABLE ab_im_message DROP CONSTRAINT IF EXISTS ab_im_message_triage_bucket_check;
ALTER TABLE ab_im_message ADD CONSTRAINT ab_im_message_triage_bucket_check
    CHECK (triage_bucket IS NULL
        OR triage_bucket IN ('light_chat', 'contextual_answer', 'sync_action', 'acp_run'));
