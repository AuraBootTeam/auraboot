package com.auraboot.framework.authoring.workspace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

import static org.springframework.http.HttpStatus.CONFLICT;

/** Persistence boundary for review, immutable release activation and rollback. */
@Repository
public class AuthoringGovernanceRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AuthoringGovernanceRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public GovernanceRow findChangeSet(long tenantId, long envId, String changeSetPid, boolean lock) {
        String lockClause = lock ? " FOR UPDATE OF cs, rd" : "";
        return jdbcTemplate.query("""
                        SELECT cs.id AS change_set_id, cs.pid AS change_set_pid,
                               cs.tenant_id, cs.env_id, cs.owner_user_id, cs.status,
                               cs.revision, cs.risk_level, cs.route, cs.publish_policy,
                               cs.validation_state, cs.approval_state, cs.publish_state,
                               cs.manifest_checksum, cs.base_release_pid,
                               rd.id AS resource_draft_id, rd.pid AS resource_draft_pid,
                               rd.resource_pid, rd.base_version, rd.base_checksum,
                               rd.manifest_checksum AS draft_manifest_checksum,
                               rd.snapshot::text
                        FROM ab_authoring_change_set cs
                        JOIN ab_authoring_resource_draft rd
                          ON rd.change_set_id = cs.id
                         AND rd.tenant_id = cs.tenant_id
                         AND rd.env_id = cs.env_id
                         AND rd.resource_type = 'PAGE_SCHEMA'
                        WHERE cs.tenant_id = ? AND cs.env_id = ? AND cs.pid = ?
                          AND cs.deleted_flag = FALSE
                        """ + lockClause,
                resultSet -> resultSet.next() ? mapGovernance(resultSet) : null,
                tenantId, envId, changeSetPid);
    }

    public int countItems(GovernanceRow row) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ab_authoring_change_item
                WHERE tenant_id = ? AND env_id = ? AND change_set_id = ?
                """, Integer.class, row.tenantId(), row.envId(), row.changeSetId());
        return count == null ? 0 : count;
    }

    public void submit(GovernanceRow row, boolean approvalRequired, long actorUserId) {
        String status = approvalRequired ? "IN_REVIEW" : "APPROVED";
        String approvalState = approvalRequired ? "PENDING" : "NOT_REQUIRED";
        String publishState = approvalRequired ? "DRAFT" : "READY";
        int updated = jdbcTemplate.update("""
                UPDATE ab_authoring_change_set
                SET status = ?, validation_state = 'VALID', approval_state = ?,
                    publish_state = ?, submitted_at = CURRENT_TIMESTAMP,
                    approved_at = CASE WHEN ? THEN NULL ELSE CURRENT_TIMESTAMP END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND env_id = ? AND revision = ?
                  AND status IN ('DRAFT', 'REJECTED')
                """, status, approvalState, publishState, approvalRequired,
                row.changeSetId(), row.tenantId(), row.envId(), row.revision());
        requireOne(updated, "authoring.submit.conflict");
        jdbcTemplate.update("""
                UPDATE ab_authoring_resource_draft
                SET validation_state = 'VALID', stale_reason = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND env_id = ? AND revision = ?
                """, row.resourceDraftId(), row.tenantId(), row.envId(), row.revision());
        jdbcTemplate.update("""
                UPDATE ab_authoring_config_session
                SET state = 'READ_ONLY', updated_at = CURRENT_TIMESTAMP
                WHERE change_set_id = ? AND tenant_id = ? AND env_id = ?
                  AND state IN ('ACTIVE', 'READ_ONLY')
                """, row.changeSetId(), row.tenantId(), row.envId());
        if (approvalRequired) {
            createPendingApproval(row, actorUserId);
        }
    }

    public void approve(GovernanceRow row, long reviewerUserId, String reason) {
        int approvalUpdated = jdbcTemplate.update("""
                UPDATE ab_authoring_approval
                SET status = 'APPROVED', reviewer_user_id = ?, reason = ?,
                    decided_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND env_id = ? AND change_set_id = ?
                  AND change_set_revision = ? AND status = 'PENDING'
                """, reviewerUserId, reason, row.tenantId(), row.envId(),
                row.changeSetId(), row.revision());
        requireOne(approvalUpdated, "authoring.approval.not-pending");
        int changeSetUpdated = jdbcTemplate.update("""
                UPDATE ab_authoring_change_set
                SET status = 'APPROVED', approval_state = 'APPROVED',
                    publish_state = 'READY', approved_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND env_id = ? AND revision = ?
                  AND status = 'IN_REVIEW' AND validation_state = 'VALID'
                """, row.changeSetId(), row.tenantId(), row.envId(), row.revision());
        requireOne(changeSetUpdated, "authoring.approval.conflict");
    }

    public void reject(
            GovernanceRow row,
            long reviewerUserId,
            String reason,
            Instant leaseUntil) {
        int approvalUpdated = jdbcTemplate.update("""
                UPDATE ab_authoring_approval
                SET status = 'REJECTED', reviewer_user_id = ?, reason = ?,
                    decided_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND env_id = ? AND change_set_id = ?
                  AND change_set_revision = ? AND status = 'PENDING'
                """, reviewerUserId, reason, row.tenantId(), row.envId(),
                row.changeSetId(), row.revision());
        requireOne(approvalUpdated, "authoring.approval.not-pending");
        int changeSetUpdated = jdbcTemplate.update("""
                UPDATE ab_authoring_change_set
                SET status = 'REJECTED', approval_state = 'REJECTED',
                    publish_state = 'DRAFT', updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND env_id = ? AND revision = ?
                  AND status = 'IN_REVIEW'
                """, row.changeSetId(), row.tenantId(), row.envId(), row.revision());
        requireOne(changeSetUpdated, "authoring.rejection.conflict");
        jdbcTemplate.update("""
                UPDATE ab_authoring_config_session
                SET state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP
                WHERE change_set_id = ? AND tenant_id = ? AND env_id = ?
                  AND expires_at > CURRENT_TIMESTAMP AND state = 'READ_ONLY'
                """, row.changeSetId(), row.tenantId(), row.envId());
        jdbcTemplate.update("""
                UPDATE ab_authoring_writer_lease
                SET leased_until = ?, lease_revision = lease_revision + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE change_set_id = ? AND tenant_id = ? AND env_id = ?
                """, Timestamp.from(leaseUntil), row.changeSetId(), row.tenantId(), row.envId());
    }

    public ChannelRow lockChannel(GovernanceRow row) {
        return jdbcTemplate.query("""
                        SELECT c.id AS channel_id, c.pid AS channel_pid, c.row_version,
                               c.tenant_id, c.env_id,
                               c.active_release_id, r.pid AS active_release_pid
                        FROM ab_authoring_release_channel c
                        JOIN ab_authoring_release r ON r.id = c.active_release_id
                        WHERE c.tenant_id = ? AND c.env_id = ?
                          AND c.resource_type = 'PAGE_SCHEMA' AND c.resource_pid = ?
                        FOR UPDATE OF c, r
                        """,
                resultSet -> resultSet.next()
                        ? new ChannelRow(
                            resultSet.getLong("channel_id"),
                            resultSet.getString("channel_pid"),
                            resultSet.getLong("row_version"),
                            resultSet.getLong("active_release_id"),
                            resultSet.getString("active_release_pid"))
                        : null,
                row.tenantId(), row.envId(), row.resourcePid());
    }

    public ReleaseRow activateRelease(
            GovernanceRow row,
            ChannelRow channel,
            String releasePid,
            String releaseItemPid,
            String channelPid,
            JsonNode manifest,
            String manifestChecksum,
            JsonNode snapshot,
            String snapshotChecksum,
            long actorUserId) {
        Long releaseId = jdbcTemplate.queryForObject("""
                INSERT INTO ab_authoring_release (
                    pid, tenant_id, env_id, change_set_id, change_set_revision,
                    previous_release_pid, status, manifest, manifest_checksum, created_by)
                VALUES (?, ?, ?, ?, ?, ?, 'PREPARING', ?::jsonb, ?, ?)
                RETURNING id
                """, Long.class, releasePid, row.tenantId(), row.envId(), row.changeSetId(),
                row.revision(), channel == null ? null : channel.activeReleasePid(),
                json(manifest), manifestChecksum, actorUserId);
        jdbcTemplate.update("""
                INSERT INTO ab_authoring_release_item (
                    pid, tenant_id, env_id, release_id, resource_type, resource_pid,
                    source_version, snapshot, snapshot_checksum)
                VALUES (?, ?, ?, ?, 'PAGE_SCHEMA', ?, ?, ?::jsonb, ?)
                """, releaseItemPid, row.tenantId(), row.envId(), releaseId,
                row.resourcePid(), row.revision(), json(snapshot), snapshotChecksum);
        long channelVersion = moveChannel(
                row, channel, channelPid, releaseId, actorUserId);
        if (channel != null) {
            jdbcTemplate.update("""
                    UPDATE ab_authoring_release SET status = 'SUPERSEDED'
                    WHERE id = ? AND tenant_id = ? AND env_id = ? AND status = 'ACTIVE'
                    """, channel.activeReleaseId(), row.tenantId(), row.envId());
        }
        jdbcTemplate.update("""
                UPDATE ab_authoring_release
                SET status = 'ACTIVE', activated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND env_id = ? AND status = 'PREPARING'
                """, releaseId, row.tenantId(), row.envId());
        int changeSetUpdated = jdbcTemplate.update("""
                UPDATE ab_authoring_change_set
                SET status = 'PUBLISHED', publish_state = 'PUBLISHED',
                    published_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND env_id = ? AND revision = ?
                  AND status = 'APPROVED' AND publish_state = 'READY'
                """, row.changeSetId(), row.tenantId(), row.envId(), row.revision());
        requireOne(changeSetUpdated, "authoring.publish.conflict");
        jdbcTemplate.update("""
                UPDATE ab_authoring_config_session
                SET state = 'CLOSED', updated_at = CURRENT_TIMESTAMP
                WHERE change_set_id = ? AND tenant_id = ? AND env_id = ?
                """, row.changeSetId(), row.tenantId(), row.envId());
        return new ReleaseRow(releaseId, releasePid, row.changeSetPid(), row.revision(),
                channel == null ? null : channel.activeReleasePid(), "ACTIVE",
                manifestChecksum, channelVersion, Instant.now());
    }

    public RollbackRow lockRollback(String releasePid, long tenantId, long envId) {
        return jdbcTemplate.query("""
                        SELECT c.id AS channel_id, c.pid AS channel_pid, c.row_version,
                               c.tenant_id, c.env_id,
                               active.id AS active_release_id, active.pid AS active_release_pid,
                               active.change_set_id AS active_change_set_id,
                               prior.id AS prior_release_id, prior.pid AS prior_release_pid,
                               prior.change_set_revision AS prior_change_set_revision,
                               prior.manifest_checksum AS prior_manifest_checksum,
                               prior.change_set_id AS prior_change_set_id,
                               prior_cs.pid AS prior_change_set_pid
                        FROM ab_authoring_release_channel c
                        JOIN ab_authoring_release active ON active.id = c.active_release_id
                        JOIN ab_authoring_release prior ON prior.id = c.previous_release_id
                        JOIN ab_authoring_change_set prior_cs ON prior_cs.id = prior.change_set_id
                        WHERE c.tenant_id = ? AND c.env_id = ?
                          AND active.pid = ? AND active.status = 'ACTIVE'
                          AND prior.status = 'SUPERSEDED'
                        FOR UPDATE OF c, active, prior
                        """,
                resultSet -> resultSet.next() ? mapRollback(resultSet) : null,
                tenantId, envId, releasePid);
    }

    public int countNonReversibleItems(RollbackRow rollback) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ab_authoring_change_item
                WHERE tenant_id = ? AND env_id = ? AND change_set_id = ?
                  AND reversibility <> 'REVERSIBLE'
                """, Integer.class, rollback.tenantId(), rollback.envId(),
                rollback.activeChangeSetId());
        return count == null ? 0 : count;
    }

    public ReleaseRow rollback(RollbackRow rollback, long actorUserId) {
        jdbcTemplate.update("""
                UPDATE ab_authoring_release SET status = 'ROLLED_BACK'
                WHERE id = ? AND tenant_id = ? AND env_id = ? AND status = 'ACTIVE'
                """, rollback.activeReleaseId(), rollback.tenantId(), rollback.envId());
        jdbcTemplate.update("""
                UPDATE ab_authoring_release
                SET status = 'ACTIVE', activated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND env_id = ? AND status = 'SUPERSEDED'
                """, rollback.priorReleaseId(), rollback.tenantId(), rollback.envId());
        Long channelVersion = jdbcTemplate.queryForObject("""
                UPDATE ab_authoring_release_channel
                SET active_release_id = ?, previous_release_id = ?,
                    row_version = row_version + 1, updated_by = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND env_id = ? AND row_version = ?
                RETURNING row_version
                """, Long.class, rollback.priorReleaseId(), rollback.activeReleaseId(), actorUserId,
                rollback.channelId(), rollback.tenantId(), rollback.envId(), rollback.channelVersion());
        jdbcTemplate.update("""
                UPDATE ab_authoring_change_set
                SET publish_state = 'ROLLED_BACK', updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND env_id = ?
                """, rollback.activeChangeSetId(), rollback.tenantId(), rollback.envId());
        return new ReleaseRow(
                rollback.priorReleaseId(), rollback.priorReleasePid(),
                rollback.priorChangeSetPid(), rollback.priorChangeSetRevision(),
                rollback.activeReleasePid(), "ACTIVE", rollback.priorManifestChecksum(),
                channelVersion == null ? rollback.channelVersion() + 1 : channelVersion,
                Instant.now());
    }

    public boolean hasApprovedRevision(GovernanceRow row) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ab_authoring_approval
                WHERE tenant_id = ? AND env_id = ? AND change_set_id = ?
                  AND change_set_revision = ? AND status = 'APPROVED'
                """, Integer.class, row.tenantId(), row.envId(),
                row.changeSetId(), row.revision());
        return count != null && count == 1;
    }

    private void createPendingApproval(GovernanceRow row, long actorUserId) {
        jdbcTemplate.update("""
                INSERT INTO ab_authoring_approval (
                    pid, tenant_id, env_id, change_set_id, change_set_revision, status, reason)
                VALUES (?, ?, ?, ?, ?, 'PENDING', ?)
                ON CONFLICT (change_set_id, change_set_revision)
                DO UPDATE SET status = 'PENDING', reviewer_user_id = NULL,
                              reason = EXCLUDED.reason, decided_at = NULL
                """, com.auraboot.framework.common.util.UniqueIdGenerator.generate(),
                row.tenantId(), row.envId(), row.changeSetId(), row.revision(),
                "Submitted by actor " + actorUserId);
    }

    private long moveChannel(
            GovernanceRow row,
            ChannelRow channel,
            String channelPid,
            long releaseId,
            long actorUserId) {
        if (channel == null) {
            jdbcTemplate.update("""
                    INSERT INTO ab_authoring_release_channel (
                        pid, tenant_id, env_id, resource_type, resource_pid,
                        active_release_id, previous_release_id, row_version, updated_by)
                    VALUES (?, ?, ?, 'PAGE_SCHEMA', ?, ?, NULL, 1, ?)
                    """, channelPid, row.tenantId(), row.envId(), row.resourcePid(),
                    releaseId, actorUserId);
            return 1;
        }
        Long version = jdbcTemplate.queryForObject("""
                UPDATE ab_authoring_release_channel
                SET active_release_id = ?, previous_release_id = ?,
                    row_version = row_version + 1, updated_by = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND env_id = ? AND row_version = ?
                RETURNING row_version
                """, Long.class, releaseId, channel.activeReleaseId(), actorUserId,
                channel.channelId(), row.tenantId(), row.envId(), channel.rowVersion());
        return version == null ? channel.rowVersion() + 1 : version;
    }

    private GovernanceRow mapGovernance(ResultSet resultSet) throws SQLException {
        return new GovernanceRow(
                resultSet.getLong("change_set_id"), resultSet.getString("change_set_pid"),
                resultSet.getLong("tenant_id"), resultSet.getLong("env_id"),
                resultSet.getLong("owner_user_id"), resultSet.getString("status"),
                resultSet.getLong("revision"), resultSet.getString("risk_level"),
                resultSet.getString("route"), resultSet.getString("publish_policy"),
                resultSet.getString("validation_state"),
                resultSet.getString("approval_state"), resultSet.getString("publish_state"),
                resultSet.getString("manifest_checksum"),
                resultSet.getString("base_release_pid"),
                resultSet.getLong("resource_draft_id"),
                resultSet.getString("resource_draft_pid"),
                resultSet.getString("resource_pid"), resultSet.getLong("base_version"),
                resultSet.getString("base_checksum"),
                resultSet.getString("draft_manifest_checksum"),
                parse(resultSet.getString("snapshot")));
    }

    private RollbackRow mapRollback(ResultSet resultSet) throws SQLException {
        return new RollbackRow(
                resultSet.getLong("channel_id"), resultSet.getString("channel_pid"),
                resultSet.getLong("row_version"),
                resultSet.getLong("active_release_id"),
                resultSet.getString("active_release_pid"),
                resultSet.getLong("active_change_set_id"),
                resultSet.getLong("prior_release_id"),
                resultSet.getString("prior_release_pid"),
                resultSet.getLong("prior_change_set_id"),
                resultSet.getString("prior_change_set_pid"),
                resultSet.getLong("prior_change_set_revision"),
                resultSet.getString("prior_manifest_checksum"),
                resultSet.getLong("tenant_id"), resultSet.getLong("env_id"));
    }

    private void requireOne(int rows, String reason) {
        if (rows != 1) {
            throw new ResponseStatusException(CONFLICT, reason);
        }
    }

    private String json(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("authoring.json.serialize-failed", e);
        }
    }

    private JsonNode parse(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException e) {
            throw new DataRetrievalFailureException("Invalid authoring JSON", e);
        }
    }

    public record GovernanceRow(
            long changeSetId,
            String changeSetPid,
            long tenantId,
            long envId,
            long ownerUserId,
            String status,
            long revision,
            String riskLevel,
            String route,
            String publishPolicy,
            String validationState,
            String approvalState,
            String publishState,
            String manifestChecksum,
            String baseReleasePid,
            long resourceDraftId,
            String resourceDraftPid,
            String resourcePid,
            long baseVersion,
            String baseChecksum,
            String draftManifestChecksum,
            JsonNode snapshot) {
    }

    public record ChannelRow(
            long channelId,
            String channelPid,
            long rowVersion,
            long activeReleaseId,
            String activeReleasePid) {
    }

    public record ReleaseRow(
            long releaseId,
            String releasePid,
            String changeSetPid,
            long changeSetRevision,
            String previousReleasePid,
            String status,
            String manifestChecksum,
            long channelVersion,
            Instant activatedAt) {
    }

    public record RollbackRow(
            long channelId,
            String channelPid,
            long channelVersion,
            long activeReleaseId,
            String activeReleasePid,
            long activeChangeSetId,
            long priorReleaseId,
            String priorReleasePid,
            long priorChangeSetId,
            String priorChangeSetPid,
            long priorChangeSetRevision,
            String priorManifestChecksum,
            long tenantId,
            long envId) {
    }
}
