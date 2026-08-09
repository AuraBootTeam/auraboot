package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.authoring.workspace.AuthoringChangeSetSplitter.ChangeItem;
import com.auraboot.framework.authoring.workspace.AuthoringChangeSetSplitter.SplitPlan;
import com.auraboot.framework.authoring.workspace.AuthoringDraftValidator.ValidationResult;
import com.auraboot.framework.authoring.workspace.AuthoringImpactAnalyzer.ImpactResult;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceRepository.AggregatePolicy;
import com.auraboot.framework.common.util.UniqueIdGenerator;
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
import java.util.ArrayList;
import java.util.List;

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
        String lockClause = lock ? " FOR UPDATE OF cs, rd, wl" : "";
        return jdbcTemplate.query("""
                        SELECT cs.id AS change_set_id, cs.pid AS change_set_pid,
                               cs.tenant_id, cs.env_id, cs.owner_user_id, cs.title, cs.status,
                               cs.origin, cs.revision, cs.risk_level, cs.route, cs.publish_policy,
                               cs.validation_state, cs.impact_state,
                               cs.approval_state, cs.publish_state,
                               cs.manifest_checksum, cs.base_release_pid,
                               cs.source_change_set_id, cs.source_change_set_revision,
                               cs.lineage::text,
                               rd.id AS resource_draft_id, rd.pid AS resource_draft_pid,
                               rd.resource_pid, rd.base_version, rd.base_checksum,
                               rd.manifest_checksum AS draft_manifest_checksum,
                               rd.snapshot::text, rd.ownership_scope,
                               rd.source_ownership_scope, rd.source_resource_pid, rd.override_pid,
                               impact_run.dependency_checksum AS impact_dependency_checksum,
                               impact_run.dependencies::text AS impact_dependencies,
                               wl.session_id AS lease_session_id,
                               wl.holder_user_id AS lease_holder_user_id,
                               wl.lease_revision
                        FROM ab_authoring_change_set cs
                        JOIN ab_authoring_resource_draft rd
                          ON rd.change_set_id = cs.id
                         AND rd.tenant_id = cs.tenant_id
                         AND rd.env_id = cs.env_id
                         AND rd.resource_type = 'PAGE_SCHEMA'
                        JOIN ab_authoring_writer_lease wl
                          ON wl.change_set_id = cs.id
                         AND wl.tenant_id = cs.tenant_id
                         AND wl.env_id = cs.env_id
                        LEFT JOIN LATERAL (
                            SELECT ir.dependency_checksum, ir.dependencies
                            FROM ab_authoring_impact_run ir
                            WHERE ir.tenant_id = cs.tenant_id
                              AND ir.env_id = cs.env_id
                              AND ir.change_set_id = cs.id
                              AND ir.change_set_revision = cs.revision
                            ORDER BY ir.created_at DESC, ir.id DESC
                            LIMIT 1
                        ) impact_run ON TRUE
                        WHERE cs.tenant_id = ? AND cs.env_id = ? AND cs.pid = ?
                          AND cs.deleted_flag = FALSE
                        """ + lockClause,
                resultSet -> resultSet.next() ? mapGovernance(resultSet) : null,
                tenantId, envId, changeSetPid);
    }

    public List<ChangeItem> findActiveItems(GovernanceRow row) {
        return jdbcTemplate.query("""
                        SELECT ci.id, ci.pid, ci.block_id, ci.property_path, ci.operation,
                               ci.old_value::text, ci.new_value::text, ci.effect_tags::text,
                               ci.risk_level, ci.route, ci.publish_policy, ci.reversibility,
                               ci.manifest_checksum, ci.base_revision, ci.result_revision,
                               ci.actor_user_id, ci.created_at, ci.source_change_item_id,
                               source_item.pid AS source_change_item_pid,
                               ci.dependency_snapshot::text
                        FROM ab_authoring_change_item ci
                        LEFT JOIN ab_authoring_change_item source_item
                          ON source_item.id = ci.source_change_item_id
                         AND source_item.tenant_id = ci.tenant_id
                         AND source_item.env_id = ci.env_id
                        WHERE ci.tenant_id = ? AND ci.env_id = ? AND ci.change_set_id = ?
                          AND NOT EXISTS (
                              SELECT 1 FROM ab_authoring_change_item_split split_item
                              WHERE split_item.tenant_id = ci.tenant_id
                                AND split_item.env_id = ci.env_id
                                AND split_item.source_change_item_id = ci.id)
                        ORDER BY ci.result_revision, ci.id
                        """,
                (resultSet, rowNumber) -> new ChangeItem(
                        resultSet.getLong("id"),
                        resultSet.getString("pid"),
                        resultSet.getString("block_id"),
                        resultSet.getString("property_path"),
                        resultSet.getString("operation"),
                        nullableParse(resultSet.getString("old_value")),
                        nullableParse(resultSet.getString("new_value")),
                        parse(resultSet.getString("effect_tags")),
                        resultSet.getString("risk_level"),
                        resultSet.getString("route"),
                        resultSet.getString("publish_policy"),
                        resultSet.getString("reversibility"),
                        resultSet.getString("manifest_checksum"),
                        resultSet.getLong("base_revision"),
                        resultSet.getLong("result_revision"),
                        resultSet.getLong("actor_user_id"),
                        resultSet.getTimestamp("created_at").toInstant(),
                        resultSet.getObject("source_change_item_id", Long.class),
                        resultSet.getString("source_change_item_pid"),
                        parse(resultSet.getString("dependency_snapshot"))),
                row.tenantId(), row.envId(), row.changeSetId());
    }

    public void recordValidation(
            GovernanceRow row,
            ValidationResult result,
            String validationRunPid,
            String snapshotChecksum,
            long actorUserId) {
        jdbcTemplate.update("""
                INSERT INTO ab_authoring_validation_run (
                    pid, tenant_id, env_id, change_set_id, change_set_revision,
                    resource_draft_id, status, validator_version, manifest_checksum,
                    snapshot_checksum, error_count, issues, actor_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """,
                validationRunPid, row.tenantId(), row.envId(), row.changeSetId(), row.revision(),
                row.resourceDraftId(), result.status(), AuthoringDraftValidator.VALIDATOR_VERSION,
                row.draftManifestChecksum(), snapshotChecksum, result.errorCount(),
                json(objectMapper.valueToTree(result.issues())), actorUserId);
        int changeSetUpdated = jdbcTemplate.update("""
                UPDATE ab_authoring_change_set
                SET validation_state = ?,
                    publish_state = CASE WHEN ? = 'INVALID' THEN 'DRAFT' ELSE publish_state END,
                    stale_reason = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND env_id = ? AND revision = ?
                  AND status IN ('DRAFT', 'REJECTED')
                """,
                result.status(), result.status(), row.changeSetId(), row.tenantId(), row.envId(),
                row.revision());
        requireOne(changeSetUpdated, "authoring.validation.revision-conflict");
        int resourceUpdated = jdbcTemplate.update("""
                UPDATE ab_authoring_resource_draft
                SET validation_state = ?, stale_reason = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND env_id = ? AND revision = ?
                """,
                result.status(), row.resourceDraftId(), row.tenantId(), row.envId(), row.revision());
        requireOne(resourceUpdated, "authoring.validation.resource-revision-conflict");
    }

    public void recordImpact(
            GovernanceRow row,
            ImpactResult result,
            String impactRunPid,
            String snapshotChecksum,
            long actorUserId) {
        jdbcTemplate.update("""
                INSERT INTO ab_authoring_impact_run (
                    pid, tenant_id, env_id, change_set_id, change_set_revision,
                    resource_draft_id, status, analyzer_version, manifest_checksum,
                    snapshot_checksum, dependency_checksum, dependencies,
                    failure_code, actor_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                """,
                impactRunPid, row.tenantId(), row.envId(), row.changeSetId(), row.revision(),
                row.resourceDraftId(), result.status(), AuthoringImpactAnalyzer.ANALYZER_VERSION,
                row.draftManifestChecksum(), snapshotChecksum, result.dependencyChecksum(),
                json(result.dependencies()), result.failureCode(), actorUserId);
        int changeSetUpdated = jdbcTemplate.update("""
                UPDATE ab_authoring_change_set
                SET impact_state = ?,
                    publish_state = CASE WHEN ? = 'FAILED' THEN 'DRAFT' ELSE publish_state END,
                    stale_reason = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND env_id = ? AND revision = ?
                  AND status IN ('DRAFT', 'REJECTED')
                """,
                result.status(), result.status(), row.changeSetId(), row.tenantId(), row.envId(),
                row.revision());
        requireOne(changeSetUpdated, "authoring.impact.revision-conflict");
        int resourceUpdated = jdbcTemplate.update("""
                UPDATE ab_authoring_resource_draft
                SET impact_state = ?, stale_reason = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND env_id = ? AND revision = ?
                """,
                result.status(), row.resourceDraftId(), row.tenantId(), row.envId(), row.revision());
        requireOne(resourceUpdated, "authoring.impact.resource-revision-conflict");
    }

    public void markStale(GovernanceRow row, String reason) {
        int changeSetUpdated = jdbcTemplate.update("""
                UPDATE ab_authoring_change_set
                SET validation_state = CASE
                        WHEN validation_state = 'VALID' THEN 'STALE' ELSE validation_state END,
                    impact_state = CASE
                        WHEN impact_state = 'KNOWN' THEN 'STALE' ELSE impact_state END,
                    approval_state = CASE
                        WHEN approval_state IN ('PENDING', 'APPROVED') THEN 'STALE'
                        ELSE approval_state END,
                    publish_state = 'DRAFT', stale_reason = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND env_id = ? AND revision = ?
                """, reason, row.changeSetId(), row.tenantId(), row.envId(), row.revision());
        requireOne(changeSetUpdated, "authoring.stale.revision-conflict");
        int resourceUpdated = jdbcTemplate.update("""
                UPDATE ab_authoring_resource_draft
                SET validation_state = CASE
                        WHEN validation_state = 'VALID' THEN 'STALE' ELSE validation_state END,
                    impact_state = CASE
                        WHEN impact_state = 'KNOWN' THEN 'STALE' ELSE impact_state END,
                    stale_reason = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND env_id = ? AND revision = ?
                """, reason, row.resourceDraftId(), row.tenantId(), row.envId(), row.revision());
        requireOne(resourceUpdated, "authoring.stale.resource-revision-conflict");
    }

    public void submit(GovernanceRow row, boolean approvalRequired, long actorUserId) {
        String status = approvalRequired ? "IN_REVIEW" : "APPROVED";
        String approvalState = approvalRequired ? "PENDING" : "NOT_REQUIRED";
        String publishState = approvalRequired ? "DRAFT" : "READY";
        int updated = jdbcTemplate.update("""
                UPDATE ab_authoring_change_set
                SET status = ?, approval_state = ?,
                    publish_state = ?, submitted_at = CURRENT_TIMESTAMP,
                    approved_at = CASE WHEN ? THEN NULL ELSE CURRENT_TIMESTAMP END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND env_id = ? AND revision = ?
                  AND status IN ('DRAFT', 'REJECTED')
                  AND validation_state = 'VALID' AND impact_state = 'KNOWN'
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
                  AND impact_state = 'KNOWN'
                """, row.changeSetId(), row.tenantId(), row.envId(), row.revision());
        requireOne(changeSetUpdated, "authoring.approval.conflict");
    }

    public void withdrawReview(
            GovernanceRow row,
            long sessionId,
            long ownerUserId,
            Instant leaseUntil) {
        invalidateApproval(row, "PENDING", "authoring.approval.not-pending");
        resumeEditing(row, "IN_REVIEW", "DRAFT", "STALE", sessionId, ownerUserId, leaseUntil);
    }

    public void reopenApproved(
            GovernanceRow row,
            long sessionId,
            long ownerUserId,
            Instant leaseUntil) {
        String nextApprovalState = "NOT_REQUIRED";
        if ("APPROVED".equals(row.approvalState())) {
            invalidateApproval(row, "APPROVED", "authoring.approval.not-approved");
            nextApprovalState = "STALE";
        }
        resumeEditing(
                row, "APPROVED", "DRAFT", nextApprovalState,
                sessionId, ownerUserId, leaseUntil);
    }

    public void reject(
            GovernanceRow row,
            long reviewerUserId,
            String reason,
            Instant leaseUntil) {
        decideApproval(row, "PENDING", "REJECTED", reviewerUserId, reason,
                "authoring.approval.not-pending");
        resumeEditing(
                row, "IN_REVIEW", "REJECTED", "REJECTED",
                row.leaseSessionId(), row.leaseHolderUserId(), leaseUntil);
    }

    private void decideApproval(
            GovernanceRow row,
            String expectedStatus,
            String resultStatus,
            long actorUserId,
            String reason,
            String conflictReason) {
        int updated = jdbcTemplate.update("""
                UPDATE ab_authoring_approval
                SET status = ?, reviewer_user_id = ?, reason = ?,
                    decided_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND env_id = ? AND change_set_id = ?
                  AND change_set_revision = ? AND status = ?
                """, resultStatus, actorUserId, reason, row.tenantId(), row.envId(),
                row.changeSetId(), row.revision(), expectedStatus);
        requireOne(updated, conflictReason);
    }

    private void invalidateApproval(
            GovernanceRow row,
            String expectedStatus,
            String conflictReason) {
        int updated = jdbcTemplate.update("""
                UPDATE ab_authoring_approval
                SET status = 'STALE'
                WHERE tenant_id = ? AND env_id = ? AND change_set_id = ?
                  AND change_set_revision = ? AND status = ?
                """, row.tenantId(), row.envId(), row.changeSetId(), row.revision(), expectedStatus);
        requireOne(updated, conflictReason);
    }

    private void resumeEditing(
            GovernanceRow row,
            String expectedStatus,
            String resultStatus,
            String approvalState,
            long writerSessionId,
            long writerUserId,
            Instant leaseUntil) {
        int resourceUpdated = jdbcTemplate.update("""
                UPDATE ab_authoring_resource_draft
                SET revision = revision + 1, validation_state = 'UNVALIDATED',
                    impact_state = 'UNKNOWN',
                    stale_reason = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND env_id = ? AND revision = ?
                """, row.resourceDraftId(), row.tenantId(), row.envId(), row.revision());
        requireOne(resourceUpdated, "authoring.review.resource-revision-conflict");

        int changeSetUpdated = jdbcTemplate.update("""
                UPDATE ab_authoring_change_set
                SET revision = revision + 1, status = ?,
                    validation_state = 'UNVALIDATED', impact_state = 'UNKNOWN',
                    approval_state = ?,
                    publish_state = 'DRAFT', stale_reason = NULL,
                    submitted_at = NULL, approved_at = NULL, published_at = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND env_id = ? AND revision = ?
                  AND status = ?
                """, resultStatus, approvalState, row.changeSetId(), row.tenantId(), row.envId(),
                row.revision(), expectedStatus);
        requireOne(changeSetUpdated, "authoring.review.revision-conflict");

        int sessionUpdated = jdbcTemplate.update("""
                UPDATE ab_authoring_config_session
                SET revision = ?,
                    state = CASE
                        WHEN expires_at <= CURRENT_TIMESTAMP THEN 'EXPIRED'
                        WHEN id = ? THEN 'ACTIVE'
                        ELSE 'READ_ONLY'
                    END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE change_set_id = ? AND tenant_id = ? AND env_id = ?
                  AND state IN ('ACTIVE', 'READ_ONLY')
                """, row.revision() + 1, writerSessionId,
                row.changeSetId(), row.tenantId(), row.envId());
        if (sessionUpdated == 0) {
            throw new ResponseStatusException(CONFLICT, "authoring.review.session-conflict");
        }

        int leaseUpdated = jdbcTemplate.update("""
                UPDATE ab_authoring_writer_lease
                SET session_id = ?, holder_user_id = ?, acquired_at = CURRENT_TIMESTAMP,
                    leased_until = ?, lease_revision = lease_revision + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE change_set_id = ? AND tenant_id = ? AND env_id = ?
                  AND lease_revision = ?
                """, writerSessionId, writerUserId, Timestamp.from(leaseUntil),
                row.changeSetId(), row.tenantId(), row.envId(), row.leaseRevision());
        requireOne(leaseUpdated, "authoring.review.lease-conflict");
    }

    public SplitPersistenceResult split(SplitPersistenceCommand command) {
        GovernanceRow row = command.source();
        SplitPlan plan = command.plan();
        long sourceResultRevision = row.revision() + 1;

        int sourceDraftUpdated = jdbcTemplate.update("""
                UPDATE ab_authoring_resource_draft
                SET snapshot = ?::jsonb, revision = ?, validation_state = 'UNVALIDATED',
                    impact_state = 'UNKNOWN',
                    stale_reason = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND env_id = ? AND revision = ?
                """, json(plan.sourceSnapshot()), sourceResultRevision,
                row.resourceDraftId(), row.tenantId(), row.envId(), row.revision());
        requireOne(sourceDraftUpdated, "authoring.split.source-revision-conflict");

        int sourceUpdated = jdbcTemplate.update("""
                UPDATE ab_authoring_change_set
                SET revision = ?, status = 'DRAFT', risk_level = ?, route = ?,
                    publish_policy = ?, validation_state = 'UNVALIDATED',
                    impact_state = 'UNKNOWN',
                    approval_state = ?, publish_state = 'DRAFT', stale_reason = NULL,
                    submitted_at = NULL, approved_at = NULL, published_at = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND env_id = ? AND revision = ?
                  AND status IN ('DRAFT', 'REJECTED')
                """, sourceResultRevision,
                command.sourceAggregate().riskLevel(), command.sourceAggregate().route(),
                command.sourceAggregate().publishPolicy(), command.sourceAggregate().approvalState(),
                row.changeSetId(), row.tenantId(), row.envId(), row.revision());
        requireOne(sourceUpdated, "authoring.split.source-revision-conflict");

        int sourceSessionsUpdated = jdbcTemplate.update("""
                UPDATE ab_authoring_config_session
                SET revision = ?,
                    state = CASE
                        WHEN expires_at <= CURRENT_TIMESTAMP THEN 'EXPIRED'
                        WHEN id = ? THEN 'ACTIVE'
                        ELSE 'READ_ONLY'
                    END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE change_set_id = ? AND tenant_id = ? AND env_id = ?
                  AND state IN ('ACTIVE', 'READ_ONLY')
                """, sourceResultRevision, command.sourceSessionId(),
                row.changeSetId(), row.tenantId(), row.envId());
        if (sourceSessionsUpdated == 0) {
            throw new ResponseStatusException(CONFLICT, "authoring.split.source-session-conflict");
        }
        int sourceLeaseUpdated = jdbcTemplate.update("""
                UPDATE ab_authoring_writer_lease
                SET leased_until = ?, lease_revision = lease_revision + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE change_set_id = ? AND tenant_id = ? AND env_id = ?
                  AND session_id = ? AND holder_user_id = ? AND lease_revision = ?
                  AND leased_until > CURRENT_TIMESTAMP
                """, Timestamp.from(command.leaseUntil()), row.changeSetId(),
                row.tenantId(), row.envId(), command.sourceSessionId(), command.actorUserId(),
                row.leaseRevision());
        requireOne(sourceLeaseUpdated, "authoring.writer-lease.lost");

        long targetRevision = plan.targetItems().size() + 1L;
        Long targetChangeSetId = jdbcTemplate.queryForObject("""
                INSERT INTO ab_authoring_change_set (
                    pid, tenant_id, env_id, owner_user_id, title, status, revision,
                    origin, base_release_pid, manifest_checksum, risk_level, route, publish_policy,
                    validation_state, approval_state, publish_state,
                    source_change_set_id, source_change_set_revision, lineage)
                VALUES (?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?,
                        'UNVALIDATED', ?, 'DRAFT', ?, ?, ?::jsonb)
                RETURNING id
                """, Long.class,
                command.targetChangeSetPid(), row.tenantId(), row.envId(), command.actorUserId(),
                command.title(), targetRevision, row.origin(), row.baseReleasePid(), row.manifestChecksum(),
                command.targetAggregate().riskLevel(), command.targetAggregate().route(),
                command.targetAggregate().publishPolicy(), command.targetAggregate().approvalState(),
                row.changeSetId(), row.revision(), json(command.lineage()));
        if (targetChangeSetId == null) {
            throw new ResponseStatusException(CONFLICT, "authoring.split.target-create-failed");
        }

        Long targetDraftId = jdbcTemplate.queryForObject("""
                INSERT INTO ab_authoring_resource_draft (
                    pid, tenant_id, env_id, change_set_id, resource_type, resource_pid,
                    ownership_scope, source_ownership_scope, source_resource_pid, override_pid,
                    base_version, base_checksum, manifest_checksum, snapshot, revision,
                    validation_state)
                VALUES (
                    ?, ?, ?, ?, 'PAGE_SCHEMA', ?,
                    ?, ?, ?, ?,
                    ?, ?, ?, ?::jsonb, ?,
                    'UNVALIDATED')
                RETURNING id
                """, Long.class,
                command.targetResourceDraftPid(), row.tenantId(), row.envId(), targetChangeSetId,
                row.resourcePid(), row.ownershipScope(), row.sourceOwnershipScope(),
                row.sourceResourcePid(), row.overridePid(), row.baseVersion(), row.baseChecksum(),
                row.draftManifestChecksum(), json(plan.targetSnapshot()), targetRevision);
        if (targetDraftId == null) {
            throw new ResponseStatusException(CONFLICT, "authoring.split.target-create-failed");
        }

        Long targetSessionId = jdbcTemplate.queryForObject("""
                INSERT INTO ab_authoring_config_session (
                    pid, tenant_id, env_id, actor_user_id, change_set_id, page_pid, state,
                    workspace_mode, interaction_context, revision, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', 'AUTHORING', ?::jsonb, ?, ?)
                RETURNING id
                """, Long.class,
                command.targetSessionPid(), row.tenantId(), row.envId(), command.actorUserId(),
                targetChangeSetId, row.resourcePid(), json(command.interactionContext()),
                targetRevision, Timestamp.from(command.sessionExpiresAt()));
        if (targetSessionId == null) {
            throw new ResponseStatusException(CONFLICT, "authoring.split.target-create-failed");
        }
        jdbcTemplate.update("""
                INSERT INTO ab_authoring_writer_lease (
                    pid, tenant_id, env_id, change_set_id, session_id, holder_user_id,
                    lease_revision, leased_until)
                VALUES (?, ?, ?, ?, ?, ?, 1, ?)
                """, command.targetLeasePid(), row.tenantId(), row.envId(), targetChangeSetId,
                targetSessionId, command.actorUserId(), Timestamp.from(command.leaseUntil()));

        List<TargetItem> targetItems = new ArrayList<>();
        long itemRevision = 1;
        for (ChangeItem item : plan.targetItems()) {
            String targetItemPid = UniqueIdGenerator.generate();
            JsonNode dependencies = plan.targetDependencySnapshots().get(item.pid());
            Long targetItemId = jdbcTemplate.queryForObject("""
                    INSERT INTO ab_authoring_change_item (
                        pid, tenant_id, env_id, change_set_id, resource_draft_id, block_id,
                        property_path, operation, old_value, new_value, effect_tags,
                        risk_level, route, publish_policy, reversibility, manifest_checksum,
                        ownership_scope, source_resource_pid, override_pid,
                        base_revision, result_revision, actor_user_id,
                        source_change_item_id, dependency_snapshot, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb,
                            ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                    RETURNING id
                    """, Long.class,
                    targetItemPid, row.tenantId(), row.envId(), targetChangeSetId, targetDraftId,
                    item.blockId(), item.propertyPath(), item.operation(),
                    nullableJson(item.oldValue()), nullableJson(item.newValue()),
                    json(item.effectTags()), item.riskLevel(), item.route(), item.publishPolicy(),
                    item.reversibility(), item.manifestChecksum(), row.ownershipScope(),
                    row.sourceResourcePid(), row.overridePid(), itemRevision, itemRevision + 1,
                    item.actorUserId(), item.id(), json(dependencies),
                    Timestamp.from(item.createdAt()));
            if (targetItemId == null) {
                throw new ResponseStatusException(CONFLICT, "authoring.split.target-item-failed");
            }
            targetItems.add(new TargetItem(item, targetItemId, targetItemPid, dependencies));
            itemRevision++;
        }

        Long splitId = jdbcTemplate.queryForObject("""
                INSERT INTO ab_authoring_change_set_split (
                    pid, tenant_id, env_id, source_change_set_id, source_change_set_revision,
                    target_change_set_id, actor_user_id, reason, dependency_snapshot)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                RETURNING id
                """, Long.class,
                command.splitPid(), row.tenantId(), row.envId(), row.changeSetId(), row.revision(),
                targetChangeSetId, command.actorUserId(), command.reason(),
                json(command.dependencySnapshot()));
        if (splitId == null) {
            throw new ResponseStatusException(CONFLICT, "authoring.split.record-failed");
        }
        for (TargetItem targetItem : targetItems) {
            jdbcTemplate.update("""
                    INSERT INTO ab_authoring_change_item_split (
                        pid, tenant_id, env_id, split_id, source_change_item_id,
                        target_change_item_id, dependency_snapshot)
                    VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                    """, UniqueIdGenerator.generate(), row.tenantId(), row.envId(), splitId,
                    targetItem.source().id(), targetItem.id(), json(targetItem.dependencies()));
        }
        return new SplitPersistenceResult(
                command.targetChangeSetPid(), command.targetSessionPid(), sourceResultRevision,
                targetRevision);
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

    public ReleaseHistorySnapshot findReleaseHistory(
            GovernanceRow row,
            int offset,
            int limit) {
        ReleaseChannelHistory channel = jdbcTemplate.query("""
                        SELECT c.row_version,
                               active.pid AS active_release_pid,
                               previous.pid AS previous_release_pid,
                               previous.status AS previous_release_status,
                               COUNT(ci.id) FILTER (
                                   WHERE ci.reversibility = 'REVERSIBLE') AS reversible_count,
                               COUNT(ci.id) FILTER (
                                   WHERE ci.reversibility = 'COMPENSATABLE') AS compensatable_count,
                               COUNT(ci.id) FILTER (
                                   WHERE ci.reversibility = 'FORWARD_ONLY') AS forward_only_count
                        FROM ab_authoring_release_channel c
                        JOIN ab_authoring_release active
                          ON active.id = c.active_release_id
                         AND active.tenant_id = c.tenant_id
                         AND active.env_id = c.env_id
                        LEFT JOIN ab_authoring_release previous
                          ON previous.id = c.previous_release_id
                         AND previous.tenant_id = c.tenant_id
                         AND previous.env_id = c.env_id
                        LEFT JOIN ab_authoring_change_item ci
                          ON ci.change_set_id = active.change_set_id
                         AND ci.tenant_id = active.tenant_id
                         AND ci.env_id = active.env_id
                         AND NOT EXISTS (
                             SELECT 1 FROM ab_authoring_change_item_split split_item
                             WHERE split_item.tenant_id = ci.tenant_id
                               AND split_item.env_id = ci.env_id
                               AND split_item.source_change_item_id = ci.id)
                        WHERE c.tenant_id = ? AND c.env_id = ?
                          AND c.resource_type = 'PAGE_SCHEMA' AND c.resource_pid = ?
                        GROUP BY c.row_version, active.pid, previous.pid, previous.status
                        """,
                resultSet -> resultSet.next()
                        ? new ReleaseChannelHistory(
                            resultSet.getLong("row_version"),
                            resultSet.getString("active_release_pid"),
                            resultSet.getString("previous_release_pid"),
                            resultSet.getString("previous_release_status"),
                            resultSet.getLong("reversible_count"),
                            resultSet.getLong("compensatable_count"),
                            resultSet.getLong("forward_only_count"))
                        : null,
                row.tenantId(), row.envId(), row.resourcePid());
        Long total = jdbcTemplate.queryForObject("""
                SELECT COUNT(DISTINCT release.id)
                FROM ab_authoring_release release
                JOIN ab_authoring_release_item item
                  ON item.release_id = release.id
                 AND item.tenant_id = release.tenant_id
                 AND item.env_id = release.env_id
                WHERE release.tenant_id = ? AND release.env_id = ?
                  AND item.resource_type = 'PAGE_SCHEMA' AND item.resource_pid = ?
                """, Long.class, row.tenantId(), row.envId(), row.resourcePid());
        List<ReleaseHistoryRow> releases = jdbcTemplate.query("""
                        SELECT release.pid AS release_pid,
                               change_set.pid AS change_set_pid,
                               release.change_set_revision,
                               release.previous_release_pid,
                               release.status,
                               release.manifest_checksum,
                               release.created_at,
                               release.activated_at,
                               item_counts.reversible_count,
                               item_counts.compensatable_count,
                               item_counts.forward_only_count
                        FROM ab_authoring_release release
                        JOIN ab_authoring_change_set change_set
                          ON change_set.id = release.change_set_id
                         AND change_set.tenant_id = release.tenant_id
                         AND change_set.env_id = release.env_id
                        JOIN ab_authoring_release_item item
                          ON item.release_id = release.id
                         AND item.tenant_id = release.tenant_id
                         AND item.env_id = release.env_id
                        LEFT JOIN LATERAL (
                            SELECT
                                COUNT(ci.id) FILTER (
                                    WHERE ci.reversibility = 'REVERSIBLE') AS reversible_count,
                                COUNT(ci.id) FILTER (
                                    WHERE ci.reversibility = 'COMPENSATABLE') AS compensatable_count,
                                COUNT(ci.id) FILTER (
                                    WHERE ci.reversibility = 'FORWARD_ONLY') AS forward_only_count
                            FROM ab_authoring_change_item ci
                            WHERE ci.tenant_id = release.tenant_id
                              AND ci.env_id = release.env_id
                              AND ci.change_set_id = release.change_set_id
                              AND NOT EXISTS (
                                  SELECT 1 FROM ab_authoring_change_item_split split_item
                                  WHERE split_item.tenant_id = ci.tenant_id
                                    AND split_item.env_id = ci.env_id
                                    AND split_item.source_change_item_id = ci.id)
                        ) item_counts ON TRUE
                        WHERE release.tenant_id = ? AND release.env_id = ?
                          AND item.resource_type = 'PAGE_SCHEMA' AND item.resource_pid = ?
                        ORDER BY release.created_at DESC, release.id DESC
                        LIMIT ? OFFSET ?
                        """,
                (resultSet, rowNumber) -> new ReleaseHistoryRow(
                        resultSet.getString("release_pid"),
                        resultSet.getString("change_set_pid"),
                        resultSet.getLong("change_set_revision"),
                        resultSet.getString("previous_release_pid"),
                        resultSet.getString("status"),
                        resultSet.getString("manifest_checksum"),
                        resultSet.getTimestamp("created_at").toInstant(),
                        resultSet.getTimestamp("activated_at") == null
                                ? null : resultSet.getTimestamp("activated_at").toInstant(),
                        resultSet.getLong("reversible_count"),
                        resultSet.getLong("compensatable_count"),
                        resultSet.getLong("forward_only_count")),
                row.tenantId(), row.envId(), row.resourcePid(), limit, offset);
        return new ReleaseHistorySnapshot(channel, releases, total == null ? 0 : total);
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
                    ownership_scope, source_resource_pid, override_pid,
                    source_version, snapshot, snapshot_checksum)
                VALUES (?, ?, ?, ?, 'PAGE_SCHEMA', ?, ?, ?, ?, ?, ?::jsonb, ?)
                """, releaseItemPid, row.tenantId(), row.envId(), releaseId,
                row.resourcePid(), row.ownershipScope(), row.sourceResourcePid(), row.overridePid(),
                row.revision(), json(snapshot), snapshotChecksum);
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
                SELECT COUNT(*) FROM ab_authoring_change_item ci
                WHERE ci.tenant_id = ? AND ci.env_id = ? AND ci.change_set_id = ?
                  AND ci.reversibility <> 'REVERSIBLE'
                  AND NOT EXISTS (
                      SELECT 1 FROM ab_authoring_change_item_split split_item
                      WHERE split_item.tenant_id = ci.tenant_id
                        AND split_item.env_id = ci.env_id
                        AND split_item.source_change_item_id = ci.id)
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
                resultSet.getLong("owner_user_id"), resultSet.getString("title"),
                resultSet.getString("status"), resultSet.getString("origin"),
                resultSet.getLong("revision"), resultSet.getString("risk_level"),
                resultSet.getString("route"), resultSet.getString("publish_policy"),
                resultSet.getString("validation_state"),
                resultSet.getString("impact_state"),
                resultSet.getString("approval_state"), resultSet.getString("publish_state"),
                resultSet.getString("manifest_checksum"),
                resultSet.getString("base_release_pid"),
                resultSet.getObject("source_change_set_id", Long.class),
                resultSet.getObject("source_change_set_revision", Long.class),
                parse(resultSet.getString("lineage")),
                resultSet.getLong("resource_draft_id"),
                resultSet.getString("resource_draft_pid"),
                resultSet.getString("resource_pid"), resultSet.getLong("base_version"),
                resultSet.getString("base_checksum"),
                resultSet.getString("draft_manifest_checksum"),
                parse(resultSet.getString("snapshot")),
                resultSet.getString("ownership_scope"),
                resultSet.getString("source_ownership_scope"),
                resultSet.getString("source_resource_pid"),
                resultSet.getString("override_pid"),
                resultSet.getString("impact_dependency_checksum"),
                nullableParse(resultSet.getString("impact_dependencies")),
                resultSet.getLong("lease_session_id"),
                resultSet.getLong("lease_holder_user_id"),
                resultSet.getLong("lease_revision"));
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

    private String nullableJson(JsonNode value) {
        return value == null ? null : json(value);
    }

    private JsonNode nullableParse(String value) {
        return value == null ? null : parse(value);
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
            String title,
            String status,
            String origin,
            long revision,
            String riskLevel,
            String route,
            String publishPolicy,
            String validationState,
            String impactState,
            String approvalState,
            String publishState,
            String manifestChecksum,
            String baseReleasePid,
            Long sourceChangeSetId,
            Long sourceChangeSetRevision,
            JsonNode lineage,
            long resourceDraftId,
            String resourceDraftPid,
            String resourcePid,
            long baseVersion,
            String baseChecksum,
            String draftManifestChecksum,
            JsonNode snapshot,
            String ownershipScope,
            String sourceOwnershipScope,
            String sourceResourcePid,
            String overridePid,
            String impactDependencyChecksum,
            JsonNode impactDependencies,
            long leaseSessionId,
            long leaseHolderUserId,
            long leaseRevision) {
    }

    public record SplitPersistenceCommand(
            GovernanceRow source,
            SplitPlan plan,
            AggregatePolicy sourceAggregate,
            AggregatePolicy targetAggregate,
            long sourceSessionId,
            long actorUserId,
            String targetChangeSetPid,
            String targetResourceDraftPid,
            String targetSessionPid,
            String targetLeasePid,
            String splitPid,
            String title,
            String reason,
            JsonNode interactionContext,
            JsonNode lineage,
            JsonNode dependencySnapshot,
            Instant sessionExpiresAt,
            Instant leaseUntil) {
    }

    public record SplitPersistenceResult(
            String targetChangeSetPid,
            String targetSessionPid,
            long sourceRevision,
            long targetRevision) {
    }

    private record TargetItem(
            ChangeItem source,
            long id,
            String pid,
            JsonNode dependencies) {
    }

    public record ChannelRow(
            long channelId,
            String channelPid,
            long rowVersion,
            long activeReleaseId,
            String activeReleasePid) {
    }

    public record ReleaseChannelHistory(
            long channelVersion,
            String activeReleasePid,
            String previousReleasePid,
            String previousReleaseStatus,
            long reversibleItemCount,
            long compensatableItemCount,
            long forwardOnlyItemCount) {
    }

    public record ReleaseHistoryRow(
            String releasePid,
            String changeSetPid,
            long changeSetRevision,
            String previousReleasePid,
            String status,
            String manifestChecksum,
            Instant createdAt,
            Instant activatedAt,
            long reversibleItemCount,
            long compensatableItemCount,
            long forwardOnlyItemCount) {
    }

    public record ReleaseHistorySnapshot(
            ReleaseChannelHistory channel,
            List<ReleaseHistoryRow> releases,
            long total) {
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
