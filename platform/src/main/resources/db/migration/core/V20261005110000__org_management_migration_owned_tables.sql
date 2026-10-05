-- Organization management facades use Flyway-owned physical tables.
-- Existing department/employee storage is kept wider than UI validation limits.
-- Plugin reimports must not mutate migration-owned schema through runtime credentials.

CREATE TABLE IF NOT EXISTS mt_org_position (
    id BIGSERIAL PRIMARY KEY,
    pid VARCHAR(26) UNIQUE NOT NULL,
    tenant_id BIGINT NOT NULL,
    org_pos_code VARCHAR(50),
    org_pos_name VARCHAR(100) NOT NULL,
    org_pos_dept_id VARCHAR(26) NOT NULL,
    org_pos_level VARCHAR(20) NOT NULL,
    org_pos_status VARCHAR(20) DEFAULT 'active',
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    deleted_flag BOOLEAN NOT NULL DEFAULT FALSE,
    row_version INTEGER NOT NULL DEFAULT 1
);

ALTER TABLE mt_org_employee ADD COLUMN IF NOT EXISTS org_emp_code VARCHAR(50);
ALTER TABLE mt_org_employee ADD COLUMN IF NOT EXISTS org_emp_hire_date DATE;

CREATE INDEX IF NOT EXISTS idx_mt_org_position_tenant_id ON mt_org_position (tenant_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_mt_org_position_org_pos_code_tenant_unique
    ON mt_org_position (tenant_id, org_pos_code);
CREATE UNIQUE INDEX IF NOT EXISTS idx_mt_org_employee_org_emp_code_tenant_unique
    ON mt_org_employee (tenant_id, org_emp_code);
