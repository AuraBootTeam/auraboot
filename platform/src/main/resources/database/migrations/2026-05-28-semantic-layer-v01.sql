-- ============================================================================
-- 2026-05-28 — Semantic Layer v0.1 (IDA P0-1)
-- ============================================================================
--
-- PRD: ida/docs/16-prd-semantic-yml-dsl.md §5
--
-- Introduces 6 tables that back *.semantic.yml files declared in plugin
-- resourceDirs.semantic. Tables are independent of MetricConfig (legacy
-- inline metric definition) and live alongside it during v0.1.
--
-- All tables: tenant_id NOT NULL + pid VARCHAR(32) ULID unique within tenant.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. ab_semantic_model — top-level semantic model
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ab_semantic_model (
    id              BIGINT PRIMARY KEY,
    pid             VARCHAR(32) NOT NULL,
    tenant_id       BIGINT NOT NULL,
    plugin_code     VARCHAR(64) NOT NULL,
    code            VARCHAR(128) NOT NULL,          -- semantic_model.code
    model_ref       VARCHAR(128) NOT NULL,          -- MetaModel.code
    primary_entity  VARCHAR(128) NOT NULL,
    label_i18n      JSONB,
    description     TEXT,
    version         VARCHAR(16) NOT NULL DEFAULT '0.1',
    status          VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    -- DRAFT / VALIDATE / DEV / STAGED / ACTIVE / DEPRECATED / REMOVED
    yaml_source     TEXT,
    yaml_sha        VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by      BIGINT,
    updated_by      BIGINT,
    deleted_flag    BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_semantic_model_pid
    ON ab_semantic_model (pid) WHERE deleted_flag = FALSE;
CREATE UNIQUE INDEX IF NOT EXISTS uk_semantic_model_code
    ON ab_semantic_model (tenant_id, plugin_code, code, version) WHERE deleted_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_semantic_model_tenant
    ON ab_semantic_model (tenant_id, status) WHERE deleted_flag = FALSE;

-- ----------------------------------------------------------------------------
-- 2. ab_semantic_dimension — dimension declarations
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ab_semantic_dimension (
    id                  BIGINT PRIMARY KEY,
    pid                 VARCHAR(32) NOT NULL,
    tenant_id           BIGINT NOT NULL,
    semantic_model_pid  VARCHAR(32) NOT NULL,
    code                VARCHAR(128) NOT NULL,
    field_ref           VARCHAR(128) NOT NULL,
    dim_type            VARCHAR(16) NOT NULL,       -- time / categorical / numeric / boolean
    label_i18n          JSONB,
    description         TEXT,
    time_grains         JSONB,                       -- ["day","week","month","quarter","year"]
    primary_time        BOOLEAN NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_flag        BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_semantic_dimension_pid
    ON ab_semantic_dimension (pid) WHERE deleted_flag = FALSE;
CREATE UNIQUE INDEX IF NOT EXISTS uk_semantic_dimension_code
    ON ab_semantic_dimension (tenant_id, semantic_model_pid, code) WHERE deleted_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_semantic_dimension_model
    ON ab_semantic_dimension (semantic_model_pid) WHERE deleted_flag = FALSE;

-- ----------------------------------------------------------------------------
-- 3. ab_semantic_metric — metric declarations (5 types)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ab_semantic_metric (
    id                      BIGINT PRIMARY KEY,
    pid                     VARCHAR(32) NOT NULL,
    tenant_id               BIGINT NOT NULL,
    semantic_model_pid      VARCHAR(32) NOT NULL,
    code                    VARCHAR(128) NOT NULL,
    metric_type             VARCHAR(16) NOT NULL,
    -- simple / ratio / cumulative / derived / conversion
    type_params             JSONB NOT NULL,
    filter_expr             TEXT,
    label_i18n              JSONB,
    description             TEXT,
    required_permissions    JSONB,                   -- ["sales.read", ...]
    status                  VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    version                 VARCHAR(16) NOT NULL DEFAULT '0.1',
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by              BIGINT,
    updated_by              BIGINT,
    deleted_flag            BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_semantic_metric_pid
    ON ab_semantic_metric (pid) WHERE deleted_flag = FALSE;
CREATE UNIQUE INDEX IF NOT EXISTS uk_semantic_metric_code
    ON ab_semantic_metric (tenant_id, code, version) WHERE deleted_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_semantic_metric_model
    ON ab_semantic_metric (semantic_model_pid, status) WHERE deleted_flag = FALSE;

-- ----------------------------------------------------------------------------
-- 4. ab_semantic_lineage_edge — directed graph of model/metric/exposure deps
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ab_semantic_lineage_edge (
    id              BIGINT PRIMARY KEY,
    pid             VARCHAR(32) NOT NULL,
    tenant_id       BIGINT NOT NULL,
    src_node_pid    VARCHAR(128) NOT NULL,
    src_node_type   VARCHAR(32) NOT NULL,
    -- model / metric / exposure / source / dimension
    dst_node_pid    VARCHAR(128) NOT NULL,
    dst_node_type   VARCHAR(32) NOT NULL,
    ref_type        VARCHAR(16) NOT NULL,
    -- depends_on / input_metric / source / measure_ref
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_flag    BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_semantic_lineage_pid
    ON ab_semantic_lineage_edge (pid) WHERE deleted_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_lineage_src
    ON ab_semantic_lineage_edge (src_node_pid, ref_type) WHERE deleted_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_lineage_dst
    ON ab_semantic_lineage_edge (dst_node_pid, ref_type) WHERE deleted_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_lineage_tenant
    ON ab_semantic_lineage_edge (tenant_id) WHERE deleted_flag = FALSE;

-- ----------------------------------------------------------------------------
-- 5. ab_semantic_exposure — downstream consumers (Dashboard / Notebook / ML)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ab_semantic_exposure (
    id              BIGINT PRIMARY KEY,
    pid             VARCHAR(32) NOT NULL,
    tenant_id       BIGINT NOT NULL,
    plugin_code     VARCHAR(64),
    code            VARCHAR(128) NOT NULL,
    exposure_type   VARCHAR(16) NOT NULL,
    -- dashboard / notebook / analysis / ml / application
    label_i18n      JSONB,
    description     TEXT,
    owner_user_id   BIGINT,
    owner_email     VARCHAR(128),
    url             VARCHAR(512),
    maturity        VARCHAR(8),                      -- high / medium / low
    depends_on      JSONB NOT NULL,
    -- [{ "type": "metric", "pid": "01HXY..." }, ...]
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_flag    BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_semantic_exposure_pid
    ON ab_semantic_exposure (pid) WHERE deleted_flag = FALSE;
CREATE UNIQUE INDEX IF NOT EXISTS uk_semantic_exposure_code
    ON ab_semantic_exposure (tenant_id, code) WHERE deleted_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_exposure_owner
    ON ab_semantic_exposure (owner_user_id) WHERE deleted_flag = FALSE;

-- ----------------------------------------------------------------------------
-- 6. ab_semantic_query_log — audit + cache analysis
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ab_semantic_query_log (
    id                  BIGINT PRIMARY KEY,
    query_id            VARCHAR(32) NOT NULL,
    tenant_id           BIGINT NOT NULL,
    user_id             BIGINT,
    metric_pids         JSONB,
    dimension_pids      JSONB,
    filters             JSONB,
    rowcount            INTEGER,
    duration_ms         INTEGER,
    cache_hit           BOOLEAN NOT NULL DEFAULT FALSE,
    preagg_pid          VARCHAR(32),
    sql_fingerprint     VARCHAR(64),
    executed_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_semantic_query_log_qid
    ON ab_semantic_query_log (query_id);
CREATE INDEX IF NOT EXISTS idx_query_log_tenant_time
    ON ab_semantic_query_log (tenant_id, executed_at DESC);
CREATE INDEX IF NOT EXISTS idx_query_log_metrics
    ON ab_semantic_query_log USING GIN (metric_pids);
