package com.auraboot.framework.behavior.outcome;

import com.auraboot.framework.agent.service.StepContext;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Records committed CRUD commands, not inferred business benefit, in the command transaction. */
@Service
@RequiredArgsConstructor
public class AnalyticsCommandOutcomePublisher {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final BehaviorOutcomePublisher outcomes;

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(CommandPipelineContext ctx) {
        String runPid = StepContext.getRunPid();
        if (runPid == null || ctx.getRequest().isDryRun()) return;
        Object operation = ctx.getExecConfig().get("type");
        if (!Set.of("create", "update", "delete").contains(operation == null ? "" : operation)) return;
        var rows = jdbc.queryForList("""
                SELECT r.actor_user_id, r.principal_type, r.run_status, a.binding,
                       started.event_id AS started_event_id, started.payload AS started_payload
                FROM ab_agent_run r
                JOIN ab_analytics_task_execution a ON a.tenant_id=r.tenant_id AND a.task_pid=r.task_id
                LEFT JOIN ab_behavior_outcome_outbox started ON started.tenant_id=r.tenant_id
                  AND started.run_id=r.pid AND started.event_name='agent_execution_started'
                WHERE r.tenant_id=? AND r.pid=?
                FOR SHARE OF r
                """, ctx.getTenantId(), runPid);
        if (rows.isEmpty()) return;
        if (rows.size() != 1) throw new IllegalStateException("Ambiguous analytics command origin");
        var origin = rows.get(0);
        if (!"running".equals(origin.get("run_status"))
                || !Objects.equals(String.valueOf(ctx.getUserId()), String.valueOf(origin.get("actor_user_id")))
                || origin.get("started_event_id") == null) {
            throw new IllegalStateException("Analytics command has no active admitted execution");
        }
        if ("SANDBOX".equals(origin.get("principal_type"))) return;
        Map<String, Object> binding;
        try {
            var stored = json.readTree(origin.get("binding").toString());
            var started = json.readTree(origin.get("started_payload").toString()).path("analyticsExecution");
            if (!stored.equals(started) || !stored.path("analysisId").isTextual()) {
                throw new IllegalStateException("Analytics command origin changed after admission");
            }
            binding = json.convertValue(stored, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Invalid analytics command origin", invalid);
        }
        String recordPid = ctx.getRequest().getTargetRecordId();
        if (recordPid == null) {
            Map<String, Object> result = new LinkedHashMap<>(ctx.getFieldMapResults());
            result.putAll(ctx.getHandlerResults());
            Object pid = result.get("recordPid");
            if (pid == null) pid = result.get("pid");
            if (pid == null && result.get("record") instanceof Map<?, ?> record) pid = record.get("pid");
            if (pid instanceof String value) recordPid = value;
        }
        if (recordPid == null || recordPid.isBlank() || ctx.getCommand().getModelCode() == null) {
            throw new IllegalStateException("Analytics command has no persisted target reference");
        }
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("commandCode", ctx.getCommandCode());
        props.put("modelCode", ctx.getCommand().getModelCode());
        props.put("recordPid", recordPid);
        props.put("operation", operation);
        props.put("analyticsExecution", binding);
        props.put("principalType", origin.get("principal_type"));
        outcomes.publish(BehaviorOutcomeEvent.builder()
                .tenantId(ctx.getTenantId()).userId(ctx.getUserId())
                .eventId(UUID.randomUUID().toString()).eventName("analytics_business_command_committed")
                .runId(runPid).interactionId(binding.get("analysisId").toString())
                .causedByEventId(origin.get("started_event_id").toString())
                .targetType(ctx.getCommand().getModelCode()).targetKey(recordPid).props(props).build());
    }
}
