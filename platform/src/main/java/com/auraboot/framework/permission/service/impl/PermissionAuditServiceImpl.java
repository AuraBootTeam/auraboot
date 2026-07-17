package com.auraboot.framework.permission.service.impl;

import com.auraboot.framework.permission.engine.model.EvaluationStep;
import com.auraboot.framework.permission.engine.model.EvaluationVerdict;
import com.auraboot.framework.permission.engine.model.PermissionExplanation;
import com.auraboot.framework.permission.entity.PermissionAuditLog;
import com.auraboot.framework.permission.mapper.PermissionAuditLogMapper;
import com.auraboot.framework.permission.service.PermissionAuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Default implementation of PermissionAuditService.
 *
 * <p>Writes run on the "auditExecutor" thread pool (or the common async pool)
 * to keep the permission check hot path non-blocking.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionAuditServiceImpl implements PermissionAuditService {

    private final PermissionAuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;

    @Override
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logEvaluation(Long tenantId, PermissionExplanation explanation) {
        // Only log DENY decisions to avoid hot-path spam
        if (explanation.finalResult()) {
            return;
        }
        try {
            PermissionAuditLog entry = new PermissionAuditLog();
            entry.setTenantId(tenantId);
            entry.setMemberId(explanation.memberId());
            entry.setResourceCode(explanation.resource());
            entry.setActionCode(explanation.action());
            entry.setRecordId(explanation.recordId());
            entry.setRecordPid(explanation.recordPid());
            entry.setResult(explanation.finalResult());

            // Derive reason from the first DENY step
            String reason = explanation.steps().stream()
                    .filter(s -> s.verdict() != null && s.verdict().name().equals("DENY"))
                    .map(EvaluationStep::reason)
                    .findFirst()
                    .orElse("denied by policy");
            entry.setReason(reason);

            // Serialize steps as generic objects for JSONB storage
            @SuppressWarnings("unchecked")
            List<Object> trace = objectMapper.convertValue(
                    explanation.steps(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Object.class));
            entry.setEvaluationTrace(trace);

            entry.setCreatedAt(Instant.now());
            auditLogMapper.insertAuditLog(entry);
        } catch (Exception e) {
            // §P2 best-effort: audit write runs @Async on a separate transaction
            // and must never propagate failure to the permission-check hot path.
            // A persisted audit row is nice-to-have for forensics; a missed one
            // is logged here for ops to triage.
            log.warn("Failed to persist permission audit log: memberId={}, resource={}, action={}: {}",
                    explanation.memberId(), explanation.resource(), explanation.action(), e.getMessage(), e);
        }
    }

    @Override
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logFieldGovernanceFilter(
            Long tenantId,
            Long memberId,
            String resourceCode,
            String actionCode,
            Long recordId,
            String recordPid,
            Collection<String> hiddenFields) {
        List<String> fieldCodes = hiddenFields == null ? List.of() : hiddenFields.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .sorted()
                .toList();
        if (tenantId == null || memberId == null || resourceCode == null || actionCode == null || fieldCodes.isEmpty()) {
            return;
        }

        String reason = "字段权限拒绝：字段已从响应移除";
        try {
            PermissionAuditLog entry = new PermissionAuditLog();
            entry.setTenantId(tenantId);
            entry.setMemberId(memberId);
            entry.setResourceCode(resourceCode);
            entry.setActionCode(actionCode);
            entry.setRecordId(recordId);
            entry.setRecordPid(recordPid);
            entry.setResult(false);
            entry.setReason(reason);

            Map<String, Object> fieldGovernance = new LinkedHashMap<>();
            fieldGovernance.put("fieldRefs", fieldCodes.stream()
                    .map(field -> "record.data." + field)
                    .toList());
            fieldGovernance.put("hiddenFields", fieldCodes);
            fieldGovernance.put("fieldCount", fieldCodes.size());
            fieldGovernance.put("reason", "field-permission-hidden");
            fieldGovernance.put("validation", "DENY");
            fieldGovernance.put("source", "dynamic-data-field-permission");
            fieldGovernance.put("modelCode", resourceCode);

            EvaluationStep step = new EvaluationStep(
                    "FieldPermission",
                    EvaluationVerdict.DENY,
                    reason,
                    Map.of("fieldGovernance", fieldGovernance));

            @SuppressWarnings("unchecked")
            List<Object> trace = objectMapper.convertValue(
                    List.of(step),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Object.class));
            entry.setEvaluationTrace(trace);
            entry.setCreatedAt(Instant.now());
            auditLogMapper.insertAuditLog(entry);
        } catch (Exception e) {
            log.warn("Failed to persist field-governance audit log: memberId={}, resource={}, action={}: {}",
                    memberId, resourceCode, actionCode, e.getMessage(), e);
        }
    }

    @Override
    public List<PermissionAuditLog> getRecentLogs(Long tenantId, int limit) {
        try {
            return auditLogMapper.findRecent(tenantId, limit);
        } catch (Exception e) {
            // §P4 fail-closed for read query: empty list keeps the audit UI
            // safe and observable even when the audit table is unavailable
            // — operators see "no recent activity" rather than a 500.
            log.warn("Failed to query recent audit logs for tenant={}: {}", tenantId, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @Override
    public List<PermissionAuditLog> getLogsByMember(Long tenantId, Long memberId, int limit) {
        try {
            return auditLogMapper.findByMember(tenantId, memberId, limit);
        } catch (Exception e) {
            // §P4 fail-closed: see getRecentLogs above.
            log.warn("Failed to query audit logs for tenant={}, memberId={}: {}",
                    tenantId, memberId, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @Override
    public List<PermissionAuditLog> getLogsByResource(Long tenantId, String resourceCode, int limit) {
        try {
            return auditLogMapper.findByResource(tenantId, resourceCode, limit);
        } catch (Exception e) {
            // §P4 fail-closed: see getRecentLogs above.
            log.warn("Failed to query audit logs for tenant={}, resource={}: {}",
                    tenantId, resourceCode, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @Override
    public List<PermissionAuditLog> getLogsByTraceId(Long tenantId, String traceId, int limit) {
        try {
            return auditLogMapper.findByTraceId(tenantId, traceId, limit);
        } catch (Exception e) {
            // §P4 fail-closed: see getRecentLogs above.
            log.warn("Failed to query audit logs for tenant={}, traceId={}: {}",
                    tenantId, traceId, e.getMessage(), e);
            return Collections.emptyList();
        }
    }
}
