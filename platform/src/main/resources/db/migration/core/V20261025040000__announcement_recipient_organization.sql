-- Historical audience classification is captured once, never reconstructed from current IAM.
ALTER TABLE ab_announcement_recipient ADD COLUMN organization_snapshot JSONB NOT NULL
 DEFAULT '{"captured":false,"departments":[],"teamPids":[],"matchedRoles":[]}'::jsonb,
 ADD CONSTRAINT ab_announcement_recipient_org_object CHECK(jsonb_typeof(organization_snapshot)='object');
ALTER TABLE ab_announcement_audience_estimate ADD COLUMN organization_snapshot JSONB NOT NULL
 DEFAULT '{"captured":false,"departments":[],"teamPids":[],"matchedRoles":[]}'::jsonb,
 ADD CONSTRAINT ab_announcement_estimate_org_object CHECK(jsonb_typeof(organization_snapshot)='object');

CREATE FUNCTION ab_announcement_guard_recipient_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Announcement recipient identity is immutable'; END IF;
 IF ROW(NEW.tenant_id,NEW.version_pid,NEW.user_pid,NEW.member_pid,NEW.display_name_snapshot,NEW.organization_snapshot)
    IS DISTINCT FROM ROW(OLD.tenant_id,OLD.version_pid,OLD.user_pid,OLD.member_pid,OLD.display_name_snapshot,OLD.organization_snapshot)
 THEN RAISE EXCEPTION 'Announcement recipient identity is immutable'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER ab_announcement_recipient_identity_guard BEFORE UPDATE OR DELETE ON ab_announcement_recipient
 FOR EACH ROW EXECUTE FUNCTION ab_announcement_guard_recipient_identity();

CREATE FUNCTION ab_announcement_guard_sealed_audience() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.sealed THEN RAISE EXCEPTION 'Sealed announcement audience is immutable'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER ab_announcement_sealed_audience_guard BEFORE UPDATE OR DELETE ON ab_announcement_recipient_snapshot
 FOR EACH ROW EXECUTE FUNCTION ab_announcement_guard_sealed_audience();
