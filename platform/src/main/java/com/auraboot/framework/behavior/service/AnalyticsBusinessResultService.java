package com.auraboot.framework.behavior.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Current-permission projection of committed facts; run success is never used as a business result. */
@Service
@RequiredArgsConstructor
public class AnalyticsBusinessResultService {
    private final AnalyticsExecutionSourceService sources;
    private final JdbcTemplate jdbc;
    private final DynamicDataService data;
    private final UserPermissionService permissions;
    private final MetaModelService models;
    private final ObjectMapper json;
    private final AnalyticsDeletedRecordAuthorization deletedRecords;

    public record Result(String eventId, String modelLabel, String operation, Instant recordedAt) {}
    public record Page(List<Result> records, boolean hasMore, int page, int pageSize) {}

    public Page read(String adoptionPid, int page, int pageSize) {
        if (page < 1 || page > 1000 || pageSize < 1 || pageSize > 50) {
            throw new IllegalArgumentException("Invalid business result page");
        }
        var source = sources.resolveForRead(adoptionPid);
        Long tenant = MetaContext.getCurrentTenantId();
        Long actor = MetaContext.getCurrentUserId();
        var rows = jdbc.queryForList("""
                SELECT o.event_id, o.target_type, o.target_key, o.occurred_at, o.payload
                FROM ab_behavior_outcome_outbox o
                JOIN ab_agent_run r ON r.tenant_id=o.tenant_id AND r.pid=o.run_id
                JOIN ab_analytics_task_execution a ON a.tenant_id=r.tenant_id AND a.task_pid=r.task_id
                WHERE a.tenant_id=? AND a.actor_user_id=? AND a.adoption_pid=?
                  AND o.user_id=? AND o.event_name='analytics_business_command_committed'
                ORDER BY o.id ASC LIMIT ? OFFSET ?
                """, tenant, actor, adoptionPid, actor, pageSize + 1, (page - 1) * pageSize);
        List<Result> records = new ArrayList<>();
        for (var row : rows) {
            String model = Objects.toString(row.get("target_type"), "");
            String pid = Objects.toString(row.get("target_key"), "");
            if (model.isBlank() || pid.isBlank() || !permissions.hasPermission(actor, "model." + model + ".read")) {
                throw new AccessDeniedException("Business result target is not readable");
            }
            try {
                var payload = json.readTree(row.get("payload").toString());
                if (!payload.path("analyticsExecution").equals(json.valueToTree(source.binding()))
                        || !model.equals(payload.path("modelCode").asText())
                        || !pid.equals(payload.path("recordPid").asText())
                        || !Set.of("create", "update", "delete").contains(payload.path("operation").asText())) {
                    throw new IllegalStateException("Business result provenance is inconsistent");
                }
                if ("delete".equals(payload.path("operation").asText())) {
                    deletedRecords.requireReadable(row.get("event_id").toString(), model, pid);
                } else if (data.getById(model, pid) == null) {
                    throw new AccessDeniedException("Business result target is unavailable");
                }
                String label = models.getModelDefinition(model).map(def -> def.getDisplayName()).orElse(null);
                if (model.equals(label)) label = null;
                records.add(new Result(row.get("event_id").toString(), label, payload.path("operation").asText(),
                        ((java.sql.Timestamp) row.get("occurred_at")).toInstant()));
            } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                throw new IllegalStateException("Business result provenance is invalid", invalid);
            }
        }
        boolean hasMore = records.size() > pageSize;
        return new Page(List.copyOf(records.subList(0, Math.min(records.size(), pageSize))), hasMore, page, pageSize);
    }
}
