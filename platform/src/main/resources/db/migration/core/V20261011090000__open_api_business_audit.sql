ALTER TABLE ab_open_api_call_audit ADD COLUMN business_details JSONB;

COMMENT ON COLUMN ab_open_api_call_audit.business_details IS
    'Server-owned public machine actor/capability/resource/operation/PID/replay descriptors; no payload or credentials';
