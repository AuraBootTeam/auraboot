-- Every grant mutation advances the revision, including revoke/regrant and no-op writes.
ALTER TABLE ab_role_permission ADD COLUMN policy_revision bigint NOT NULL DEFAULT 1
    CHECK (policy_revision > 0);

CREATE FUNCTION ab_role_permission_advance_policy_revision() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    NEW.policy_revision := OLD.policy_revision + 1;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_role_permission_policy_revision
    BEFORE UPDATE ON ab_role_permission
    FOR EACH ROW EXECUTE FUNCTION ab_role_permission_advance_policy_revision();
