-- Drain all old workers before applying this transition; mixed old/new workers are unsupported.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM ab_async_task WHERE status = 'running') THEN
        RAISE EXCEPTION 'Drain running async tasks before migrating execution leases';
    END IF;
END $$;

ALTER TABLE ab_async_task ADD COLUMN execution_token VARCHAR(36);
ALTER TABLE ab_async_task ADD COLUMN lease_until TIMESTAMPTZ;
CREATE INDEX idx_async_task_running_lease ON ab_async_task (lease_until) WHERE status = 'running';
