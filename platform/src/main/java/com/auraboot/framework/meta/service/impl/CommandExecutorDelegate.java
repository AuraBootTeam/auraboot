package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.BindingRule;
import com.auraboot.framework.meta.entity.CommandDefinition;

import java.util.List;
import java.util.Map;

/**
 * Delegate interface exposing internal CommandExecutor operations for Phase implementations.
 * This allows phases to call existing logic during the incremental migration,
 * without directly depending on CommandExecutorImpl.
 *
 * @author AuraBoot Team
 * @since 8.0.0
 */
public interface CommandExecutorDelegate {

    void executeAssertPhase(List<BindingRule> assertRules, Map<String, Object> payload);

    void executePreconditionsPhase(Map<String, Object> execConfig, Map<String, Object> payload,
                                   Long tenantId, CommandDefinition command, CommandExecuteRequest request);

    void executeValidationPhase(Map<String, Object> execConfig, Map<String, Object> payload,
                                 Long tenantId, CommandDefinition command, CommandExecuteRequest request);

    void executeCrossFieldRules(CommandDefinition command, Map<String, Object> payload,
                                 Map<String, Object> execConfig);

    void executeCommandFieldValidationPhase(Map<String, Object> execConfig, Map<String, Object> payload,
                                             CommandDefinition command, CommandExecuteRequest request);

    boolean hasPluginHandler(String commandCode);

    boolean shouldExecuteDslPersistenceWithPlugin(Map<String, Object> execConfig, CommandExecuteRequest request);

    Map<String, Object> readRecordSnapshot(Long tenantId, String modelCode, String recordId);

    void propagateFieldMapRecordId(CommandExecuteRequest request, Map<String, Object> fieldMapResults);

    void executeComputedFieldsPhase(Map<String, Object> execConfig, Map<String, Object> payload,
                                     Long tenantId, CommandDefinition command,
                                     CommandExecuteRequest request, Map<String, Object> fieldMapResults);

    void recordChangeTracking(CommandDefinition command, CommandExecuteRequest request,
                               Long tenantId, Long userId, Map<String, Object> beforeSnapshot);

    Map<String, Object> executeHandlerPhase(List<BindingRule> handlerRules, CommandDefinition command,
                                             Map<String, Object> payload, Map<String, Object> fieldMapResults,
                                             Long tenantId, Long userId, CommandExecuteRequest request,
                                             Map<String, Object> execConfig);

    void persistHandlerResults(String modelCode, Map<String, Object> payload,
                                Map<String, Object> handlerResults, Long tenantId,
                                CommandExecuteRequest request, Map<String, Object> fieldMapResults);

    void executeConsistencyCheckPhase(CommandDefinition command, Map<String, Object> payload,
                                       Map<String, Object> fieldMapResults, Long tenantId,
                                       Map<String, Object> execConfig);

    void executeRollUpRecalculation(String modelCode, Map<String, Object> payload,
                                     Map<String, Object> fieldMapResults, Long tenantId);

    void executeGovernanceSnapshot(String modelCode, Map<String, Object> payload,
                                    Map<String, Object> fieldMapResults, Long tenantId, Long userId);

    void executePostActionPhase(Map<String, Object> execConfig, Map<String, Object> payload,
                                 Long tenantId, Long userId, CommandDefinition command,
                                 CommandExecuteRequest request, Map<String, Object> fieldMapResults);

    Map<String, Object> executeApiCallPhase(List<BindingRule> apiCallRules,
                                             Map<String, Object> payload, Map<String, Object> handlerResults);

    void executeWebhookPhase(List<BindingRule> webhookRules, CommandDefinition command,
                              Map<String, Object> payload, Map<String, Object> results, Long tenantId);

    void saveAuditLog(Long tenantId, String commandCode, String commandPid, Long userId,
                       Map<String, Object> payload, Map<String, Object> result,
                       boolean success, String errorMessage, long executionTimeMs,
                       String phaseReached, Map<String, Long> phaseTimings);

    void saveIdempotencyRecord(String clientRequestId, String commandCode,
                                Map<String, Object> payload, Map<String, Object> resultData, Long tenantId);

    void publishDomainEvent(CommandDefinition command, CommandExecuteRequest request,
                             Map<String, Object> payload, Long tenantId, Long userId,
                             Map<String, Object> beforeSnapshot);
}
