-- Role field controls reuse the existing field restriction evaluator.
ALTER TABLE ab_data_permission_policy ADD COLUMN managed_role_pid VARCHAR(32);
CREATE UNIQUE INDEX ux_role_managed_field_owner ON ab_role(tenant_id,pid);
ALTER TABLE ab_data_permission_policy ADD CONSTRAINT fk_data_policy_managed_role
    FOREIGN KEY(tenant_id,managed_role_pid) REFERENCES ab_role(tenant_id,pid) ON DELETE CASCADE;
CREATE UNIQUE INDEX ux_data_policy_managed_field ON ab_data_permission_policy
    (tenant_id,managed_role_pid,model_code,field_code,policy_type)
    WHERE managed_role_pid IS NOT NULL;
COMMENT ON COLUMN ab_data_permission_policy.managed_role_pid IS
    'Role-local field restriction authored by the atomic role draft; never a shared policy definition.';
