-- Role-level default data scope (permission v2 ② dimension).
--
-- A role can carry a default data-scope tier (all / dept_and_sub / dept / self / none). When set,
-- newly-granted permissions inherit it (materialized into ab_role_data_scope at grant time) instead
-- of the previous "no row = not configured" default. NULL preserves the existing per-action,
-- deny-by-default behaviour for every role that has not opted in.
--
-- Distinct from ab_role.scope_type (the role's GLOBAL/TENANT/STORE authority scope) — this is the
-- DATA visibility default. Additive + idempotent.
ALTER TABLE ab_role
    ADD COLUMN IF NOT EXISTS default_data_scope_type VARCHAR(32);

COMMENT ON COLUMN ab_role.default_data_scope_type IS
    'Default data-scope tier inherited by newly-granted permissions (all/dept_and_sub/dept/self/none); NULL = per-action, deny-by-default (no inheritance).';
