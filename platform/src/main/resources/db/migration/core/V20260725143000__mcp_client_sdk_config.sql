-- MCP client completeness: structured stdio args/environment and sync failure facts.
ALTER TABLE ab_agent_mcp_server
    ADD COLUMN IF NOT EXISTS stdio_args JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS transport_config JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS last_sync_error VARCHAR(1000);

COMMENT ON COLUMN ab_agent_mcp_server.stdio_args IS
    'stdio argv array; executable remains in server_url and is never interpreted by a shell';
COMMENT ON COLUMN ab_agent_mcp_server.auth_config IS
    'authentication metadata; secret fields are stored as ENC: ciphertext';
COMMENT ON COLUMN ab_agent_mcp_server.transport_config IS
    'transport-specific protected settings; stdio environment values use ENC: field encryption';
COMMENT ON COLUMN ab_agent_mcp_server.last_sync_error IS
    'bounded credential-free error from the latest failed live tools/list';
