package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.BoundaryDecision;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.PropertyCapability;
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
import java.util.List;

import static org.springframework.http.HttpStatus.CONFLICT;

/** Explicit tenant/env-scoped persistence for the authoring aggregate. */
@Repository
public class AuthoringWorkspaceRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AuthoringWorkspaceRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public CreatedWorkspace create(CreateWorkspace command) {
        Long changeSetId = jdbcTemplate.queryForObject("""
                INSERT INTO ab_authoring_change_set (
                    pid, tenant_id, env_id, owner_user_id, title, status, revision,
                    origin, base_release_pid, manifest_checksum, risk_level, route, publish_policy,
                    validation_state, approval_state, publish_state)
                VALUES (?, ?, ?, ?, ?, 'DRAFT', 1, ?, ?, ?, 'L0', 'INLINE', 'DIRECT_ALLOWED',
                        'UNVALIDATED', 'NOT_REQUIRED', 'DRAFT')
                RETURNING id
                """, Long.class,
                command.changeSetPid(), command.tenantId(), command.envId(), command.actorUserId(),
                command.title(), command.origin(), command.baseReleasePid(), command.registryChecksum());
        Long resourceDraftId = jdbcTemplate.queryForObject("""
                INSERT INTO ab_authoring_resource_draft (
                    pid, tenant_id, env_id, change_set_id, resource_type, resource_pid,
                    ownership_scope, source_ownership_scope, source_resource_pid, override_pid,
                    base_version, base_checksum, manifest_checksum, snapshot, revision)
                VALUES (?, ?, ?, ?, 'PAGE_SCHEMA', ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, 1)
                RETURNING id
                """, Long.class,
                command.resourceDraftPid(), command.tenantId(), command.envId(), changeSetId,
                command.pagePid(), command.ownershipScope(), command.sourceOwnershipScope(),
                command.sourceResourcePid(), command.overridePid(),
                command.baseVersion(), command.baseChecksum(),
                command.registryChecksum(), json(command.snapshot()));
        Long sessionId = jdbcTemplate.queryForObject("""
                INSERT INTO ab_authoring_config_session (
                    pid, tenant_id, env_id, actor_user_id, change_set_id, page_pid, state,
                    workspace_mode, interaction_context, revision, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', 'AUTHORING', ?::jsonb, 1, ?)
                RETURNING id
                """, Long.class,
                command.sessionPid(), command.tenantId(), command.envId(), command.actorUserId(),
                changeSetId, command.pagePid(), json(command.interactionContext()),
                Timestamp.from(command.expiresAt()));
        jdbcTemplate.update("""
                INSERT INTO ab_authoring_writer_lease (
                    pid, tenant_id, env_id, change_set_id, session_id, holder_user_id,
                    lease_revision, leased_until)
                VALUES (?, ?, ?, ?, ?, ?, 1, ?)
                """,
                command.leasePid(), command.tenantId(), command.envId(), changeSetId, sessionId,
                command.actorUserId(), Timestamp.from(command.leaseUntil()));
        return new CreatedWorkspace(changeSetId, resourceDraftId, sessionId);
    }

    public WorkspaceRow find(long tenantId, long envId, String sessionPid, boolean lock) {
        String lockClause = lock ? " FOR UPDATE OF s, cs, rd, wl" : "";
        return jdbcTemplate.query("""
                        SELECT
                            s.id AS session_id, s.pid AS session_pid, s.tenant_id, s.env_id,
                            s.actor_user_id,
                            s.page_pid, s.state AS session_state, s.workspace_mode,
                            s.interaction_context::text,
                            s.expires_at, s.revision AS session_revision,
                            cs.id AS change_set_id, cs.pid AS change_set_pid,
                            cs.owner_user_id AS change_set_owner_user_id,
                            cs.status AS change_set_status,
                            cs.origin AS change_set_origin,
                            cs.revision AS change_set_revision, cs.risk_level, cs.route,
                            cs.publish_policy, cs.validation_state, cs.impact_state,
                            cs.approval_state,
                            cs.publish_state, cs.manifest_checksum,
                            validation_run.validation_run_pid,
                            validation_run.validation_revision,
                            validation_run.validation_status,
                            validation_run.validation_error_count,
                            validation_run.validation_issues,
                            validation_run.validated_at,
                            impact_run.impact_run_pid,
                            impact_run.impact_revision,
                            impact_run.impact_status,
                            impact_run.impact_dependency_checksum,
                            impact_run.impact_dependencies,
                            impact_run.impact_failure_code,
                            impact_run.analyzed_at,
                            rd.id AS resource_draft_id, rd.pid AS resource_draft_pid,
                            rd.revision AS resource_revision, rd.snapshot::text,
                            rd.ownership_scope, rd.source_ownership_scope,
                            rd.source_resource_pid, rd.override_pid,
                            wl.id AS lease_id, wl.session_id AS lease_session_id,
                            wl.holder_user_id AS lease_holder_user_id,
                            wl.lease_revision, wl.leased_until
                        FROM ab_authoring_config_session s
                        JOIN ab_authoring_change_set cs ON cs.id = s.change_set_id
                        JOIN ab_authoring_resource_draft rd ON rd.change_set_id = cs.id
                            AND rd.resource_type = 'PAGE_SCHEMA' AND rd.resource_pid = s.page_pid
                        JOIN ab_authoring_writer_lease wl ON wl.change_set_id = cs.id
                        LEFT JOIN LATERAL (
                            SELECT vr.pid AS validation_run_pid,
                                   vr.change_set_revision AS validation_revision,
                                   vr.status AS validation_status,
                                   vr.error_count AS validation_error_count,
                                   vr.issues::text AS validation_issues,
                                   vr.created_at AS validated_at
                            FROM ab_authoring_validation_run vr
                            WHERE vr.tenant_id = cs.tenant_id
                              AND vr.env_id = cs.env_id
                              AND vr.change_set_id = cs.id
                              AND vr.change_set_revision = cs.revision
                            ORDER BY vr.created_at DESC, vr.id DESC
                            LIMIT 1
                        ) validation_run ON TRUE
                        LEFT JOIN LATERAL (
                            SELECT ir.pid AS impact_run_pid,
                                   ir.change_set_revision AS impact_revision,
                                   ir.status AS impact_status,
                                   ir.dependency_checksum AS impact_dependency_checksum,
                                   ir.dependencies::text AS impact_dependencies,
                                   ir.failure_code AS impact_failure_code,
                                   ir.created_at AS analyzed_at
                            FROM ab_authoring_impact_run ir
                            WHERE ir.tenant_id = cs.tenant_id
                              AND ir.env_id = cs.env_id
                              AND ir.change_set_id = cs.id
                              AND ir.change_set_revision = cs.revision
                            ORDER BY ir.created_at DESC, ir.id DESC
                            LIMIT 1
                        ) impact_run ON TRUE
                        WHERE s.tenant_id = ? AND s.env_id = ? AND s.pid = ?
                          AND cs.tenant_id = ? AND cs.env_id = ? AND cs.deleted_flag = FALSE
                          AND rd.tenant_id = ? AND rd.env_id = ?
                          AND wl.tenant_id = ? AND wl.env_id = ?
                        """ + lockClause,
                resultSet -> resultSet.next() ? mapWorkspace(resultSet) : null,
                tenantId, envId, sessionPid,
                tenantId, envId,
                tenantId, envId,
                tenantId, envId);
    }

    public String createObserverSession(CreateObserverSession command) {
        List<ObservationTarget> targets = jdbcTemplate.query("""
                        SELECT cs.id AS change_set_id, cs.revision,
                               rd.resource_pid AS page_pid
                        FROM ab_authoring_change_set cs
                        JOIN ab_authoring_resource_draft rd ON rd.change_set_id = cs.id
                          AND rd.resource_type = 'PAGE_SCHEMA'
                        JOIN ab_authoring_writer_lease wl ON wl.change_set_id = cs.id
                        WHERE cs.tenant_id = ? AND cs.env_id = ? AND cs.pid = ?
                          AND cs.deleted_flag = FALSE
                          AND rd.tenant_id = ? AND rd.env_id = ?
                          AND wl.tenant_id = ? AND wl.env_id = ?
                        FOR UPDATE OF cs, rd, wl
                        """,
                (resultSet, rowNum) -> new ObservationTarget(
                        resultSet.getLong("change_set_id"),
                        resultSet.getLong("revision"),
                        resultSet.getString("page_pid")),
                command.tenantId(), command.envId(), command.changeSetPid(),
                command.tenantId(), command.envId(),
                command.tenantId(), command.envId());
        if (targets.size() != 1) {
            return null;
        }
        ObservationTarget target = targets.get(0);
        jdbcTemplate.update("""
                INSERT INTO ab_authoring_config_session (
                    pid, tenant_id, env_id, actor_user_id, change_set_id, page_pid, state,
                    workspace_mode, interaction_context, revision, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, 'READ_ONLY', ?, ?::jsonb, ?, ?)
                """,
                command.sessionPid(), command.tenantId(), command.envId(), command.actorUserId(),
                target.changeSetId(), target.pagePid(), command.workspaceMode(),
                json(command.interactionContext()),
                target.revision(), Timestamp.from(command.expiresAt()));
        return command.sessionPid();
    }

    public void takeoverWriterLease(
            WorkspaceRow workspace,
            long actorUserId,
            Instant sessionExpiresAt,
            Instant leaseUntil) {
        jdbcTemplate.update("""
                UPDATE ab_authoring_config_session
                SET state = 'READ_ONLY', updated_at = CURRENT_TIMESTAMP
                WHERE change_set_id = ? AND tenant_id = ? AND env_id = ?
                  AND state = 'ACTIVE'
                """,
                workspace.changeSetId(), workspace.tenantId(), workspace.envId());
        int sessionUpdated = jdbcTemplate.update("""
                UPDATE ab_authoring_config_session
                SET state = 'ACTIVE', revision = ?, expires_at = ?,
                    last_seen_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND env_id = ? AND actor_user_id = ?
                """,
                workspace.changeSetRevision(), Timestamp.from(sessionExpiresAt),
                workspace.sessionId(), workspace.tenantId(), workspace.envId(), actorUserId);
        requireOne(sessionUpdated, "authoring.session.actor-mismatch");

        int leaseUpdated = jdbcTemplate.update("""
                UPDATE ab_authoring_writer_lease
                SET session_id = ?, holder_user_id = ?,
                    lease_revision = lease_revision + 1,
                    acquired_at = CURRENT_TIMESTAMP, leased_until = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND env_id = ? AND lease_revision = ?
                """,
                workspace.sessionId(), actorUserId, Timestamp.from(leaseUntil),
                workspace.leaseId(), workspace.tenantId(), workspace.envId(),
                workspace.leaseRevision());
        requireOne(leaseUpdated, "authoring.writer-lease.conflict");
    }

    public void persistPatch(
            WorkspaceRow workspace,
            JsonNode snapshot,
            String registryChecksum,
            String changeItemPid,
            String blockId,
            String propertyPath,
            String operation,
            JsonNode previousValue,
            JsonNode savedValue,
            PropertyCapability capability,
            BoundaryDecision decision,
            long actorUserId,
            AggregatePolicy aggregate,
            Instant leaseUntil) {
        long expectedRevision = workspace.changeSetRevision();
        int resourceUpdated = jdbcTemplate.update("""
                UPDATE ab_authoring_resource_draft
                SET snapshot = ?::jsonb,
                    revision = revision + 1,
                    manifest_checksum = ?,
                    validation_state = 'UNVALIDATED',
                    impact_state = 'UNKNOWN',
                    stale_reason = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND env_id = ? AND revision = ?
                """,
                json(snapshot), registryChecksum, workspace.resourceDraftId(),
                workspace.tenantId(), workspace.envId(), expectedRevision);
        requireOne(resourceUpdated, "authoring.revision.conflict");

        int changeSetUpdated = jdbcTemplate.update("""
                UPDATE ab_authoring_change_set
                SET revision = revision + 1,
                    manifest_checksum = ?,
                    risk_level = ?,
                    route = ?,
                    publish_policy = ?,
                    validation_state = 'UNVALIDATED',
                    impact_state = 'UNKNOWN',
                    approval_state = ?,
                    publish_state = 'DRAFT',
                    stale_reason = NULL,
                    status = 'DRAFT',
                    approved_at = NULL,
                    published_at = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND env_id = ? AND revision = ?
                  AND status IN ('DRAFT', 'REJECTED')
                """,
                registryChecksum, aggregate.riskLevel(), aggregate.route(),
                aggregate.publishPolicy(), aggregate.approvalState(), workspace.changeSetId(),
                workspace.tenantId(), workspace.envId(), expectedRevision);
        requireOne(changeSetUpdated, "authoring.revision.conflict");

        int sessionUpdated = jdbcTemplate.update("""
                UPDATE ab_authoring_config_session
                SET revision = revision + 1,
                    last_seen_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND env_id = ? AND revision = ?
                  AND state = 'ACTIVE' AND expires_at > CURRENT_TIMESTAMP
                """,
                workspace.sessionId(), workspace.tenantId(), workspace.envId(), expectedRevision);
        requireOne(sessionUpdated, "authoring.session.stale");

        int leaseUpdated = jdbcTemplate.update("""
                UPDATE ab_authoring_writer_lease
                SET leased_until = ?, lease_revision = lease_revision + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND env_id = ?
                  AND holder_user_id = ? AND leased_until > CURRENT_TIMESTAMP
                """,
                Timestamp.from(leaseUntil), workspace.leaseId(), workspace.tenantId(), workspace.envId(),
                actorUserId);
        requireOne(leaseUpdated, "authoring.writer-lease.lost");

        jdbcTemplate.update("""
                INSERT INTO ab_authoring_change_item (
                    pid, tenant_id, env_id, change_set_id, resource_draft_id, block_id,
                    property_path, operation, old_value, new_value, effect_tags,
                    risk_level, route, publish_policy, reversibility, manifest_checksum,
                    ownership_scope, source_resource_pid, override_pid,
                    base_revision, result_revision, actor_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                changeItemPid, workspace.tenantId(), workspace.envId(), workspace.changeSetId(),
                workspace.resourceDraftId(), blockId, propertyPath, operation,
                nullableJson(previousValue), nullableJson(savedValue), json(capability.effectTags()),
                decision.risk().name(), decision.route().name(), decision.publishPolicy().name(),
                capability.reversibility().name(), decision.manifestChecksum(),
                workspace.ownershipScope(), workspace.sourceResourcePid(), workspace.overridePid(),
                expectedRevision,
                expectedRevision + 1, actorUserId);
    }

    public void audit(AuditEntry audit) {
        jdbcTemplate.update("""
                INSERT INTO ab_authoring_audit_event (
                    pid, tenant_id, env_id, actor_user_id, change_set_pid, session_pid,
                    event_type, result, reason_code, resource_type, resource_pid,
                    block_id, property_path, trace_id, metadata)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
                audit.pid(), audit.tenantId(), audit.envId(), audit.actorUserId(),
                audit.changeSetPid(), audit.sessionPid(), audit.eventType(), audit.result(),
                audit.reasonCode(), audit.resourceType(), audit.resourcePid(), audit.blockId(),
                audit.propertyPath(), audit.traceId(), json(audit.metadata()));
    }

    public void createHandoff(CreateHandoff command) {
        jdbcTemplate.update("""
                INSERT INTO ab_authoring_handoff_context (
                    pid, tenant_id, env_id, actor_user_id, change_set_id, nonce_hash,
                    target_route, context_payload, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """,
                command.pid(), command.tenantId(), command.envId(), command.actorUserId(),
                command.changeSetId(), command.nonceHash(), command.targetRoute(),
                json(command.contextPayload()), Timestamp.from(command.expiresAt()));
    }

    public HandoffRow findHandoff(
            long tenantId,
            long envId,
            long actorUserId,
            String nonceHash,
            boolean lock) {
        String lockClause = lock ? " FOR UPDATE" : "";
        List<HandoffRow> rows = jdbcTemplate.query("""
                        SELECT h.id, h.pid, h.tenant_id, h.env_id, h.actor_user_id,
                               h.change_set_id, cs.pid AS change_set_pid, h.target_route,
                               h.context_payload::text, h.expires_at, h.consumed_at
                        FROM ab_authoring_handoff_context h
                        JOIN ab_authoring_change_set cs ON cs.id = h.change_set_id
                          AND cs.tenant_id = h.tenant_id AND cs.env_id = h.env_id
                        WHERE h.tenant_id = ? AND h.env_id = ? AND h.actor_user_id = ?
                          AND h.nonce_hash = ?
                        """ + lockClause,
                (resultSet, rowNum) -> new HandoffRow(
                        resultSet.getLong("id"),
                        resultSet.getString("pid"),
                        resultSet.getLong("tenant_id"),
                        resultSet.getLong("env_id"),
                        resultSet.getLong("actor_user_id"),
                        resultSet.getLong("change_set_id"),
                        resultSet.getString("change_set_pid"),
                        resultSet.getString("target_route"),
                        parse(resultSet.getString("context_payload")),
                        resultSet.getTimestamp("expires_at").toInstant(),
                        resultSet.getTimestamp("consumed_at") == null
                                ? null
                                : resultSet.getTimestamp("consumed_at").toInstant()),
                tenantId, envId, actorUserId, nonceHash);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean consumeHandoff(HandoffRow handoff) {
        return jdbcTemplate.update("""
                UPDATE ab_authoring_handoff_context
                SET consumed_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND env_id = ? AND actor_user_id = ?
                  AND consumed_at IS NULL AND expires_at > CURRENT_TIMESTAMP
                """,
                handoff.id(), handoff.tenantId(), handoff.envId(), handoff.actorUserId()) == 1;
    }

    private WorkspaceRow mapWorkspace(ResultSet resultSet) throws SQLException {
        return new WorkspaceRow(
                resultSet.getLong("session_id"),
                resultSet.getString("session_pid"),
                resultSet.getLong("tenant_id"),
                resultSet.getLong("env_id"),
                resultSet.getLong("actor_user_id"),
                resultSet.getString("page_pid"),
                resultSet.getString("session_state"),
                resultSet.getString("workspace_mode"),
                parse(resultSet.getString("interaction_context")),
                resultSet.getTimestamp("expires_at").toInstant(),
                resultSet.getLong("session_revision"),
                resultSet.getLong("change_set_id"),
                resultSet.getString("change_set_pid"),
                resultSet.getLong("change_set_owner_user_id"),
                resultSet.getString("change_set_status"),
                resultSet.getString("change_set_origin"),
                resultSet.getLong("change_set_revision"),
                resultSet.getString("risk_level"),
                resultSet.getString("route"),
                resultSet.getString("publish_policy"),
                resultSet.getString("validation_state"),
                mapValidationSummary(resultSet),
                resultSet.getString("impact_state"),
                mapImpactSummary(resultSet),
                resultSet.getString("approval_state"),
                resultSet.getString("publish_state"),
                resultSet.getString("manifest_checksum"),
                resultSet.getLong("resource_draft_id"),
                resultSet.getString("resource_draft_pid"),
                resultSet.getLong("resource_revision"),
                parse(resultSet.getString("snapshot")),
                resultSet.getString("ownership_scope"),
                resultSet.getString("source_ownership_scope"),
                resultSet.getString("source_resource_pid"),
                resultSet.getString("override_pid"),
                resultSet.getLong("lease_id"),
                resultSet.getLong("lease_session_id"),
                resultSet.getLong("lease_holder_user_id"),
                resultSet.getLong("lease_revision"),
                resultSet.getTimestamp("leased_until").toInstant());
    }

    private ValidationRunSummary mapValidationSummary(ResultSet resultSet) throws SQLException {
        String status = resultSet.getString("validation_status");
        if (status == null) {
            return null;
        }
        return new ValidationRunSummary(
                resultSet.getString("validation_run_pid"),
                resultSet.getLong("validation_revision"),
                status,
                resultSet.getInt("validation_error_count"),
                parse(resultSet.getString("validation_issues")),
                resultSet.getTimestamp("validated_at").toInstant());
    }

    private ImpactRunSummary mapImpactSummary(ResultSet resultSet) throws SQLException {
        String status = resultSet.getString("impact_status");
        if (status == null) {
            return null;
        }
        return new ImpactRunSummary(
                resultSet.getString("impact_run_pid"),
                resultSet.getLong("impact_revision"),
                status,
                resultSet.getString("impact_dependency_checksum"),
                parse(resultSet.getString("impact_dependencies")),
                resultSet.getString("impact_failure_code"),
                resultSet.getTimestamp("analyzed_at").toInstant());
    }

    private void requireOne(int rows, String reason) {
        if (rows != 1) {
            throw new ResponseStatusException(CONFLICT, reason);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("authoring.json.serialize-failed", e);
        }
    }

    private String nullableJson(JsonNode value) {
        return value == null ? null : json(value);
    }

    private JsonNode parse(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException e) {
            throw new DataRetrievalFailureException("Invalid authoring JSON", e);
        }
    }

    public record CreateWorkspace(
            long tenantId,
            long envId,
            long actorUserId,
            String sessionPid,
            String changeSetPid,
            String resourceDraftPid,
            String leasePid,
            String pagePid,
            String title,
            String origin,
            String ownershipScope,
            String sourceOwnershipScope,
            String sourceResourcePid,
            String overridePid,
            String baseReleasePid,
            long baseVersion,
            String baseChecksum,
            String registryChecksum,
            JsonNode snapshot,
            JsonNode interactionContext,
            Instant expiresAt,
            Instant leaseUntil) {
    }

    public record CreatedWorkspace(long changeSetId, long resourceDraftId, long sessionId) {
    }

    public record CreateObserverSession(
            long tenantId,
            long envId,
            long actorUserId,
            String changeSetPid,
            String sessionPid,
            String workspaceMode,
            JsonNode interactionContext,
            Instant expiresAt) {
    }

    private record ObservationTarget(long changeSetId, long revision, String pagePid) {
    }

    public record CreateHandoff(
            String pid,
            long tenantId,
            long envId,
            long actorUserId,
            long changeSetId,
            String nonceHash,
            String targetRoute,
            JsonNode contextPayload,
            Instant expiresAt) {
    }

    public record HandoffRow(
            long id,
            String pid,
            long tenantId,
            long envId,
            long actorUserId,
            long changeSetId,
            String changeSetPid,
            String targetRoute,
            JsonNode contextPayload,
            Instant expiresAt,
            Instant consumedAt) {
    }

    public record WorkspaceRow(
            long sessionId,
            String sessionPid,
            long tenantId,
            long envId,
            long actorUserId,
            String pagePid,
            String sessionState,
            String workspaceMode,
            JsonNode interactionContext,
            Instant expiresAt,
            long sessionRevision,
            long changeSetId,
            String changeSetPid,
            long changeSetOwnerUserId,
            String changeSetStatus,
            String changeSetOrigin,
            long changeSetRevision,
            String riskLevel,
            String route,
            String publishPolicy,
            String validationState,
            ValidationRunSummary validation,
            String impactState,
            ImpactRunSummary impact,
            String approvalState,
            String publishState,
            String manifestChecksum,
            long resourceDraftId,
            String resourceDraftPid,
            long resourceRevision,
            JsonNode snapshot,
            String ownershipScope,
            String sourceOwnershipScope,
            String sourceResourcePid,
            String overridePid,
            long leaseId,
            long leaseSessionId,
            long leaseHolderUserId,
            long leaseRevision,
            Instant leasedUntil) {
    }

    public record ValidationRunSummary(
            String validationRunPid,
            long revision,
            String status,
            int errorCount,
            JsonNode issues,
            Instant validatedAt) {
    }

    public record ImpactRunSummary(
            String impactRunPid,
            long revision,
            String status,
            String dependencyChecksum,
            JsonNode dependencies,
            String failureCode,
            Instant analyzedAt) {
    }

    public record AggregatePolicy(
            String riskLevel,
            String route,
            String publishPolicy,
            String approvalState) {
    }

    public record AuditEntry(
            String pid,
            long tenantId,
            long envId,
            Long actorUserId,
            String changeSetPid,
            String sessionPid,
            String eventType,
            String result,
            String reasonCode,
            String resourceType,
            String resourcePid,
            String blockId,
            String propertyPath,
            String traceId,
            JsonNode metadata) {
    }
}
