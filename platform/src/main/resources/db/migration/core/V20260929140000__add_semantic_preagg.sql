-- R3 (BI rectification): pre-aggregation engine for published semantic metrics.
-- Each preagg owns one PostgreSQL materialized view holding the metric
-- aggregated by the chosen dimensions under the model's governance scope.
-- Refresh is a scheduled sweep honouring per-preagg refresh intervals;
-- staleness (last_refreshed_at) is exposed so dashboards can annotate.

CREATE TABLE IF NOT EXISTS ab_semantic_preagg (
    pid                VARCHAR(26) PRIMARY KEY,
    tenant_id          BIGINT      NOT NULL,
    name               VARCHAR(200) NOT NULL,
    semantic_model_pid VARCHAR(26) NOT NULL,
    metric_code        VARCHAR(100) NOT NULL,
    dimension_codes    JSONB       NOT NULL DEFAULT '[]',
    refresh_minutes    INTEGER     NOT NULL DEFAULT 60,
    mv_name            VARCHAR(100) NOT NULL UNIQUE,
    last_refreshed_at  TIMESTAMPTZ,
    last_refresh_rows  BIGINT,
    created_by         BIGINT      NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_flag       BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_semantic_preagg_tenant
    ON ab_semantic_preagg (tenant_id)
    WHERE deleted_flag = FALSE;
