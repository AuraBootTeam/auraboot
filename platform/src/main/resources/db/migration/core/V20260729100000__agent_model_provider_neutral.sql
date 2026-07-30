-- Agent definitions express runtime capability/policy; provider and model are
-- deployment configuration. New rows must not silently pin a vendor model.
ALTER TABLE ab_agent_definition
    ALTER COLUMN model DROP DEFAULT;

-- Built-in platform templates are safe to migrate because operators create
-- tenant copies before customization. Do not rewrite tenant-authored agents.
UPDATE ab_agent_definition
SET model = NULL,
    updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 1
  AND agent_code IN (
      'tpl_aurabot_internal',
      'tpl_approval_assistant',
      'tpl_customer_service')
  AND deleted_flag = FALSE;

COMMENT ON COLUMN ab_agent_definition.model IS
    'Optional deployment-selected model override; NULL resolves through the provider-neutral runtime profile';
