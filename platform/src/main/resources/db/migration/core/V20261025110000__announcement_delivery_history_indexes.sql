-- Bound exact-version channel history and queue telemetry without changing business facts.
CREATE INDEX idx_announcement_delivery_history
 ON ab_announcement_delivery(tenant_id,version_pid,created_at DESC,batch_pid,user_pid,channel);
CREATE INDEX idx_announcement_delivery_pending_age
 ON ab_announcement_delivery(created_at) WHERE status='queued';
