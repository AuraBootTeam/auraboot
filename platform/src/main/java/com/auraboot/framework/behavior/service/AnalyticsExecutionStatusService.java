package com.auraboot.framework.behavior.service;

import com.auraboot.framework.application.tenant.MetaContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import java.util.*;

/** Bounded owner-scoped status projection, called only after suggestion-source authorization. */
@Service
@RequiredArgsConstructor
public class AnalyticsExecutionStatusService {
    @org.springframework.lang.Nullable
    private final AnalyticsExecutionReadModel executionReads;
    public record Status(String state, int attempts) {}

    public Map<String, Status> read(Collection<String> adoptionPids) {
        Long tenant = MetaContext.getCurrentTenantId();
        Long actor = MetaContext.getCurrentUserId();
        if (tenant == null || actor == null) throw new AccessDeniedException("Execution status owner required");
        if (adoptionPids.size() > 50) throw new IllegalArgumentException("Too many execution status references");
        if (adoptionPids.isEmpty()) return Map.of();
        if (executionReads == null) {
            throw new com.auraboot.framework.exception.BusinessException(
                    com.auraboot.framework.common.constant.ResponseCode.UnsupportedFeature,
                    "Analytics agent execution status is not installed");
        }
        var rows = executionReads.statuses(tenant, actor, adoptionPids);
        Map<String, Status> result = new HashMap<>();
        for (var row : rows) {
            String state = Boolean.TRUE.equals(row.get("deleted_flag")) ? "unavailable"
                    : row.get("run_status") == null ? "not_started" : String.valueOf(row.get("run_status"));
            if (!Set.of("unavailable", "not_started", "running", "pending", "queued", "success", "failed", "cancelled").contains(state)) {
                state = "unknown";
            }
            result.put(String.valueOf(row.get("adoption_pid")), new Status(state, ((Number) row.get("attempts")).intValue()));
        }
        return result;
    }
}
