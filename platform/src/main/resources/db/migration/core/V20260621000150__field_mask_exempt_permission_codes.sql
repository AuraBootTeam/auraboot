-- Capability-driven field unmasking: let a field-mask config exempt users by permission code,
-- in addition to the existing exempt_roles. A user holding ANY of the listed permission codes
-- sees the unmasked value. This backs the permission v2 "sensitive capability" pattern — e.g.
-- the capability behind crm.account.contact_unmask unmasks crm_account_common.phone, while every
-- other role still reads 138****1234. Additive + idempotent (IF NOT EXISTS).

ALTER TABLE ab_field_mask_config ADD COLUMN IF NOT EXISTS exempt_permission_codes TEXT;

COMMENT ON COLUMN ab_field_mask_config.exempt_permission_codes IS
    'Comma-separated permission codes; a user holding ANY of them sees the unmasked value (capability-driven unmask, evaluated in addition to exempt_roles)';
