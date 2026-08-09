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

    public PromotionDriftCoordinator(
            JdbcTemplate jdbcTemplate,
            PromotionUnitMapper promotionUnitMapper,
            AuthoringPageSnapshotFactory snapshotFactory,
            ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.promotionUnitMapper = promotionUnitMapper;
        this.snapshotFactory = snapshotFactory;
        this.objectMapper = objectMapper;
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
            jdbcTemplate.update("""
                    UPDATE ab_promotion_unit
                    SET target_resource_pid = ?, drift_status = 'PENDING',
                        drift_fingerprint = ?, drift_decision = NULL,
                        drift_decision_pid = NULL
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
        String decisionPid = UniqueIdGenerator.generate();
        recordEvent(
                promotion, unit, decisionPid, "DECIDED", current.kind(), current.fingerprint(),
                request.getDecision(), "PROMOTION_DRIFT_DECISION_RECORDED",
                request.getReason().trim(), actorUserId, current.evidence());
        unit.setDriftStatus("RESOLVED");
        unit.setDriftDecision(request.getDecision());
        unit.setDriftDecisionPid(decisionPid);
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

    public void applyOverwrite(
            Promotion promotion,
            PromotionUnit unit,
            Assessment assessment,
            long actorUserId,
            String applyReason) {
        if (!assessment.applyReady()
                || !assessment.fingerprint().equals(unit.getDriftFingerprint())) {
            throw conflict("promotion.drift.fingerprint-stale");
        }
        int releaseUpdated = jdbcTemplate.update("""
                UPDATE ab_authoring_release
                SET status = 'SUPERSEDED'
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
        unit.setDriftStatus("APPLIED");
        promotionUnitMapper.updateById(unit);
        recordEvent(
                promotion, unit, "APPLIED", assessment.kind(), assessment.fingerprint(),
                "OVERWRITE", "PROMOTION_DRIFT_OVERWRITE_APPLIED",
                normalizedOptionalReason(applyReason), actorUserId, assessment.evidence());
    }

    private Assessment assessment(
            PromotionUnit unit,
            PageSchema source,
            PageSchema target,
            ActiveTargetRelease active,
            String kind,
            ObjectNode evidence) {
        boolean applyReady = "RESOLVED".equals(unit.getDriftStatus())
                && "OVERWRITE".equals(unit.getDriftDecision());
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
        jdbcTemplate.update("""
                UPDATE ab_promotion_unit
                SET target_resource_pid = ?, drift_status = 'NONE',
                    drift_fingerprint = NULL, drift_decision = NULL,
                    drift_decision_pid = NULL
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
            case "REBASE" -> "OPEN_REBASE_WORKSPACE";
            case "BACKPORT" -> "CREATE_REVERSE_PROMOTION";
            case "KEEP_OVERRIDE" -> "DEFER_PROMOTION";
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
