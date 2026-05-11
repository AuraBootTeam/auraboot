-- Migration: record share public PID aliases
-- Date: 2026-05-11
-- Plan: ID-001C/ID-001D cross-module PID migration

ALTER TABLE ab_record_share
    ADD COLUMN IF NOT EXISTS record_pid VARCHAR(64);

ALTER TABLE ab_record_share
    ADD COLUMN IF NOT EXISTS subject_pid VARCHAR(64);

ALTER TABLE ab_record_share
    ALTER COLUMN record_id DROP NOT NULL;

ALTER TABLE ab_record_share
    ALTER COLUMN subject_id DROP NOT NULL;

CREATE INDEX IF NOT EXISTS idx_ab_record_share_record_pid
    ON ab_record_share (tenant_id, resource_code, record_pid);

CREATE INDEX IF NOT EXISTS idx_ab_record_share_subject_pid
    ON ab_record_share (tenant_id, subject_type, subject_pid);
