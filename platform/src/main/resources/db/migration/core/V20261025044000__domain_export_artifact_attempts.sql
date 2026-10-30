-- Private attempt registry: a crash or a losing execution fence must not orphan sensitive artifacts.
CREATE TABLE ab_domain_export_attempt (
    pid VARCHAR(36) PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    export_task_pid VARCHAR(32) NOT NULL REFERENCES ab_export_task(pid),
    execution_token VARCHAR(36) NOT NULL,
    file_key TEXT NOT NULL UNIQUE,
    temp_path TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    expires_at TIMESTAMPTZ NOT NULL,
    next_cleanup_at TIMESTAMPTZ NOT NULL,
    cleanup_count INTEGER NOT NULL DEFAULT 0 CHECK(cleanup_count>=0),
    cleanup_error TEXT,
    cleaned_at TIMESTAMPTZ,
    CHECK(expires_at>created_at)
);
CREATE INDEX idx_domain_export_cleanup ON ab_domain_export_attempt(next_cleanup_at);
CREATE INDEX idx_domain_export_execution ON ab_domain_export_attempt(tenant_id,export_task_pid,execution_token);

CREATE FUNCTION ab_guard_domain_export_attempt() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NOT EXISTS(SELECT 1 FROM ab_export_task WHERE pid=NEW.export_task_pid AND tenant_id=NEW.tenant_id
      AND query_code LIKE 'domain:%' AND request_params->>'sourceType'='domain')
  THEN RAISE EXCEPTION 'Domain export attempt owner binding is invalid'; END IF;
  RETURN NEW;
 END IF;
 IF TG_OP='DELETE' OR (to_jsonb(NEW)-ARRAY['next_cleanup_at','cleanup_count','cleanup_error','cleaned_at'])
       IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['next_cleanup_at','cleanup_count','cleanup_error','cleaned_at'])
 THEN RAISE EXCEPTION 'Domain export attempt identity is immutable'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER ab_domain_export_attempt_guard BEFORE INSERT OR UPDATE OR DELETE ON ab_domain_export_attempt
 FOR EACH ROW EXECUTE FUNCTION ab_guard_domain_export_attempt();
