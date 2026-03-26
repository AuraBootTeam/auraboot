package com.auraboot.framework.bpm.chain.saga;

import com.auraboot.framework.bpm.chain.CommandChainDefinition;
import com.auraboot.framework.bpm.chain.CommandChainDefinition.ChainMode;
import com.auraboot.framework.bpm.chain.CommandChainResult;
import com.auraboot.framework.bpm.service.ExecutionLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SAGA mode executor — each step runs in its own transaction.
 * On failure, completed steps are compensated in reverse order.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SagaExecutor {

    private final SagaStateManager stateManager;
    private final SagaStepRunner stepRunner;
    private final SagaCompensator compensator;
    private final ExecutionLogService executionLogService;

    public CommandChainResult execute(CommandChainDefinition chain, String businessKey,
                                      Map<String, Object> payload) {
        long startTime = System.currentTimeMillis();

        // 1. Create execution record
        SagaExecution saga = stateManager.createExecution(chain, businessKey, payload);
        List<SagaStep> steps = stateManager.createSteps(saga, chain);

        log.info("Starting saga {} with {} steps for business key '{}'",
                saga.getId(), steps.size(), businessKey);

        // 2. Build process variables
        Map<String, Object> processVars = new HashMap<>(payload);
        processVars.put("_chain_mode", "saga");
        processVars.put("_chain_execution_id", saga.getId());
        processVars.put("_chain_business_key", businessKey);

        // 3. Execute steps sequentially
        return executeSteps(saga, steps, processVars, 0, startTime, chain);
    }

    public CommandChainResult retryFromFailed(String sagaId) {
        long startTime = System.currentTimeMillis();

        SagaExecution saga = stateManager.getSagaExecution(sagaId);
        if (saga == null) {
            throw new IllegalArgumentException("Saga not found: " + sagaId);
        }
        if (!SagaStatus.FAILED.name().equals(saga.getStatus())) {
            throw new IllegalStateException("Saga is not in FAILED status: " + saga.getStatus());
        }

        SagaStep failedStep = stateManager.getFailedStep(sagaId);
        if (failedStep == null) {
            throw new IllegalStateException("No failed step found in saga: " + sagaId);
        }

        List<SagaStep> steps = stateManager.getSteps(sagaId);

        // Reset saga to RUNNING
        stateManager.markSagaRunning(saga);

        // Rebuild process vars from payload + completed step outputs
        Map<String, Object> processVars = new HashMap<>(saga.getPayload() != null ? saga.getPayload() : Map.of());
        processVars.put("_chain_mode", "saga");
        processVars.put("_chain_execution_id", sagaId);
        processVars.put("_chain_business_key", saga.getBusinessKey());

        for (SagaStep step : steps) {
            if (SagaStatus.COMPLETED.name().equals(step.getStatus()) && step.getOutputData() != null) {
                processVars.put("_step_" + step.getNodeId() + "_result", step.getOutputData());
                if (step.getRecordId() != null) {
                    processVars.put("_step_" + step.getNodeId() + "_recordId", step.getRecordId());
                }
                processVars.put("_step_" + step.getNodeId() + "_success", true);
            }
        }

        // Increment retry count on failed step
        failedStep.setRetryCount(failedStep.getRetryCount() + 1);
        failedStep.setStatus(SagaStatus.PENDING.name());
        failedStep.setErrorMessage(null);
        stateManager.updateStepOutput(failedStep);

        int startFromIndex = steps.indexOf(failedStep);

        // Use null chain since we don't need it for sequential execution
        return executeSteps(saga, steps, processVars, startFromIndex, startTime, null);
    }

    private CommandChainResult executeSteps(SagaExecution saga, List<SagaStep> steps,
                                             Map<String, Object> processVars, int startFromIndex,
                                             long startTime, CommandChainDefinition chain) {
        for (int i = startFromIndex; i < steps.size(); i++) {
            SagaStep step = steps.get(i);
            if (SagaStatus.COMPLETED.name().equals(step.getStatus())) {
                continue; // Skip already completed steps (retry scenario)
            }

            try {
                stepRunner.executeStep(step, processVars);
                stateManager.markStepCompleted(step);
                stateManager.updateProgress(saga, step.getNodeId());

                executionLogService.logNodeComplete(saga.getId(), step.getNodeId(),
                        Map.of("commandCode", step.getCommandCode(), "success", true),
                        System.currentTimeMillis() - startTime);

                log.info("Saga {} step {}/{} completed: {}",
                        saga.getId(), i + 1, steps.size(), step.getCommandCode());
            } catch (Exception e) {
                log.error("Saga {} step {} failed: {}",
                        saga.getId(), step.getNodeId(), e.getMessage(), e);

                stateManager.markStepFailed(step, e.getMessage());
                stateManager.markSagaFailed(saga, step.getNodeId(), e.getMessage());

                executionLogService.logNodeFailure(saga.getId(), step.getNodeId(),
                        e, Map.of("commandCode", step.getCommandCode()));

                // Compensate completed steps
                compensator.compensate(saga, steps, processVars);

                long durationMs = System.currentTimeMillis() - startTime;
                return CommandChainResult.builder()
                        .success(false)
                        .chainExecutionId(saga.getId())
                        .sagaExecutionId(saga.getId())
                        .processKey(saga.getChainCode())
                        .businessKey(saga.getBusinessKey())
                        .chainMode(ChainMode.SAGA)
                        .status(saga.getStatus())
                        .failedNodeId(step.getNodeId())
                        .failedCommandCode(step.getCommandCode())
                        .errorMessage(e.getMessage())
                        .durationMs(durationMs)
                        .build();
            }
        }

        // All steps completed
        stateManager.markSagaCompleted(saga);
        long durationMs = System.currentTimeMillis() - startTime;

        log.info("Saga {} completed successfully in {}ms", saga.getId(), durationMs);

        return CommandChainResult.builder()
                .success(true)
                .chainExecutionId(saga.getId())
                .sagaExecutionId(saga.getId())
                .processKey(saga.getChainCode())
                .businessKey(saga.getBusinessKey())
                .chainMode(ChainMode.SAGA)
                .status(SagaStatus.COMPLETED.name())
                .durationMs(durationMs)
                .build();
    }
}
