-- Build the cohort before sealing; serialize inserts against the final seal.
CREATE FUNCTION ab_announcement_guard_recipient_insert() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE audience_sealed BOOLEAN;
BEGIN
 SELECT sealed INTO audience_sealed FROM ab_announcement_recipient_snapshot
 WHERE tenant_id=NEW.tenant_id AND version_pid=NEW.version_pid FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Announcement audience header is missing'; END IF;
 IF audience_sealed THEN RAISE EXCEPTION 'Sealed announcement audience rejects additional recipients'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER ab_announcement_recipient_insert_guard BEFORE INSERT ON ab_announcement_recipient
 FOR EACH ROW EXECUTE FUNCTION ab_announcement_guard_recipient_insert();

CREATE OR REPLACE FUNCTION ab_announcement_guard_sealed_audience() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.sealed THEN RAISE EXCEPTION 'Sealed announcement audience is immutable'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 IF ROW(NEW.tenant_id,NEW.pid,NEW.version_pid,NEW.origin,NEW.resolved_at)
    IS DISTINCT FROM ROW(OLD.tenant_id,OLD.pid,OLD.version_pid,OLD.origin,OLD.resolved_at)
 THEN RAISE EXCEPTION 'Announcement audience identity is immutable'; END IF;
 IF NEW.sealed AND NEW.recipient_count<>(SELECT count(*) FROM ab_announcement_recipient
    WHERE tenant_id=NEW.tenant_id AND version_pid=NEW.version_pid)
 THEN RAISE EXCEPTION 'Announcement audience count does not match its recipients'; END IF;
 RETURN NEW;
END $$;
