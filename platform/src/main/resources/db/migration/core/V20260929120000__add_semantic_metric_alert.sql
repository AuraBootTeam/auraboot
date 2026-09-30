-- R2 (BI rectification plan): threshold alerts on published semantic metrics.
-- An alert re-evaluates a published metric on a schedule and notifies the
-- creator (or a resolved recipient) when the value crosses a threshold,
-- honouring a silence window so a persistently-breaching metric does not
-- spam the inbox.

CREATE TABLE IF NOT EXISTS ab_semantic_metric_alert (
    pid               VARCHAR(26) PRIMARY KEY,
    tenant_id         BIGINT       NOT NULL,
    name              VARCHAR(200) NOT NULL,
    metric_pid        VARCHAR(26)  NOT NULL,
    comparator        VARCHAR(10)  NOT NULL,
    threshold         NUMERIC      NOT NULL,
    silence_minutes   INTEGER      NOT NULL DEFAULT 60,
    alert_status      VARCHAR(20)  NOT NULL DEFAULT 'active',
    last_triggered_at TIMESTAMPTZ,
    last_evaluated_at TIMESTAMPTZ,
    created_by        BIGINT       NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted_flag      BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT chk_semantic_metric_alert_comparator
        CHECK (comparator IN ('gt', 'gte', 'lt', 'lte')),
    CONSTRAINT chk_semantic_metric_alert_status
        CHECK (alert_status IN ('active', 'paused'))
);

CREATE INDEX IF NOT EXISTS idx_semantic_metric_alert_tenant_status
    ON ab_semantic_metric_alert (tenant_id, alert_status)
    WHERE deleted_flag = FALSE;
