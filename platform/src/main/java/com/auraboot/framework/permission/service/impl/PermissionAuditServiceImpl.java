package com.auraboot.framework.permission.service.impl;

import com.auraboot.framework.permission.engine.model.EvaluationStep;
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
import java.util.Collections;
import java.util.List;

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
            // CATCH: non-transactional async write — safe to swallow; must not affect caller
            log.warn("Failed to persist permission audit log: memberId={}, resource={}, action={}: {}",
                    explanation.memberId(), explanation.resource(), explanation.action(), e.getMessage());
        }
    }

    @Override
    public List<PermissionAuditLog> getRecentLogs(Long tenantId, int limit) {
        try {
            return auditLogMapper.findRecent(tenantId, limit);
        } catch (Exception e) {
            log.warn("Failed to query recent audit logs for tenant={}: {}", tenantId, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<PermissionAuditLog> getLogsByMember(Long tenantId, Long memberId, int limit) {
        try {
            return auditLogMapper.findByMember(tenantId, memberId, limit);
        } catch (Exception e) {
            log.warn("Failed to query audit logs for tenant={}, memberId={}: {}",
                    tenantId, memberId, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<PermissionAuditLog> getLogsByResource(Long tenantId, String resourceCode, int limit) {
        try {
            return auditLogMapper.findByResource(tenantId, resourceCode, limit);
        } catch (Exception e) {
            log.warn("Failed to query audit logs for tenant={}, resource={}: {}",
                    tenantId, resourceCode, e.getMessage());
            return Collections.emptyList();
        }
    }
}
