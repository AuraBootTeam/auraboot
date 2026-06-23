ALTER TABLE ab_named_query
    ADD COLUMN IF NOT EXISTS resource_code TEXT,
    ADD COLUMN IF NOT EXISTS action_code TEXT;

CREATE INDEX IF NOT EXISTS ix_named_query_data_scope_declaration
    ON ab_named_query(tenant_id, resource_code, action_code)
    WHERE resource_code IS NOT NULL AND action_code IS NOT NULL;

COMMENT ON COLUMN ab_named_query.resource_code IS 'Protected business resource for DataScope evaluation';
COMMENT ON COLUMN ab_named_query.action_code IS 'Protected action for DataScope evaluation';
