-- Phase 4 "report storage graduation" — slice 1 (PURELY ADDITIVE).
--
-- First-class low-code report definition table. Nothing reads or writes ab_report yet:
-- the report designer still persists via ab_page_schema (kind:'list') + extension.reportDsl.
-- This table is the storage destination for the eventual one-time migration off the page
-- shell. The whole ReportDsl is stored as ONE jsonb blob in `dsl` (1:1 with today's
-- extension.reportDsl) so the future data migration is a trivial copy.
--
-- Additive only: this migration creates a new table + indexes and does NOT alter
-- ab_page_schema / ab_report_template / ab_report_schedule.

CREATE TABLE ab_report (
    id           BIGINT PRIMARY KEY,
    pid          VARCHAR(26) UNIQUE NOT NULL,
    tenant_id    BIGINT NOT NULL,
    code         VARCHAR(128) NOT NULL,
    title        VARCHAR(255),
    profile      VARCHAR(32) NOT NULL DEFAULT 'paged-media',
    dsl          JSONB NOT NULL DEFAULT '{}',
    status       VARCHAR(32) NOT NULL DEFAULT 'draft',
    version      INT NOT NULL DEFAULT 1,
    created_by   BIGINT,
    created_at   TIMESTAMP NOT NULL DEFAULT now(),
    updated_by   BIGINT,
    updated_at   TIMESTAMP NOT NULL DEFAULT now(),
    deleted_flag SMALLINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_ab_report_tenant_code UNIQUE (tenant_id, code)
);

CREATE INDEX idx_ab_report_tenant ON ab_report (tenant_id, deleted_flag);

COMMENT ON TABLE ab_report IS 'First-class low-code report definition (Phase 4 storage graduation; replaces the kind:list + extension.reportDsl page shell).';
