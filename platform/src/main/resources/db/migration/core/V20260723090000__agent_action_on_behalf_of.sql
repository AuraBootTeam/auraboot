-- Who asked for this, as distinct from what carried it out.
--
-- An agent executes under the initiating user's MetaContext — its tools run
-- with that person's permissions and data scope. The action audit recorded
-- actor_type='agent' and nothing else: not which agent, and not whose authority
-- was being spent. Reading the trail afterwards, "an agent deleted this record"
-- is not an answer anyone can act on, and in an incident it is the only
-- question that matters.
--
-- Three identities, kept apart because they genuinely differ:
--   actor_id            — the agent that performed it (already had a column,
--                         never written to)
--   on_behalf_of_user_id — the person whose authority it used
--   created_by / tenant  — unchanged, the row's own provenance
ALTER TABLE ab_agent_action ADD COLUMN IF NOT EXISTS on_behalf_of_user_id BIGINT;

COMMENT ON COLUMN ab_agent_action.on_behalf_of_user_id IS
    'The user whose permissions and data scope this agent action executed under; NULL for system-initiated runs';

COMMENT ON COLUMN ab_agent_action.actor_id IS
    'The agent that performed the action (agent_code), as opposed to the human it acted for';

-- Answering "what has this agent done for this person" is the shape every
-- incident review takes, so it gets the index rather than either column alone.
CREATE INDEX IF NOT EXISTS idx_agent_action_actor_on_behalf
    ON ab_agent_action (tenant_id, actor_id, on_behalf_of_user_id);
