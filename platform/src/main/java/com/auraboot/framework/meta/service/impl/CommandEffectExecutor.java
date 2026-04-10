package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.BindingRule;
import com.auraboot.framework.meta.entity.CommandAuditLog;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.event.CommandExecutedEvent;
import com.auraboot.framework.meta.event.StateTransitionEvent;
import com.auraboot.framework.meta.mapper.CommandAuditLogMapper;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.EventStore;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.OutboxWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles EFFECT phase of the command execution pipeline.
 * Writes events to outbox and event store, records audit logs.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommandEffectExecutor {

    private final OutboxWriter outboxWriter;
    private final EventStore eventStore;
    private final CommandAuditLogMapper commandAuditLogMapper;
    private final DynamicDataMapper dynamicDataMapper;
    private final MetaModelService metaModelService;
    private final ObjectMapper objectMapper;

    public void executeEffectPhase(List<BindingRule> effectRules,
                            CommandDefinition command,
                            Map<String, Object> payload,
                            Map<String, Object> results,
                            Long tenantId, Long userId,
                            CommandExecuteRequest request,
                            String targetState) {
        for (BindingRule rule : effectRules) {
            String eventType = StringUtils.hasText(rule.getEventType())
                    ? rule.getEventType()
                    : "CommandExecuted";

            CommandExecutedEvent event = new CommandExecutedEvent(
                    command.getCode(), command.getModelCode(),
                    payload, results, tenantId, userId);

            // Write to outbox (same transaction) for delivery
            outboxWriter.write(event, command.getCode(), tenantId);

            // Write to event store (same transaction) for permanent history
            appendToEventStore(event, command, tenantId, userId, request);

            log.debug("Wrote event {} to outbox and event store for command {}", eventType, command.getCode());
        }

        // Record state transition event if a state change occurred
        if (targetState != null && request != null && StringUtils.hasText(request.getTargetRecordId())) {
            recordStateTransitionEvent(command, tenantId, userId, request, targetState);
        }
    }

    public void saveAuditLog(Long tenantId, String commandCode, String commandPid,
                      Long userId, Map<String, Object> requestPayload,
                      Map<String, Object> executionResult,
                      boolean success, String errorMessage,
                      long executionTimeMs, String phaseReached,
                      Map<String, Long> phaseTimings) {
        try {
            CommandAuditLog auditLog = new CommandAuditLog();
            auditLog.setTenantId(tenantId);
            auditLog.setCommandCode(commandCode);
            auditLog.setCommandPid(commandPid);
            auditLog.setUserId(userId);
            auditLog.setRequestPayload(requestPayload != null ? objectMapper.writeValueAsString(requestPayload) : null);
            auditLog.setExecutionResult(executionResult != null ? objectMapper.writeValueAsString(executionResult) : null);
            auditLog.setSuccess(success);
            auditLog.setErrorMessage(errorMessage);
            auditLog.setExecutionTimeMs(executionTimeMs);
            auditLog.setPhaseReached(phaseReached);
            auditLog.setPhaseTimings(phaseTimings != null ? objectMapper.writeValueAsString(phaseTimings) : null);
            auditLog.setCreatedAt(Instant.now());

            commandAuditLogMapper.insertLog(auditLog);
        } catch (Exception e) {
            log.warn("Failed to save audit log: {}", e.getMessage());
        }
    }

    private void recordStateTransitionEvent(CommandDefinition command, Long tenantId, Long userId,
                                             CommandExecuteRequest request, String targetState) {
        try {
            // Read current state before the transition was written
            // (Note: by this point the state was already written during STATE_CHECK phase)
            StateTransitionEvent transitionEvent = new StateTransitionEvent(
                    command.getModelCode(), request.getTargetRecordId(),
                    null, targetState, command.getCode(), tenantId, userId);

            String eventPayload = objectMapper.writeValueAsString(transitionEvent);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("userId", userId != null ? userId : 0L);
            metadata.put("commandCode", command.getCode());
            metadata.put("fromState", "");
            metadata.put("toState", targetState);

            eventStore.append(tenantId, "StateTransition", command.getModelCode(),
                    request.getTargetRecordId(), eventPayload, metadata);
        } catch (Exception e) {
            log.warn("Failed to record state transition event: {}", e.getMessage());
        }
    }

    private void appendToEventStore(CommandExecutedEvent event, CommandDefinition command,
                                     Long tenantId, Long userId, CommandExecuteRequest request) {
        try {
            String aggregateType = command.getModelCode();
            String aggregateId = deriveAggregateId(event, request);
            String payload = objectMapper.writeValueAsString(event);

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("userId", userId != null ? userId : 0L);
            metadata.put("commandCode", command.getCode());
            metadata.put("eventId", event.getEventId());
            metadata.put("source", "CommandExecutor");

            eventStore.append(tenantId, event.getEventType(), aggregateType, aggregateId, payload, metadata);
        } catch (EventStoreImpl.ConcurrencyException e) {
            log.warn("EventStore version conflict during EFFECT phase: {}", e.getMessage());
            throw new BusinessException(ResponseCode.BadParam,
                    "Concurrent modification detected: " + e.getMessage());
        } catch (Exception e) {
            log.error("Failed to write to event store: {}", e.getMessage());
            // Non-fatal: outbox write succeeded, event store is supplementary
        }
    }

    private String deriveAggregateId(CommandExecutedEvent event, CommandExecuteRequest request) {
        if (request != null && StringUtils.hasText(request.getTargetRecordId())) {
            return request.getTargetRecordId();
        }
        if (event.getPayload() != null) {
            Object id = event.getPayload().get("id");
            if (id != null && !id.toString().isEmpty()) {
                return id.toString();
            }
        }
        return event.getEventId();
    }
}
