-- Rule-center binding for SLA configs.

ALTER TABLE ab_sla_config
    ADD COLUMN IF NOT EXISTS rule_binding JSONB;

CREATE INDEX IF NOT EXISTS idx_sla_config_rule_binding_gin
    ON ab_sla_config USING GIN (rule_binding);
