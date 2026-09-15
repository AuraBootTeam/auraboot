package com.auraboot.framework.behavior.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.conversation.TurnContext;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Atomically stores a task and its verified analytics binding under a caller request identity. */
@Service
@RequiredArgsConstructor
public class AnalyticsConversationTaskStore {
    private final AnalyticsExecutionSourceService sources;
    private final DynamicDataMapper data;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public record Request(String adoptionPid, String requestId) {}
    public record Stored(String taskPid, boolean created) {}

    @Transactional
    public Stored create(TurnContext ctx, Request request, Map<String, Object> task, Map<String, Object> input) {
        if (!Objects.equals(ctx.tenantId(), MetaContext.getCurrentTenantId())
                || !Objects.equals(ctx.executionUserId(), MetaContext.getCurrentUserId())) {
            throw new IllegalArgumentException("Task actor does not match the authenticated adoption owner");
        }
        if (request.requestId() == null || !UUID.fromString(request.requestId()).toString().equals(request.requestId())) {
            throw new IllegalArgumentException("A canonical execution request UUID is required");
        }
        var source = sources.resolve(request.adoptionPid());
        String key = UUID.nameUUIDFromBytes((ctx.tenantId() + ":" + ctx.executionUserId() + ":" + request.requestId())
                .getBytes(StandardCharsets.UTF_8)).toString();
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", key);
        var existing = jdbc.queryForList("""
                SELECT task_pid AS pid, adoption_pid
                FROM ab_analytics_task_execution WHERE tenant_id = ? AND request_key = ?::uuid
                """, ctx.tenantId(), key);
        if (!existing.isEmpty()) {
            if (existing.size() != 1 || !Objects.equals(existing.get(0).get("adoption_pid"), request.adoptionPid())) {
                throw new IllegalArgumentException("Execution request identity was already used for another adoption");
            }
            return new Stored(String.valueOf(existing.get(0).get("pid")), false);
        }
        // Different request identities for one adoption still refer to one logical task.
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                ctx.tenantId() + ":analytics-adoption:" + request.adoptionPid());
        var adopted = jdbc.queryForList("""
                SELECT task_pid FROM ab_analytics_task_execution
                WHERE tenant_id = ? AND actor_user_id = ? AND adoption_pid = ?
                """, ctx.tenantId(), ctx.executionUserId(), request.adoptionPid());
        if (!adopted.isEmpty()) return new Stored(String.valueOf(adopted.get(0).get("task_pid")), false);
        input.put("analyticsExecution", source.binding());
        input.put("analyticsExecutionRequestKey", key);
        input.put("userMessage", source.goal());
        task.put("description", source.goal());
        task.put("title", source.goal().substring(0, Math.min(80, source.goal().length())));
        task.put("created_by", ctx.executionUserId());
        try {
            task.put("input_data", json.writeValueAsString(input));
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalArgumentException("Cannot serialize analytics task binding", error);
        }
        if (data.insert("ab_agent_task", task) != 1) throw new IllegalStateException("Analytics task was not persisted");
        try {
            if (jdbc.update("""
                    INSERT INTO ab_analytics_task_execution
                    (tenant_id, task_pid, actor_user_id, adoption_pid, request_key, binding, goal)
                    VALUES (?, ?, ?, ?, ?::uuid, ?::jsonb, ?)
                    """, ctx.tenantId(), task.get("pid"), ctx.executionUserId(), request.adoptionPid(),
                    key, json.writeValueAsString(source.binding()), source.goal()) != 1) {
                throw new IllegalStateException("Analytics execution binding was not persisted");
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalArgumentException("Cannot serialize authoritative analytics binding", error);
        }
        return new Stored(String.valueOf(task.get("pid")), true);
    }
}
