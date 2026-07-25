-- ab_ai_action_audit_log was designed for one actor: a human confirming or cancelling an
-- AI suggestion on a mobile client. user_id was therefore NOT NULL.
--
-- The guardrail is now enforced server-side in the agent tool loop, so the rows that
-- matter most are the ones with no human in them at all — an autonomous run whose
-- BLOCKED action was refused. With user_id NOT NULL every one of those inserts would
-- fail, and the table would stay exactly as empty as it has been (0 rows in all six
-- databases checked) while looking like it was wired up.
--
-- user_id becomes nullable and actor identity moves into two explicit columns, so
-- "which human decided this" and "which agent tried it" stop being the same question.

ALTER TABLE ab_ai_action_audit_log ALTER COLUMN user_id DROP NOT NULL;

ALTER TABLE ab_ai_action_audit_log ADD COLUMN IF NOT EXISTS actor_type VARCHAR(20);
ALTER TABLE ab_ai_action_audit_log ADD COLUMN IF NOT EXISTS agent_code VARCHAR(200);
ALTER TABLE ab_ai_action_audit_log ADD COLUMN IF NOT EXISTS run_pid VARCHAR(64);
ALTER TABLE ab_ai_action_audit_log ADD COLUMN IF NOT EXISTS trace_id VARCHAR(36);

COMMENT ON COLUMN ab_ai_action_audit_log.user_id IS
    'Human who confirmed/cancelled the action; NULL for an autonomous agent action with no human actor';
COMMENT ON COLUMN ab_ai_action_audit_log.actor_type IS
    'Who took the action: user | agent';
COMMENT ON COLUMN ab_ai_action_audit_log.agent_code IS
    'Agent that attempted the action when actor_type = agent';
COMMENT ON COLUMN ab_ai_action_audit_log.run_pid IS
    'Agent run this action belonged to, for joining back to the run timeline';
COMMENT ON COLUMN ab_ai_action_audit_log.trace_id IS
    'OTel W3C traceId (32-hex) of the request, so a refused action is reachable from the eagle-eye console';

-- Refused actions are the query this table exists to answer; make that lookup cheap.
CREATE INDEX IF NOT EXISTS idx_ai_audit_decision
    ON ab_ai_action_audit_log (tenant_id, user_decision, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_ai_audit_trace_id
    ON ab_ai_action_audit_log (trace_id) WHERE trace_id IS NOT NULL;
