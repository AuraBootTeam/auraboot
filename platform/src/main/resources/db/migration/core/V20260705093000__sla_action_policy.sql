ALTER TABLE ab_sla_config
    ADD COLUMN IF NOT EXISTS action_policy JSONB DEFAULT '{}';

CREATE INDEX IF NOT EXISTS idx_sla_config_action_policy_gin
    ON ab_sla_config USING GIN (action_policy);
