-- Supports bounded TTL cleanup of raw quarantine payloads.
CREATE INDEX IF NOT EXISTS idx_ab_behavior_quarantine_retention
    ON ab_behavior_quarantine (quarantined_at, id);
