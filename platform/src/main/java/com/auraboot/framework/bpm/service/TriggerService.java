package com.auraboot.framework.bpm.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.dto.ExecutionResult;
import com.auraboot.framework.bpm.dto.TriggerConfig;
import com.auraboot.framework.bpm.entity.BpmTriggerDefinition;
import com.auraboot.framework.bpm.enums.TriggerType;
import com.auraboot.framework.bpm.mapper.BpmTriggerDefinitionMapper;
import com.auraboot.framework.common.util.UlidGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * Trigger service for process orchestration.
 * Manages process triggers (scheduled, event, webhook, manual).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TriggerService {

    private final BpmTriggerDefinitionMapper triggerMapper;
    private final ProcessOrchestrationService orchestrationService;
    private final ObjectMapper objectMapper;

    /**
     * Create a new trigger for a process.
     */
    @Transactional
    public BpmTriggerDefinition createTrigger(String processKey, TriggerType type, TriggerConfig config) {
        Long tenantId = MetaContext.getCurrentTenantId();

        Map<String, Object> configMap = objectMapper.convertValue(config, new TypeReference<Map<String, Object>>() {});

        BpmTriggerDefinition trigger = BpmTriggerDefinition.builder()
                .pid(UlidGenerator.generate())
                .tenantId(tenantId)
                .processKey(processKey)
                .triggerType(type.name().toLowerCase())
                .triggerConfig(configMap)
                .status(StatusConstants.DISABLED)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        triggerMapper.insert(trigger);
        log.info("Trigger created: pid={}, processKey={}, type={}", trigger.getPid(), processKey, type);

        return trigger;
    }

    /**
     * Enable a trigger.
     */
    @Transactional
    public void enableTrigger(String triggerId) {
        BpmTriggerDefinition trigger = getTriggerOrThrow(triggerId);
        trigger.setStatus(StatusConstants.ENABLED);
        trigger.setUpdatedAt(Instant.now());
        triggerMapper.updateById(trigger);
        log.info("Trigger enabled: pid={}", triggerId);
    }

    /**
     * Disable a trigger.
     */
    @Transactional
    public void disableTrigger(String triggerId) {
        BpmTriggerDefinition trigger = getTriggerOrThrow(triggerId);
        trigger.setStatus(StatusConstants.DISABLED);
        trigger.setUpdatedAt(Instant.now());
        triggerMapper.updateById(trigger);
        log.info("Trigger disabled: pid={}", triggerId);
    }

    /**
     * List triggers for a process.
     */
    public List<BpmTriggerDefinition> listTriggers(String processKey) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return triggerMapper.findByProcessKey(tenantId, processKey);
    }

    /**
     * Get a specific trigger by PID.
     */
    public BpmTriggerDefinition getTrigger(String triggerId) {
        return triggerMapper.findByPid(triggerId);
    }

    /**
     * Update trigger configuration.
     */
    @Transactional
    public BpmTriggerDefinition updateTrigger(String triggerId, TriggerConfig config) {
        BpmTriggerDefinition trigger = getTriggerOrThrow(triggerId);
        Map<String, Object> configMap = objectMapper.convertValue(config, new TypeReference<Map<String, Object>>() {});
        trigger.setTriggerConfig(configMap);
        trigger.setUpdatedAt(Instant.now());
        triggerMapper.updateById(trigger);
        log.info("Trigger updated: pid={}", triggerId);
        return trigger;
    }

    /**
     * Delete a trigger (soft delete).
     */
    @Transactional
    public void deleteTrigger(String triggerId) {
        BpmTriggerDefinition trigger = getTriggerOrThrow(triggerId);
        triggerMapper.deleteById(trigger.getId());
        log.info("Trigger deleted: pid={}", triggerId);
    }

    /**
     * Manually fire a trigger (for testing).
     */
    @Transactional
    public ExecutionResult fireTrigger(String triggerId, Map<String, Object> payload) {
        BpmTriggerDefinition trigger = getTriggerOrThrow(triggerId);

        // Merge default payload from config with provided payload
        Map<String, Object> mergedPayload = new HashMap<>();
        if (trigger.getTriggerConfig() != null) {
            Object defaultPayload = trigger.getTriggerConfig().get("defaultPayload");
            if (defaultPayload instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> defaults = (Map<String, Object>) defaultPayload;
                mergedPayload.putAll(defaults);
            }
        }
        if (payload != null) {
            mergedPayload.putAll(payload);
        }

        // Update last fired timestamp
        trigger.setLastFiredAt(Instant.now());
        trigger.setUpdatedAt(Instant.now());
        triggerMapper.updateById(trigger);

        // Start the execution
        ExecutionResult result = orchestrationService.startExecution(
                trigger.getProcessKey(), "trigger:" + triggerId, mergedPayload);

        log.info("Trigger fired: pid={}, executionId={}", triggerId, result.executionId());
        return result;
    }

    private BpmTriggerDefinition getTriggerOrThrow(String triggerId) {
        BpmTriggerDefinition trigger = triggerMapper.findByPid(triggerId);
        if (trigger == null) {
            throw new IllegalArgumentException("Trigger not found: " + triggerId);
        }
        return trigger;
    }
}
