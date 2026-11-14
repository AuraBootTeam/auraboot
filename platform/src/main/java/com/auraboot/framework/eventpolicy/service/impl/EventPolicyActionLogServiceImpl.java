package com.auraboot.framework.eventpolicy.service.impl;

import com.auraboot.framework.eventpolicy.dto.EventPolicyActionLogDTO;
import com.auraboot.framework.eventpolicy.entity.DrtPolicyExecLogEntity;
import com.auraboot.framework.eventpolicy.mapper.DrtPolicyExecLogMapper;
import com.auraboot.framework.eventpolicy.service.EventPolicyActionLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Default {@link EventPolicyActionLogService}: query branches and entity→DTO
 * projection moved verbatim from EventPolicyController. No behavior change.
 */
@Service
@RequiredArgsConstructor
public class EventPolicyActionLogServiceImpl implements EventPolicyActionLogService {

    private final DrtPolicyExecLogMapper execLogMapper;

    @Override
    public List<EventPolicyActionLogDTO> findActionLogs(Long tenantId, String policyCode, String policyCodePrefix,
                                                        String decisionTraceId, String correlationId, int limit) {
        boolean hasPolicyFilter = StringUtils.hasText(policyCode) || StringUtils.hasText(policyCodePrefix);
        List<DrtPolicyExecLogEntity> rows = hasPolicyFilter
                ? execLogMapper.findByPolicyCodeFilter(tenantId, normalize(policyCode),
                        normalize(policyCodePrefix), limit)
                : execLogMapper.findByTraceLink(tenantId, normalize(decisionTraceId), normalize(correlationId));
        return rows.stream().map(this::toDto).toList();
    }

    @Override
    public EventPolicyActionLogDTO toDto(DrtPolicyExecLogEntity entity) {
        EventPolicyActionLogDTO dto = new EventPolicyActionLogDTO();
        dto.setPid(entity.getPid());
        dto.setTenantId(entity.getTenantId());
        dto.setIdempotencyKey(entity.getIdempotencyKey());
        dto.setPolicyCode(entity.getPolicyCode());
        dto.setDecisionTraceId(entity.getDecisionTraceId());
        dto.setCorrelationId(entity.getCorrelationId());
        dto.setRuleCode(entity.getRuleCode());
        dto.setActionType(entity.getActionType());
        dto.setStatus(entity.getStatus());
        dto.setFailureStrategy(entity.getFailureStrategy());
        dto.setErrorMessage(entity.getErrorMessage());
        dto.setResultPayload(entity.getResultPayload());
        dto.setActionPayload(entity.getActionPayload());
        dto.setContextPayload(entity.getContextPayload());
        dto.setAttemptCount(entity.getAttemptCount());
        dto.setMaxAttempts(entity.getMaxAttempts());
        dto.setNextRetryAt(entity.getNextRetryAt());
        dto.setLastRetryAt(entity.getLastRetryAt());
        dto.setDeadLetteredAt(entity.getDeadLetteredAt());
        dto.setExecutedAt(entity.getExecutedAt());
        return dto;
    }

    private static String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
