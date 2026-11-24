ALTER TABLE ab_open_api_call_audit ADD COLUMN http_details jsonb;
CREATE INDEX idx_open_api_audit_installation_time ON ab_open_api_call_audit (tenant_id, installation_pid, occurred_at DESC, id DESC);
COMMENT ON COLUMN ab_open_api_call_audit.http_details IS 'Bounded redacted HTTP metadata and JSON bodies; NULL means historical capture unavailable. Never stores credentials or raw partial payloads.';
