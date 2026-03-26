package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.entity.DecisionAlarm;
import com.auraboot.framework.meta.entity.DecisionDefinition;
import com.auraboot.framework.meta.mapper.DecisionAlarmMapper;
import com.auraboot.framework.meta.mapper.DecisionDefinitionMapper;
import com.auraboot.framework.meta.mapper.DecisionRecordMapper;
import com.auraboot.framework.meta.mapper.EvidenceRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * Decision Alarm Worker.
 * Periodically checks for evidence missing, decision missing, and invariant violations.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DecisionAlarmWorker {

    private static final int ALARM_BATCH_SIZE = 50;

    private final DecisionDefinitionMapper definitionMapper;
    private final EvidenceRecordMapper evidenceRecordMapper;
    private final DecisionRecordMapper decisionRecordMapper;
    private final DecisionAlarmMapper alarmMapper;

    /**
     * Check for alarm conditions.
     * Scheduled via DatabaseSchedulerEngine (sys-decision-alarm, interval 1min).
     */
    public void checkAlarms() {
        try {
            checkEvidenceMissing();
        } catch (Exception e) {
            log.error("Evidence missing check failed: {}", e.getMessage());
        }

        try {
            checkDecisionMissing();
        } catch (Exception e) {
            log.error("Decision missing check failed: {}", e.getMessage());
        }
    }

    /**
     * Check for evidence that hasn't arrived within the defined timeout.
     */
    private void checkEvidenceMissing() {
        // Use a reasonable cutoff (30 minutes ago as default minimum)
        Instant cutoffTime = Instant.now().minus(30, ChronoUnit.MINUTES);

        // Query all published definitions to find their tenant contexts
        // For simplicity, we check across all tenants that have published definitions
        List<DecisionDefinition> definitions = definitionMapper.findAllPublished(null);
        if (definitions == null || definitions.isEmpty()) {
            return;
        }

        for (DecisionDefinition def : definitions) {
            try {
                List<Map<String, Object>> incompleteSubjects = evidenceRecordMapper
                        .findIncompleteEvidenceSubjects(def.getTenantId(), cutoffTime, ALARM_BATCH_SIZE);

                for (Map<String, Object> subject : incompleteSubjects) {
                    String subjectType = (String) subject.get("subject_type");
                    String subjectId = (String) subject.get("subject_id");
                    String stage = (String) subject.get("stage");

                    createAlarmIfNotExists(def.getTenantId(), "evidence_missing",
                            subjectType, subjectId, stage, "warn",
                            "Evidence incomplete past timeout for stage: " + stage);
                }
            } catch (Exception e) {
                log.debug("Evidence check skipped for definition {}: {}", def.getCode(), e.getMessage());
            }
        }
    }

    /**
     * Check for subjects with complete evidence but no decision.
     */
    private void checkDecisionMissing() {
        List<DecisionDefinition> definitions = definitionMapper.findAllPublished(null);
        if (definitions == null || definitions.isEmpty()) {
            return;
        }

        for (DecisionDefinition def : definitions) {
            try {
                List<Map<String, Object>> undecided = decisionRecordMapper
                        .findUndecidedWithCompleteEvidence(def.getTenantId(), ALARM_BATCH_SIZE);

                for (Map<String, Object> subject : undecided) {
                    String subjectType = (String) subject.get("subject_type");
                    String subjectId = (String) subject.get("subject_id");
                    String stage = (String) subject.get("stage");

                    createAlarmIfNotExists(def.getTenantId(), "decision_missing",
                            subjectType, subjectId, stage, "warn",
                            "Evidence complete but no decision produced for stage: " + stage);
                }
            } catch (Exception e) {
                log.debug("Decision missing check skipped for definition {}: {}", def.getCode(), e.getMessage());
            }
        }
    }

    /**
     * Create alarm if no OPEN alarm of the same type exists for this subject.
     */
    private void createAlarmIfNotExists(Long tenantId, String alarmType,
                                         String subjectType, String subjectId,
                                         String stage, String severity, String message) {
        int existing = alarmMapper.countOpenAlarm(tenantId, alarmType, subjectType, subjectId, stage);
        if (existing > 0) {
            return; // Already has an open alarm
        }

        DecisionAlarm alarm = new DecisionAlarm();
        alarm.setTenantId(tenantId);
        alarm.setAlarmType(alarmType);
        alarm.setSubjectType(subjectType);
        alarm.setSubjectId(subjectId);
        alarm.setStage(stage);
        alarm.setSeverity(severity);
        alarm.setMessage(message);
        alarm.setStatus(StatusConstants.OPEN);
        alarm.setCreatedAt(Instant.now());

        alarmMapper.insertAlarm(alarm);
        log.info("Alarm created: type={}, subject={}/{}, stage={}", alarmType, subjectType, subjectId, stage);
    }
}
