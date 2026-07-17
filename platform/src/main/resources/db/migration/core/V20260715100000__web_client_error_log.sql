-- Web front-end client error log — captures uncaught JS errors and unhandled
-- promise rejections reported by the browser (window.onerror / unhandledrejection),
-- so front-end failures are visible in the in-app troubleshooting center instead
-- of vanishing. Mirrors the ab_mobile_client_log pattern for the web surface.
-- NOTE: keep this DDL in sync with database/schema.sql (fresh/golden stacks apply
-- schema.sql via psql and do NOT run Flyway).
CREATE TABLE IF NOT EXISTS ab_web_client_error (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           BIGINT,
    user_id             BIGINT,
    session_id          VARCHAR(128),
    trace_id            VARCHAR(128),
    error_type          VARCHAR(30),        -- error | unhandledrejection
    message             TEXT,
    stack               TEXT,
    page_url            TEXT,
    user_agent          VARCHAR(512),
    app_version         VARCHAR(50),
    client_timestamp    TIMESTAMPTZ,
    created_at          TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_web_client_error_tenant_time ON ab_web_client_error(tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_web_client_error_trace ON ab_web_client_error(trace_id);
