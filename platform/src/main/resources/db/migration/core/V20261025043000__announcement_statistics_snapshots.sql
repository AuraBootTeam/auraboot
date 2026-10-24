-- Fixed statistics inputs remain private and immutable through the export retention window.
CREATE TABLE ab_announcement_statistics_snapshot (
    tenant_id BIGINT NOT NULL,
    pid VARCHAR(36) NOT NULL,
    version_pid VARCHAR(36) NOT NULL,
    owner_user_id BIGINT NOT NULL,
    owner_member_pid VARCHAR(36) NOT NULL,
    snapshot_schema VARCHAR(40) NOT NULL CHECK(snapshot_schema='announcement.statistics.v1'),
    as_of TIMESTAMPTZ NOT NULL,
    view_expires_at TIMESTAMPTZ NOT NULL,
    retained_until TIMESTAMPTZ NOT NULL,
    metadata JSONB NOT NULL CHECK(jsonb_typeof(metadata)='object'),
    summary JSONB NOT NULL CHECK(jsonb_typeof(summary)='object'),
    filters JSONB NOT NULL CHECK(jsonb_typeof(filters)='object'),
    row_count BIGINT NOT NULL CHECK(row_count>=0),
    filtered_count BIGINT NOT NULL CHECK(filtered_count>=0 AND filtered_count<=row_count),
    sealed BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY(tenant_id,pid),
    FOREIGN KEY(tenant_id,version_pid) REFERENCES ab_announcement_version(tenant_id,pid),
    CHECK(view_expires_at=as_of+INTERVAL '30 minutes'),
    CHECK(retained_until=as_of+INTERVAL '25 hours')
);
CREATE INDEX idx_announcement_statistics_retention ON ab_announcement_statistics_snapshot(retained_until);
CREATE TABLE ab_announcement_statistics_row (
    tenant_id BIGINT NOT NULL,
    snapshot_pid VARCHAR(36) NOT NULL,
    user_pid VARCHAR(36) NOT NULL,
    member_pid VARCHAR(36) NOT NULL,
    organization_snapshot JSONB NOT NULL,
    display_proof JSONB NOT NULL,
    view_payload JSONB NOT NULL,
    matches_filters BOOLEAN NOT NULL,
    PRIMARY KEY(tenant_id,snapshot_pid,user_pid),
    FOREIGN KEY(tenant_id,snapshot_pid) REFERENCES ab_announcement_statistics_snapshot(tenant_id,pid) ON DELETE CASCADE
);
CREATE INDEX idx_announcement_statistics_page ON ab_announcement_statistics_row(tenant_id,snapshot_pid,user_pid) WHERE matches_filters;

CREATE FUNCTION ab_announcement_guard_statistics_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.sealed THEN RAISE EXCEPTION 'Announcement statistics must be sealed after its rows'; END IF;
  RETURN NEW;
 END IF;
 IF TG_OP='DELETE' THEN
  IF OLD.retained_until>clock_timestamp() THEN RAISE EXCEPTION 'Announcement statistics retention has not elapsed'; END IF;
  RETURN OLD;
 END IF;
 IF OLD.sealed OR NOT NEW.sealed OR (to_jsonb(NEW)-'sealed') IS DISTINCT FROM (to_jsonb(OLD)-'sealed')
 THEN RAISE EXCEPTION 'Announcement statistics snapshot is immutable'; END IF;
 IF NEW.row_count<>(SELECT count(*) FROM ab_announcement_statistics_row WHERE tenant_id=NEW.tenant_id AND snapshot_pid=NEW.pid)
    OR NEW.filtered_count<>(SELECT count(*) FROM ab_announcement_statistics_row WHERE tenant_id=NEW.tenant_id AND snapshot_pid=NEW.pid AND matches_filters)
 THEN RAISE EXCEPTION 'Announcement statistics row counts do not match'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER ab_announcement_statistics_snapshot_guard BEFORE INSERT OR UPDATE OR DELETE ON ab_announcement_statistics_snapshot
 FOR EACH ROW EXECUTE FUNCTION ab_announcement_guard_statistics_snapshot();

CREATE FUNCTION ab_announcement_guard_statistics_row() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE header_sealed BOOLEAN; retention TIMESTAMPTZ;
BEGIN
 IF TG_OP='UPDATE' THEN RAISE EXCEPTION 'Announcement statistics rows are immutable'; END IF;
 IF TG_OP='DELETE' THEN
  SELECT retained_until INTO retention FROM ab_announcement_statistics_snapshot WHERE tenant_id=OLD.tenant_id AND pid=OLD.snapshot_pid;
  IF retention>clock_timestamp() THEN RAISE EXCEPTION 'Announcement statistics retention has not elapsed'; END IF;
  RETURN OLD;
 END IF;
 SELECT sealed INTO header_sealed FROM ab_announcement_statistics_snapshot WHERE tenant_id=NEW.tenant_id AND pid=NEW.snapshot_pid FOR UPDATE;
 IF NOT FOUND OR header_sealed THEN RAISE EXCEPTION 'Announcement statistics snapshot is missing or sealed'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER ab_announcement_statistics_row_guard BEFORE INSERT OR UPDATE OR DELETE ON ab_announcement_statistics_row
 FOR EACH ROW EXECUTE FUNCTION ab_announcement_guard_statistics_row();
