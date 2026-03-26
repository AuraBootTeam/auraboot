package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.dto.InvariantEvaluationResultDTO;

import java.util.List;
import java.util.Map;

/**
 * Invariant Engine interface.
 * Evaluates PRE/POST/ALWAYS invariants during command execution pipeline.
 *
 * @author AuraBoot Team
 * @since 2.5.0
 */
public interface InvariantEngine {

    /**
     * Evaluate PRE invariants before command execution.
     * Throws ValidationException if ERROR-severity invariant is violated.
     *
     * @param tenantId     current tenant
     * @param commandCode  command being executed
     * @param modelCode    target model
     * @param payload      command payload
     * @param recordId     target record id (nullable)
     * @param currentState current state of the record (nullable)
     * @return list of evaluation results
     */
    List<InvariantEvaluationResultDTO> evaluatePreInvariants(
            Long tenantId, String commandCode, String modelCode,
            Map<String, Object> payload, String recordId, String currentState);

    /**
     * Evaluate POST invariants after command execution.
     * Never throws - violations create alarms only.
     *
     * @param tenantId     current tenant
     * @param commandCode  command that was executed
     * @param modelCode    target model
     * @param payload      command payload
     * @param recordId     target record id (nullable)
     * @param currentState current/target state after execution (nullable)
     * @return list of evaluation results
     */
    List<InvariantEvaluationResultDTO> evaluatePostInvariants(
            Long tenantId, String commandCode, String modelCode,
            Map<String, Object> payload, String recordId, String currentState);

    /**
     * Evaluate ALWAYS invariants for periodic system checks.
     * Used by InvariantAlarmWorker.
     *
     * @param tenantId  tenant to check
     * @param modelCode model to scan
     */
    void evaluateAlwaysInvariants(Long tenantId, String modelCode);
}
