-- Preserve public request correlation across durable retry and replay.
ALTER TABLE ab_webhook_delivery_log ADD COLUMN request_id VARCHAR(128);
COMMENT ON COLUMN ab_webhook_delivery_log.request_id IS
    'Originating request ID captured before asynchronous delivery; unchanged by retry/replay';
