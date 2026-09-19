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
    private final NamedParameterJdbcTemplate jdbc;
    public record Status(String state, int attempts) {}

    public Map<String, Status> read(Collection<String> adoptionPids) {
        Long tenant = MetaContext.getCurrentTenantId();
        Long actor = MetaContext.getCurrentUserId();
        if (tenant == null || actor == null) throw new AccessDeniedException("Execution status owner required");
        if (adoptionPids.size() > 50) throw new IllegalArgumentException("Too many execution status references");
        if (adoptionPids.isEmpty()) return Map.of();
        var rows = jdbc.queryForList("""
                SELECT a.adoption_pid, t.deleted_flag, latest.run_status,
                       (SELECT count(*) FROM ab_agent_run r WHERE r.tenant_id=a.tenant_id AND r.task_id=a.task_pid) AS attempts
                FROM ab_analytics_task_execution a
                JOIN ab_agent_task t ON t.tenant_id=a.tenant_id AND t.pid=a.task_pid
                LEFT JOIN LATERAL (
                    SELECT run_status FROM ab_agent_run r WHERE r.tenant_id=a.tenant_id AND r.task_id=a.task_pid
                    ORDER BY r.created_at DESC, r.pid DESC LIMIT 1
                ) latest ON TRUE
                WHERE a.tenant_id=:tenant AND a.actor_user_id=:actor AND a.adoption_pid IN (:adoptions)
                """, Map.of("tenant", tenant, "actor", actor, "adoptions", adoptionPids));
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
