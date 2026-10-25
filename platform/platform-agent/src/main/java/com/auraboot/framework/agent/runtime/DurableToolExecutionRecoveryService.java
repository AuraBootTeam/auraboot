package com.auraboot.framework.agent.runtime;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.identity.ExecutionPrincipalContext;
import com.auraboot.framework.agent.runtime.context.ContextEnvelope;
import com.auraboot.framework.agent.runtime.context.ContextEnvelopeContext;
import com.auraboot.framework.agent.runtime.context.ContextEnvelopeFactory;
import com.auraboot.framework.agent.service.ToolAclChecker;
import com.auraboot.framework.agent.provider.ProviderExecutionResult;
import com.auraboot.framework.agent.provider.ToolProviderRegistry;
import com.auraboot.framework.agent.service.ActionRecorder;
import com.auraboot.framework.common.util.LogSanitizer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Recovers failed direct provider-side tool executions that are safe to retry.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DurableToolExecutionRecoveryService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final DurableToolExecutionLedger ledger;
    private final ToolProviderRegistry toolProviderRegistry;
    private final ObjectMapper objectMapper;
    private final ActionRecorder actionRecorder;
    private final JdbcTemplate jdbcTemplate;
    private final ContextEnvelopeFactory envelopeFactory;
    private final ToolAclChecker toolAclChecker;

    private static final Set<String> RECOVERABLE_RUN_STATES = Set.of("running", "failed");

    @Value("${agent.tool-execution.recovery.batch-size:50}")
    private int batchSize = 50;

    @Value("${agent.tool-execution.running-lease-ms:900000}")
    private long runningLeaseMillis = 900000L;

    @Scheduled(cron = "${agent.tool-execution.recovery.cron:0 * * * * *}")
    public int processDue() {
        return processDue(batchSize);
    }

    int processDue(int limit) {
        if (runningLeaseMillis <= 0) throw new IllegalStateException("Execution lease must be positive");
        int processed = ledger.markAbandonedExecutions(System.currentTimeMillis() - runningLeaseMillis);
        for (DurableToolExecutionRecord record : ledger.findRecoverable(limit)) {
            if (recover(record)) {
                processed++;
            }
        }
        return processed;
    }

    private boolean recover(DurableToolExecutionRecord record) {
        DurableToolExecutionRequest request = record == null ? null : record.request();
        if (!canRetry(record, request)) {
            ledger.markCompensationRequired(record, compensationReason(record, request));
            return true;
        }
        if (!ledger.claimRetry(record)) {
            return false;
        }
        var dispatched = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            ContextEnvelope envelope = recoveryEnvelope(request);
            return ExecutionPrincipalContext.callAs(envelope.principal(),
                    () -> ContextEnvelopeContext.callWith(envelope, () -> {
                        ToolAclChecker.Decision acl = toolAclChecker.check(request.tenantId(),
                                envelope.profileId(), envelope.channel(), "interactive", request.toolName());
                        if (!acl.isAllowed()) {
                            throw new IllegalStateException("Recovery tool ACL denied: " + acl.getReason());
                        }
                        dispatched.set(true);
                        return executeClaimed(record, request);
                    }));
        } catch (Exception e) {
            // CATCH: this scheduler runs outside a transaction. Only dispatched provider
            // failures remain retryable; missing authority is an explicit operator action.
            String error = safeError(e);
            if (dispatched.get()) {
                ledger.fail(request, record.executionKey(), "Error: " + error, error);
            } else {
                ledger.markCompensationRequired(record, "Recovery context rejected: " + error);
            }
            log.warn("Durable tool execution retry failed: key={}, error={}",
                    LogSanitizer.safe(record.executionKey()), error);
            return true;
        }
    }

    private boolean executeClaimed(DurableToolExecutionRecord record, DurableToolExecutionRequest request) {
        ProviderExecutionResult providerResult = toolProviderRegistry.execute(
                request.tenantId(), request.toolRef(), request.input());
        String rawResult = serializeProviderResult(providerResult);
        if (providerResult != null && providerResult.isSuccess()) {
            ledger.complete(request, record.executionKey(), rawResult);
            actionRecorder.recordProviderAction(
                    request.tenantId(), request.runPid(), request.toolRef(), toolDefinition(request),
                    request.input(), parseResult(rawResult), null, request.requiredEffects());
        } else {
            String error = providerResult == null ? "provider returned no result" : providerResult.getErrorMessage();
            ledger.fail(request, record.executionKey(), rawResult, error);
        }
        return true;
    }

    private ContextEnvelope recoveryEnvelope(DurableToolExecutionRequest request)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        var rows = jdbcTemplate.queryForList("""
                SELECT run_status, context_envelope::text AS context_envelope, context_envelope_hash,
                       timeout_at, actor_user_id, actor_member_id
                  FROM ab_agent_run
                 WHERE tenant_id = ? AND pid = ? AND task_id = ? AND agent_id = ?
                """, request.tenantId(), request.runPid(), request.taskPid(), request.agentCode());
        if (rows.size() != 1 || !RECOVERABLE_RUN_STATES.contains(rows.get(0).get("run_status"))) {
            throw new IllegalStateException("Recovery source run is unavailable or terminal");
        }
        var row = rows.get(0);
        if (!(row.get("context_envelope") instanceof String json) || json.isBlank()) {
            throw new IllegalStateException("Recovery source has no immutable ContextEnvelope");
        }
        ContextEnvelope envelope = objectMapper.readValue(json, ContextEnvelope.class);
        if (!envelopeFactory.verify(envelope)
                || !Objects.equals(row.get("context_envelope_hash"), envelope.envelopeHash())
                || !Objects.equals(request.tenantId(), envelope.tenantId())
                || !Objects.equals(request.agentCode(), envelope.agentCode())
                || !(row.get("actor_user_id") instanceof Number actor)
                || actor.longValue() != envelope.principal().actorUserId()
                || !(row.get("actor_member_id") instanceof Number member)
                || member.longValue() != envelope.principal().actorMemberId()) {
            throw new IllegalStateException("Recovery ContextEnvelope identity verification failed");
        }
        Instant now = Instant.now();
        Object timeout = row.get("timeout_at");
        if ((envelope.deadlineAt() != null && !now.isBefore(envelope.deadlineAt()))
                || (timeout instanceof java.sql.Timestamp timestamp && !now.isBefore(timestamp.toInstant()))
                || (timeout instanceof java.time.LocalDateTime date && !java.time.LocalDateTime.now().isBefore(date))) {
            throw new IllegalStateException("Recovery source run exceeded its deadline");
        }
        return envelope;
    }

    private boolean canRetry(DurableToolExecutionRecord record, DurableToolExecutionRequest request) {
        if (record == null || request == null) {
            return false;
        }
        if (!record.retryable() || record.attemptCount() >= record.maxAttempts()) {
            return false;
        }
        String toolRef = request.toolRef();
        String toolType = request.toolType();
        return hasText(toolRef)
                && (toolRef.startsWith("platform.")
                || toolRef.startsWith("custom:")
                || toolRef.startsWith("mcp:")
                || "platform".equals(toolType)
                || "custom".equals(toolType)
                || "mcp".equals(toolType)
                || "built_in".equals(toolType));
    }

    private String compensationReason(DurableToolExecutionRecord record, DurableToolExecutionRequest request) {
        if (record == null || request == null) {
            return "missing retry request snapshot";
        }
        if (!record.retryable()) {
            return "not retryable: missing external idempotency key";
        }
        if (record.attemptCount() >= record.maxAttempts()) {
            return "retry attempts exhausted";
        }
        return "unsupported recovery tool type";
    }

    private String serializeProviderResult(ProviderExecutionResult providerResult) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", providerResult != null && providerResult.isSuccess());
        if (providerResult != null && providerResult.getData() != null) {
            response.putAll(providerResult.getData());
        }
        if (providerResult != null && providerResult.getErrorMessage() != null) {
            response.put("error", providerResult.getErrorMessage());
        }
        if (providerResult != null) {
            response.put("durationMs", providerResult.getDurationMs());
        }
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            return "{\"success\":false,\"error\":\"Unable to serialize provider retry result\"}";
        }
    }

    private Map<String, Object> parseResult(String rawResult) {
        if (rawResult == null || rawResult.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(rawResult, MAP_TYPE);
        } catch (Exception e) {
            return Map.of("rawResult", rawResult);
        }
    }

    private AgentToolDefinition toolDefinition(DurableToolExecutionRequest request) {
        return AgentToolDefinition.builder()
                .name(request.toolName())
                .sourceCode(request.toolRef())
                .toolType(request.toolType() != null ? request.toolType() : "provider")
                .riskLevel("L3")
                .build();
    }

    private String safeError(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
