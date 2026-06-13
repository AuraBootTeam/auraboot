package com.auraboot.framework.decision.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.decision.dto.DecisionDashboardDTO;
import com.auraboot.framework.decision.dto.DecisionDashboardExceptionDTO;
import com.auraboot.framework.decision.dto.DecisionDashboardSummaryDTO;
import com.auraboot.framework.decision.entity.DrtLogEntity;
import com.auraboot.framework.decision.mapper.DrtDefinitionMapper;
import com.auraboot.framework.decision.mapper.DrtLogMapper;
import com.auraboot.framework.decision.service.DecisionDashboardService;
import com.auraboot.framework.eventpolicy.mapper.DrtPolicyDefinitionMapper;
import com.auraboot.framework.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Aggregates the DecisionOps F1 workbench from persisted runtime and EventPolicy data.
 */
@Service
@RequiredArgsConstructor
public class DecisionDashboardServiceImpl implements DecisionDashboardService {

    private static final int EXCEPTION_LIMIT = 5;

    private final DrtDefinitionMapper definitionMapper;
    private final DrtPolicyDefinitionMapper policyDefinitionMapper;
    private final DrtLogMapper logMapper;

    @Override
    public DecisionDashboardDTO getDashboard() {
        Long tenantId = requireTenant();
        List<DrtLogEntity> todayLogs = logMapper.findSince(tenantId, startOfTodayUtc());

        DecisionDashboardSummaryDTO summary = new DecisionDashboardSummaryDTO();
        summary.setDefinitions(definitionMapper.countByTenant(tenantId));
        summary.setPolicies(policyDefinitionMapper.countByTenant(tenantId));
        summary.setEvaluationsToday(todayLogs.size());
        summary.setMatched(todayLogs.stream().filter((log) -> Boolean.TRUE.equals(log.getMatched())).count());
        summary.setFailed(todayLogs.stream().filter((log) -> isFailed(log.getStatus())).count());
        summary.setRetrying(todayLogs.stream().filter((log) -> isRetrying(log.getStatus())).count());
        summary.setP95LatencyMs(p95(todayLogs));

        DecisionDashboardDTO result = new DecisionDashboardDTO();
        result.setSummary(summary);
        result.setExceptions(todayLogs.stream()
                .filter((log) -> isFailed(log.getStatus()) || isRetrying(log.getStatus()))
                .limit(EXCEPTION_LIMIT)
                .map(this::toException)
                .toList());
        return result;
    }

    private Long requireTenant() {
        Long tenantId = MetaContext.exists() ? MetaContext.getCurrentTenantId() : null;
        if (tenantId == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Decision dashboard not found");
        }
        return tenantId;
    }

    private Instant startOfTodayUtc() {
        return LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    private boolean isFailed(String status) {
        return "ERROR".equals(status) || "FAILED".equals(status);
    }

    private boolean isRetrying(String status) {
        return "FAILED_RETRYING".equals(status) || "RETRYING".equals(status);
    }

    private Long p95(List<DrtLogEntity> logs) {
        List<Long> durations = logs.stream()
                .map(DrtLogEntity::getDurationMs)
                .filter((duration) -> duration != null && duration >= 0)
                .sorted()
                .toList();
        if (durations.isEmpty()) {
            return null;
        }
        int index = Math.max(0, (int) Math.ceil(durations.size() * 0.95) - 1);
        return durations.get(index);
    }

    private DecisionDashboardExceptionDTO toException(DrtLogEntity log) {
        DecisionDashboardExceptionDTO dto = new DecisionDashboardExceptionDTO();
        dto.setTraceId(log.getTraceId());
        dto.setCode(log.getDecisionCode());
        dto.setStatus(normalizeExceptionStatus(log.getStatus()));
        dto.setError(log.getErrorMessage());
        dto.setTime(log.getCreatedAt());
        return dto;
    }

    private String normalizeExceptionStatus(String status) {
        if ("ERROR".equals(status)) {
            return "ERROR";
        }
        if (isRetrying(status)) {
            return "FAILED_RETRYING";
        }
        return "FAILED";
    }
}
