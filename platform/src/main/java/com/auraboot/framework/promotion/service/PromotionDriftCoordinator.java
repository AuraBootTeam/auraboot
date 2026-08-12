package com.auraboot.framework.promotion.service;

import com.auraboot.framework.authoring.workspace.AuthoringPageSnapshotFactory;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.promotion.dao.entity.Promotion;
import com.auraboot.framework.promotion.dao.entity.PromotionUnit;
import com.auraboot.framework.promotion.dao.mapper.PromotionUnitMapper;
import com.auraboot.framework.promotion.dto.DryRunResult.Drift;
import com.auraboot.framework.promotion.dto.PromotionDriftDecisionRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.springframework.http.HttpStatus.CONFLICT;

/** Detects and records the explicit fate of target-local releases met by a promotion. */
@Service
public class PromotionDriftCoordinator {

    private static final List<String> OPTIONS =
            List.of("REBASE", "BACKPORT", "KEEP_OVERRIDE", "OVERWRITE");

    private final JdbcTemplate jdbcTemplate;
    private final PromotionUnitMapper promotionUnitMapper;
    private final AuthoringPageSnapshotFactory snapshotFactory;
    private final ObjectMapper objectMapper;
    private final PromotionThreeWayMergeService threeWayMergeService;

    public PromotionDriftCoordinator(
            JdbcTemplate jdbcTemplate,
            PromotionUnitMapper promotionUnitMapper,
            AuthoringPageSnapshotFactory snapshotFactory,
            ObjectMapper objectMapper,
            PromotionThreeWayMergeService threeWayMergeService) {
        this.jdbcTemplate = jdbcTemplate;
        this.promotionUnitMapper = promotionUnitMapper;
        this.snapshotFactory = snapshotFactory;
        this.objectMapper = objectMapper;
        this.threeWayMergeService = threeWayMergeService;
    }

    public Optional<Assessment> assess(
            Promotion promotion,
            PromotionUnit unit,
            PageSchema source,
            PageSchema target,
            Long actorUserId) {
        if (target == null) {
            clear(unit, null);
            return Optional.empty();
        }
        ActiveTargetRelease active = findActiveRelease(
                promotion.getTenantId(), promotion.getTargetEnvId(), target.getPid());
        if (active == null) {
            clear(unit, target.getPid());
            return Optional.empty();
        }

        String kind = active.overridePid() == null
                ? "PRODUCTION_CONTEXTUAL_HOTFIX"
                : "TENANT_OVERRIDE";
        ObjectNode evidence = evidence(source, target, active);
        String fingerprint = snapshotFactory.checksum(evidence);
        boolean fingerprintChanged = !fingerprint.equals(unit.getDriftFingerprint());
        if (fingerprintChanged) {
            if (unit.getDriftFingerprint() != null) {
                recordEvent(
                        promotion, unit, "STALE", kind, unit.getDriftFingerprint(),
                        unit.getDriftDecision(), "PROMOTION_DRIFT_FINGERPRINT_CHANGED",
                        null, actorUserId, staleEvidence(fingerprint));
            }
            unit.setTargetResourcePid(target.getPid());
            unit.setDriftStatus("PENDING");
            unit.setDriftFingerprint(fingerprint);
            unit.setDriftDecision(null);
            unit.setDriftDecisionPid(null);
            unit.setDriftExecutionStatus("NONE");
            unit.setDriftExecutionPid(null);
            unit.setDriftExecutionPayload(null);
            jdbcTemplate.update("""
                    UPDATE ab_promotion_unit
                    SET target_resource_pid = ?, drift_status = 'PENDING',
                        drift_fingerprint = ?, drift_decision = NULL,
                        drift_decision_pid = NULL, drift_execution_status = 'NONE',
                        drift_execution_pid = NULL, drift_execution_payload = NULL
                    WHERE id = ? AND tenant_id = ? AND deleted_flag = FALSE
                    """, target.getPid(), fingerprint, unit.getId(), unit.getTenantId());
            recordEvent(
                    promotion, unit, "DETECTED", kind, fingerprint, null,
                    "PROMOTION_TARGET_LOCAL_RELEASE_DETECTED", null, actorUserId, evidence);
        } else if (!target.getPid().equals(unit.getTargetResourcePid())) {
            unit.setTargetResourcePid(target.getPid());
            promotionUnitMapper.updateById(unit);
        }
        return Optional.of(assessment(unit, source, target, active, kind, evidence));
    }

    public Assessment resolve(
            Promotion promotion,
            PromotionUnit unit,
            PageSchema source,
            PageSchema target,
            PromotionDriftDecisionRequest request,
            long actorUserId) {
        lockAndRefresh(unit);
        Assessment current = assess(promotion, unit, source, target, actorUserId)
                .orElseThrow(() -> conflict("promotion.drift.no-active-target-release"));
        if (!current.fingerprint().equals(request.getExpectedFingerprint())) {
            throw conflict("promotion.drift.fingerprint-stale");
        }
        if (!OPTIONS.contains(request.getDecision())) {
            throw conflict("promotion.drift.decision-unsupported");
        }
        if (request.getReason() == null || request.getReason().isBlank()) {
            throw conflict("promotion.drift.reason-required");
        }
        if ("BACKPORTED".equals(unit.getDriftExecutionStatus())
                && !request.getDecision().equals(unit.getDriftDecision())) {
            throw conflict("promotion.drift.backport-already-created");
        }
        if ("RESOLVED".equals(unit.getDriftStatus())
                && request.getDecision().equals(unit.getDriftDecision())
                && unit.getDriftExecutionStatus() != null
                && !"NONE".equals(unit.getDriftExecutionStatus())) {
            return current;
        }
        String decisionPid = UniqueIdGenerator.generate();
        recordEvent(
                promotion, unit, decisionPid, "DECIDED", current.kind(), current.fingerprint(),
                request.getDecision(), "PROMOTION_DRIFT_DECISION_RECORDED",
                request.getReason().trim(), actorUserId, current.evidence());
        unit.setDriftStatus("RESOLVED");
        unit.setDriftDecision(request.getDecision());
        unit.setDriftDecisionPid(decisionPid);
        executeDecision(promotion, unit, source, target, current, actorUserId, request.getReason());
        promotionUnitMapper.updateById(unit);
        return assessment(unit, source, target, current.active(), current.kind(), current.evidence());
    }

    public Optional<Assessment> requireApplyReady(
            Promotion promotion,
            PromotionUnit unit,
            PageSchema source,
            PageSchema target,
            long actorUserId) {
        Optional<Assessment> assessment = assess(
                promotion, unit, source, target, actorUserId);
        if (assessment.isPresent() && !assessment.get().applyReady()) {
            throw conflict("promotion.drift.decision-not-apply-ready");
        }
        return assessment;
    }

    public void applyDecision(
            Promotion promotion,
            PromotionUnit unit,
            Assessment assessment,
            long actorUserId,
            String applyReason) {
        if (!assessment.applyReady()
                || !assessment.fingerprint().equals(unit.getDriftFingerprint())) {
            throw conflict("promotion.drift.fingerprint-stale");
        }
        if (!"KEEP_OVERRIDE".equals(unit.getDriftDecision())) {
            supersedeActiveRelease(promotion, assessment);
        }
        unit.setDriftStatus("APPLIED");
        unit.setDriftExecutionStatus("APPLIED");
        promotionUnitMapper.updateById(unit);
        recordEvent(
                promotion, unit, "APPLIED", assessment.kind(), assessment.fingerprint(),
                unit.getDriftDecision(), "PROMOTION_DRIFT_DECISION_APPLIED",
                normalizedOptionalReason(applyReason), actorUserId, assessment.evidence());
    }

    public ObjectNode sourceForApply(PromotionUnit unit, ObjectNode fallback) {
        if (!"REBASE".equals(unit.getDriftDecision())) {
            return fallback;
        }
        if (!"PREPARED".equals(unit.getDriftExecutionStatus())
                || unit.getDriftExecutionPayload() == null) {
            throw conflict("promotion.drift.rebase-not-prepared");
        }
        try {
            return (ObjectNode) objectMapper.readTree(unit.getDriftExecutionPayload());
        } catch (JsonProcessingException | ClassCastException failure) {
            throw conflict("promotion.drift.rebase-payload-invalid");
        }
    }

    private Assessment assessment(
            PromotionUnit unit,
            PageSchema source,
            PageSchema target,
            ActiveTargetRelease active,
            String kind,
            ObjectNode evidence) {
        boolean applyReady = "RESOLVED".equals(unit.getDriftStatus())
                && ("REBASE".equals(unit.getDriftDecision())
                    || "OVERWRITE".equals(unit.getDriftDecision()))
                && "PREPARED".equals(unit.getDriftExecutionStatus());
        return new Assessment(
                unit.getPid(), source.getPid(), target.getPid(), source.getPageKey(), kind,
                unit.getDriftStatus(), unit.getDriftFingerprint(), unit.getDriftDecision(),
                applyReady, nextAction(unit.getDriftStatus(), unit.getDriftDecision()),
                source.getVersion(), target.getVersion(), active, evidence.deepCopy());
    }

    public Drift toView(Assessment assessment) {
        Drift drift = new Drift();
        drift.setUnitPid(assessment.unitPid());
        drift.setResourceType("PAGE_SCHEMA");
        drift.setResourcePid(assessment.sourceResourcePid());
        drift.setTargetResourcePid(assessment.targetResourcePid());
        drift.setPageKey(assessment.pageKey());
        drift.setKind(assessment.kind());
        drift.setStatus(assessment.status());
        drift.setFingerprint(assessment.fingerprint());
        drift.setDecision(assessment.decision());
        PromotionUnit unit = promotionUnitMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<PromotionUnit>()
                        .eq("pid", assessment.unitPid())
                        .eq("deleted_flag", false));
        drift.setExecutionStatus(unit == null ? null : unit.getDriftExecutionStatus());
        drift.setExecutionPid(unit == null ? null : unit.getDriftExecutionPid());
        drift.setApplyReady(assessment.applyReady());
        drift.setNextAction(assessment.nextAction());
        drift.setActiveReleasePid(assessment.active().releasePid());
        drift.setChannelVersion(assessment.active().channelVersion());
        drift.setOverridePid(assessment.active().overridePid());
        drift.setSourceVersion(assessment.sourceVersion());
        drift.setTargetVersion(assessment.targetVersion());
        drift.setOptions(OPTIONS);
        return drift;
    }

    private void executeDecision(
            Promotion promotion,
            PromotionUnit unit,
            PageSchema source,
            PageSchema target,
            Assessment assessment,
            long actorUserId,
            String reason) {
        switch (unit.getDriftDecision()) {
            case "REBASE" -> prepareRebase(
                    promotion, unit, source, target, assessment, actorUserId, reason);
            case "BACKPORT" -> createBackport(
                    promotion, unit, target, assessment, actorUserId, reason);
            case "KEEP_OVERRIDE" -> prepare(
                    promotion, unit, assessment, actorUserId, reason,
                    "DEFERRED", "PROMOTION_DRIFT_OVERRIDE_PRESERVED");
            case "OVERWRITE" -> prepare(
                    promotion, unit, assessment, actorUserId, reason,
                    "PREPARED", "PROMOTION_DRIFT_OVERWRITE_PREPARED");
            default -> throw conflict("promotion.drift.decision-unsupported");
        }
    }

    private void lockAndRefresh(PromotionUnit unit) {
        jdbcTemplate.queryForObject("""
                SELECT id FROM ab_promotion_unit
                WHERE id = ? AND tenant_id = ? AND deleted_flag = FALSE
                FOR UPDATE
                """, Long.class, unit.getId(), unit.getTenantId());
        jdbcTemplate.queryForObject("""
                SELECT drift_status, drift_fingerprint, drift_decision,
                       drift_decision_pid, drift_execution_status,
                       drift_execution_pid, drift_execution_payload::text
                FROM ab_promotion_unit WHERE id = ?
                """, (resultSet, rowNum) -> {
                    unit.setDriftStatus(resultSet.getString("drift_status"));
                    unit.setDriftFingerprint(resultSet.getString("drift_fingerprint"));
                    unit.setDriftDecision(resultSet.getString("drift_decision"));
                    unit.setDriftDecisionPid(resultSet.getString("drift_decision_pid"));
                    unit.setDriftExecutionStatus(resultSet.getString("drift_execution_status"));
                    unit.setDriftExecutionPid(resultSet.getString("drift_execution_pid"));
                    unit.setDriftExecutionPayload(resultSet.getString("drift_execution_payload"));
                    return unit;
                }, unit.getId());
    }

    private void prepareRebase(
            Promotion promotion,
            PromotionUnit unit,
            PageSchema source,
            PageSchema target,
            Assessment assessment,
            long actorUserId,
            String reason) {
        ObjectNode merged = threeWayMergeService.merge(
                snapshotFactory.create(target),
                snapshotFactory.create(source),
                readActiveReleaseSnapshot(promotion, assessment));
        merged.put("pid", target.getPid());
        merged.put("pageKey", target.getPageKey());
        merged.put("modelCode", target.getModelCode());
        unit.setDriftExecutionStatus("PREPARED");
        unit.setDriftExecutionPid(UniqueIdGenerator.generate());
        unit.setDriftExecutionPayload(json(merged));
        recordExecuted(promotion, unit, assessment, actorUserId, reason,
                "PROMOTION_DRIFT_REBASE_PREPARED", executionEvidence(unit));
    }

    private void createBackport(
            Promotion promotion,
            PromotionUnit unit,
            PageSchema target,
            Assessment assessment,
            long actorUserId,
            String reason) {
        String reversePid = UniqueIdGenerator.generate();
        Long reverseId = jdbcTemplate.queryForObject("""
                INSERT INTO ab_promotion (
                    pid, tenant_id, source_env_id, target_env_id, status,
                    plan_summary, parent_promotion_pid, origin_drift_decision_pid,
                    created_at, created_by, updated_at, updated_by, deleted_flag)
                VALUES (?, ?, ?, ?, 'DRAFT', ?::jsonb, ?, ?, CURRENT_TIMESTAMP, ?,
                        CURRENT_TIMESTAMP, ?, FALSE)
                RETURNING id
                """, Long.class, reversePid, promotion.getTenantId(),
                promotion.getTargetEnvId(), promotion.getSourceEnvId(),
                "{\"unitCount\":1,\"resourceTypes\":[\"PAGE_SCHEMA\"]}",
                promotion.getPid(), unit.getDriftDecisionPid(), actorUserId, actorUserId);
        jdbcTemplate.update("""
                INSERT INTO ab_promotion_unit (
                    pid, tenant_id, promotion_id, resource_type, resource_pid,
                    source_version, sort_order, created_at, deleted_flag)
                VALUES (?, ?, ?, 'PAGE_SCHEMA', ?, ?, 0, CURRENT_TIMESTAMP, FALSE)
                """, UniqueIdGenerator.generate(), promotion.getTenantId(), reverseId,
                target.getPid(), target.getVersion());
        unit.setDriftExecutionStatus("BACKPORTED");
        unit.setDriftExecutionPid(reversePid);
        unit.setDriftExecutionPayload(json(executionEvidence(unit)));
        recordExecuted(promotion, unit, assessment, actorUserId, reason,
                "PROMOTION_DRIFT_BACKPORT_CREATED", executionEvidence(unit));
    }

    private void prepare(
            Promotion promotion,
            PromotionUnit unit,
            Assessment assessment,
            long actorUserId,
            String reason,
            String status,
            String reasonCode) {
        unit.setDriftExecutionStatus(status);
        unit.setDriftExecutionPid(UniqueIdGenerator.generate());
        unit.setDriftExecutionPayload(json(executionEvidence(unit)));
        recordExecuted(promotion, unit, assessment, actorUserId, reason, reasonCode,
                executionEvidence(unit));
    }

    private ObjectNode readActiveReleaseSnapshot(
            Promotion promotion,
            Assessment assessment) {
        String serialized = jdbcTemplate.queryForObject("""
                SELECT item.snapshot::text
                FROM ab_authoring_release release
                JOIN ab_authoring_release_item item
                  ON item.release_id = release.id
                 AND item.tenant_id = release.tenant_id
                 AND item.env_id = release.env_id
                WHERE release.tenant_id = ? AND release.env_id = ? AND release.pid = ?
                  AND item.resource_type = 'PAGE_SCHEMA' AND item.resource_pid = ?
                """, String.class, promotion.getTenantId(), promotion.getTargetEnvId(),
                assessment.active().releasePid(), assessment.targetResourcePid());
        try {
            return (ObjectNode) objectMapper.readTree(serialized);
        } catch (JsonProcessingException | ClassCastException failure) {
            throw conflict("promotion.drift.active-release-snapshot-invalid");
        }
    }

    private void supersedeActiveRelease(
            Promotion promotion,
            Assessment assessment) {
        int releaseUpdated = jdbcTemplate.update("""
                UPDATE ab_authoring_release SET status = 'SUPERSEDED'
                WHERE tenant_id = ? AND env_id = ? AND pid = ? AND status = 'ACTIVE'
                """, promotion.getTenantId(), promotion.getTargetEnvId(),
                assessment.active().releasePid());
        if (releaseUpdated != 1) {
            throw conflict("promotion.drift.active-release-changed");
        }
        if (assessment.active().overridePid() != null) {
            int overrideUpdated = jdbcTemplate.update("""
                    UPDATE ab_authoring_tenant_override
                    SET status = 'SUPERSEDED', row_version = row_version + 1,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE tenant_id = ? AND env_id = ? AND pid = ? AND status = 'ACTIVE'
                    """, promotion.getTenantId(), promotion.getTargetEnvId(),
                    assessment.active().overridePid());
            if (overrideUpdated != 1) {
                throw conflict("promotion.drift.override-changed");
            }
        }
    }

    private ObjectNode executionEvidence(PromotionUnit unit) {
        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("executionStatus", unit.getDriftExecutionStatus());
        evidence.put("executionPid", unit.getDriftExecutionPid());
        return evidence;
    }

    private void recordExecuted(
            Promotion promotion,
            PromotionUnit unit,
            Assessment assessment,
            long actorUserId,
            String reason,
            String reasonCode,
            ObjectNode evidence) {
        recordEvent(promotion, unit, "EXECUTED", assessment.kind(),
                assessment.fingerprint(), unit.getDriftDecision(), reasonCode,
                reason.trim(), actorUserId, evidence);
    }

    private ActiveTargetRelease findActiveRelease(long tenantId, long envId, String targetPid) {
        return jdbcTemplate.query("""
                        SELECT release.pid AS release_pid, channel.row_version,
                               item.snapshot_checksum, item.override_pid,
                               change_set.origin
                        FROM ab_authoring_release_channel channel
                        JOIN ab_authoring_release release
                          ON release.id = channel.active_release_id
                         AND release.tenant_id = channel.tenant_id
                         AND release.env_id = channel.env_id
                         AND release.status = 'ACTIVE'
                        JOIN ab_authoring_release_item item
                          ON item.release_id = release.id
                         AND item.tenant_id = channel.tenant_id
                         AND item.env_id = channel.env_id
                         AND item.resource_type = channel.resource_type
                         AND item.resource_pid = channel.resource_pid
                        JOIN ab_authoring_change_set change_set
                          ON change_set.id = release.change_set_id
                         AND change_set.tenant_id = release.tenant_id
                         AND change_set.env_id = release.env_id
                        WHERE channel.tenant_id = ? AND channel.env_id = ?
                          AND channel.resource_type = 'PAGE_SCHEMA'
                          AND channel.resource_pid = ?
                        """,
                resultSet -> resultSet.next() ? mapActive(resultSet) : null,
                tenantId, envId, targetPid);
    }

    private ActiveTargetRelease mapActive(ResultSet resultSet) throws SQLException {
        return new ActiveTargetRelease(
                resultSet.getString("release_pid"),
                resultSet.getLong("row_version"),
                resultSet.getString("snapshot_checksum"),
                resultSet.getString("override_pid"),
                resultSet.getString("origin"));
    }

    private ObjectNode evidence(
            PageSchema source,
            PageSchema target,
            ActiveTargetRelease active) {
        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("incomingSourcePid", source.getPid());
        evidence.put("incomingSourceVersion", source.getVersion());
        evidence.put("incomingSourceChecksum", checksum(source));
        evidence.put("targetResourcePid", target.getPid());
        evidence.put("targetBaseVersion", target.getVersion());
        evidence.put("targetBaseChecksum", checksum(target));
        evidence.put("activeReleasePid", active.releasePid());
        evidence.put("activeReleaseChecksum", active.snapshotChecksum());
        evidence.put("channelVersion", active.channelVersion());
        evidence.put("origin", active.origin());
        if (active.overridePid() != null) {
            evidence.put("overridePid", active.overridePid());
        }
        return evidence;
    }

    private ObjectNode staleEvidence(String nextFingerprint) {
        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("supersededByFingerprint", nextFingerprint);
        return evidence;
    }

    private String checksum(PageSchema page) {
        return snapshotFactory.checksum(snapshotFactory.create(page));
    }

    private void clear(PromotionUnit unit, String targetResourcePid) {
        if (unit.getDriftFingerprint() == null && "NONE".equals(unit.getDriftStatus())) {
            if (targetResourcePid != null && !targetResourcePid.equals(unit.getTargetResourcePid())) {
                unit.setTargetResourcePid(targetResourcePid);
                promotionUnitMapper.updateById(unit);
            }
            return;
        }
        unit.setTargetResourcePid(targetResourcePid);
        unit.setDriftStatus("NONE");
        unit.setDriftFingerprint(null);
        unit.setDriftDecision(null);
        unit.setDriftDecisionPid(null);
        unit.setDriftExecutionStatus("NONE");
        unit.setDriftExecutionPid(null);
        unit.setDriftExecutionPayload(null);
        jdbcTemplate.update("""
                UPDATE ab_promotion_unit
                SET target_resource_pid = ?, drift_status = 'NONE',
                    drift_fingerprint = NULL, drift_decision = NULL,
                    drift_decision_pid = NULL, drift_execution_status = 'NONE',
                    drift_execution_pid = NULL, drift_execution_payload = NULL
                WHERE id = ? AND tenant_id = ? AND deleted_flag = FALSE
                """, targetResourcePid, unit.getId(), unit.getTenantId());
    }

    private void recordEvent(
            Promotion promotion,
            PromotionUnit unit,
            String eventType,
            String driftKind,
            String fingerprint,
            String decision,
            String reasonCode,
            String reason,
            Long actorUserId,
            ObjectNode evidence) {
        recordEvent(
                promotion, unit, UniqueIdGenerator.generate(), eventType, driftKind,
                fingerprint, decision, reasonCode, reason, actorUserId, evidence);
    }

    private void recordEvent(
            Promotion promotion,
            PromotionUnit unit,
            String eventPid,
            String eventType,
            String driftKind,
            String fingerprint,
            String decision,
            String reasonCode,
            String reason,
            Long actorUserId,
            ObjectNode evidence) {
        jdbcTemplate.update("""
                INSERT INTO ab_promotion_drift_event (
                    pid, tenant_id, promotion_id, promotion_unit_id, event_type,
                    drift_kind, drift_fingerprint, decision, reason_code, reason,
                    actor_user_id, evidence)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """, eventPid, promotion.getTenantId(), promotion.getId(), unit.getId(),
                eventType, driftKind, fingerprint, decision, reasonCode, reason,
                actorUserId, json(evidence));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("promotion.drift.evidence-serialization-failed", exception);
        }
    }

    private String nextAction(String status, String decision) {
        if (!"RESOLVED".equals(status)) {
            return "SELECT_DECISION";
        }
        return switch (decision) {
            case "REBASE" -> "APPLY_REBASED_PROMOTION";
            case "BACKPORT" -> "REVERSE_PROMOTION_CREATED";
            case "KEEP_OVERRIDE" -> "PROMOTION_DEFERRED";
            case "OVERWRITE" -> "APPLY_PROMOTION";
            default -> "SELECT_DECISION";
        };
    }

    private String normalizedOptionalReason(String reason) {
        return reason == null || reason.isBlank() ? null : reason.trim();
    }

    private ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(CONFLICT, reason);
    }

    public record ActiveTargetRelease(
            String releasePid,
            long channelVersion,
            String snapshotChecksum,
            String overridePid,
            String origin) {
    }

    public record Assessment(
            String unitPid,
            String sourceResourcePid,
            String targetResourcePid,
            String pageKey,
            String kind,
            String status,
            String fingerprint,
            String decision,
            boolean applyReady,
            String nextAction,
            Integer sourceVersion,
            Integer targetVersion,
            ActiveTargetRelease active,
            ObjectNode evidence) {
    }
}
