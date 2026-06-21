-- V20260621000000__agent_eval_case.sql
-- Per-business agent eval cases (injected via plugin agent-definitions.json),
-- read at runtime by CapabilityEvalService.loadRegisteredCases. Sub-resource of
-- the agent definition: lifecycle follows the agent (rollback/restore/overwrite).
CREATE TABLE IF NOT EXISTS ab_agent_eval_case (
  id                   BIGSERIAL PRIMARY KEY,
  pid                  VARCHAR(26) UNIQUE NOT NULL,
  tenant_id            BIGINT NOT NULL,
  agent_code           VARCHAR(100) NOT NULL,
  case_id              VARCHAR(150) NOT NULL,
  category             VARCHAR(100),
  task_description     TEXT NOT NULL,
  expected_tool_codes  JSONB NOT NULL DEFAULT '[]',
  forbidden_tool_codes JSONB NOT NULL DEFAULT '[]',
  expected_input_keys  JSONB NOT NULL DEFAULT '{}',
  expected_risk_level  VARCHAR(20),
  expects_confirmation BOOLEAN NOT NULL DEFAULT FALSE,
  plugin_source        VARCHAR(100),
  deleted_flag         BOOLEAN DEFAULT FALSE,
  created_at           TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
  updated_at           TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_eval_case
  ON ab_agent_eval_case (tenant_id, agent_code, case_id) WHERE deleted_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_agent_eval_case_tenant_agent
  ON ab_agent_eval_case (tenant_id, agent_code) WHERE deleted_flag = FALSE;
