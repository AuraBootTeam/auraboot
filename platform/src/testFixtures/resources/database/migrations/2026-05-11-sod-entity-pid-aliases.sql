-- Migration: SoD and audit trail public entity PID aliases
-- Date: 2026-05-11
-- Plan: ID-001C-G cross-module PID migration

ALTER TABLE ab_audit_trail
    ADD COLUMN IF NOT EXISTS entity_pid VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_audit_trail_entity_pid
    ON ab_audit_trail (tenant_id, entity_type, entity_pid);

ALTER TABLE ab_sod_violation_log
    ADD COLUMN IF NOT EXISTS entity_pid VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_sod_violation_entity_pid
    ON ab_sod_violation_log (tenant_id, entity_type, entity_pid);
