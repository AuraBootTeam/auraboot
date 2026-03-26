package com.auraboot.framework.bpm.service;

import com.alibaba.smart.framework.engine.SmartEngine;
import com.alibaba.smart.framework.engine.constant.RequestMapSpecialKeyConstant;
import com.alibaba.smart.framework.engine.model.instance.ExecutionInstance;
import com.alibaba.smart.framework.engine.model.instance.InstanceStatus;
import com.alibaba.smart.framework.engine.model.instance.ProcessInstance;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.dto.*;
import com.auraboot.framework.bpm.enums.ExecutionState;
import com.auraboot.framework.bpm.enums.StorageMode;
import com.auraboot.framework.plugin.entity.BpmProcessDefinition;
import com.alibaba.smart.framework.engine.persister.custom.session.PersisterSession;
import com.alibaba.smart.framework.engine.storage.StorageModeHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Process orchestration service.
 * Manages the full lifecycle of orchestrated process executions
 * (start, pause, resume, retry, cancel) with automatic storage mode selection.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessOrchestrationService {

    private final SmartEngine smartEngine;
    private final ProcessEngineService processEngineService;
    private final ProcessDeploymentService deploymentService;
    private final ExecutionLogService executionLogService;

    private static final String EXTENSION_KEY_EXECUTION_MODE = "executionMode";

    // ==================== Execution Management ====================

    /**
     * Start a new process execution with automatic storage mode selection.
     */
    public ExecutionResult startExecution(String processKey, String businessKey, Map<String, Object> payload) {
        log.info("Starting orchestrated execution: processKey={}, businessKey={}", processKey, businessKey);

        BpmProcessDefinition definition = deploymentService.getByProcessKey(processKey);
        if (definition == null) {
            throw new IllegalArgumentException("Process definition not found: " + processKey);
        }
        if (!definition.isDeployed()) {
            throw new IllegalStateException("Process is not deployed: " + processKey);
        }

        return executeWithStorageMode(processKey, () -> {
            Map<String, Object> variables = payload != null ? new HashMap<>(payload) : new HashMap<>();

            ProcessInstance instance = processEngineService.startProcess(
                    definition.getProcessKey(), businessKey, variables);

            String executionId = instance.getInstanceId();

            // Log the execution start
            executionLogService.logStateChange(executionId, null,
                    ExecutionState.RUNNING.name(), "Execution started");

            log.info("Orchestrated execution started: executionId={}, processKey={}",
                    executionId, processKey);

            return new ExecutionResult(executionId, processKey,
                    ExecutionState.RUNNING.name(), Instant.now());
        });
    }

    /**
     * Get the current execution status.
     */
    public ExecutionStatusDTO getExecutionStatus(String executionId) {
        ProcessInstance instance = processEngineService.getProcessInstance(executionId);
        if (instance == null) {
            return null;
        }

        String processKey = instance.getProcessDefinitionId();
        String state = resolveState(instance);

        // Get current active node
        String currentNodeId = null;
        try {
            var statusDTO = processEngineService.getProcessInstanceStatus(executionId);
            if (statusDTO != null && !statusDTO.currentNodes().isEmpty()) {
                currentNodeId = statusDTO.currentNodes().getFirst().nodeId();
            }
        } catch (Exception e) {
            log.debug("Could not resolve current node for execution: {}", executionId, e);
        }

        List<ExecutionLogEntry> recentEvents = executionLogService.getTimeline(executionId);
        // Return last 10 events
        if (recentEvents.size() > 10) {
            recentEvents = recentEvents.subList(recentEvents.size() - 10, recentEvents.size());
        }

        return new ExecutionStatusDTO(
                executionId, processKey, state, currentNodeId,
                Map.of(), recentEvents, Instant.now()
        );
    }

    /**
     * Pause execution at the current node.
     */
    public void pauseExecution(String executionId, String reason) {
        log.info("Pausing execution: executionId={}, reason={}", executionId, reason);

        ProcessInstance instance = processEngineService.getProcessInstance(executionId);
        if (instance == null) {
            throw new IllegalArgumentException("Execution not found: " + executionId);
        }

        String processKey = instance.getProcessDefinitionId();
        executeWithStorageMode(processKey, () -> {
            processEngineService.suspendProcessInstance(executionId, Map.of());

            executionLogService.logStateChange(executionId,
                    ExecutionState.RUNNING.name(), ExecutionState.PAUSED.name(), reason);

            log.info("Execution paused: executionId={}", executionId);
            return null;
        });
    }

    /**
     * Resume a paused execution.
     */
    public void resumeExecution(String executionId) {
        log.info("Resuming execution: executionId={}", executionId);

        ProcessInstance instance = processEngineService.getProcessInstance(executionId);
        if (instance == null) {
            throw new IllegalArgumentException("Execution not found: " + executionId);
        }

        String processKey = instance.getProcessDefinitionId();
        String userId = getCurrentUserId();

        executeWithStorageMode(processKey, () -> {
            processEngineService.resumeProcessInstance(executionId, userId);

            executionLogService.logStateChange(executionId,
                    ExecutionState.PAUSED.name(), ExecutionState.RUNNING.name(), "Resumed by user");

            log.info("Execution resumed: executionId={}", executionId);
            return null;
        });
    }

    /**
     * Cancel an execution.
     * In STRICT control mode, manual cancellation is not allowed.
     */
    public void cancelExecution(String executionId, String reason) {
        if (processEngineService.isStrictMode(executionId)) {
            throw new IllegalStateException("Manual cancellation is not allowed in STRICT control mode");
        }

        log.info("Cancelling execution: executionId={}, reason={}", executionId, reason);

        ProcessInstance instance = processEngineService.getProcessInstance(executionId);
        if (instance == null) {
            throw new IllegalArgumentException("Execution not found: " + executionId);
        }

        String processKey = instance.getProcessDefinitionId();
        String userId = getCurrentUserId();

        executeWithStorageMode(processKey, () -> {
            processEngineService.terminateProcessInstance(executionId, userId, reason);

            executionLogService.logStateChange(executionId,
                    ExecutionState.RUNNING.name(), ExecutionState.CANCELLED.name(), reason);

            log.info("Execution cancelled: executionId={}", executionId);
            return null;
        });
    }

    // ==================== Node-Level Control ====================

    /**
     * Retry execution from a specific node.
     * Uses SmartEngine jumpTo() to re-create an execution at the target node.
     */
    public void retryFromNode(String executionId, String nodeId, Map<String, Object> overrideVariables) {
        if (processEngineService.isStrictMode(executionId)) {
            throw new IllegalStateException("Retry from node is not allowed in STRICT control mode");
        }

        log.info("Retrying from node: executionId={}, nodeId={}", executionId, nodeId);

        String tenantId = MetaContext.getCurrentTenantIdAsString();

        ProcessInstance instance = processEngineService.getProcessInstance(executionId);
        if (instance == null) {
            throw new IllegalArgumentException("Execution not found: " + executionId);
        }

        Map<String, Object> retryInput = new HashMap<>();
        retryInput.put("retryNodeId", nodeId);
        if (overrideVariables != null) {
            retryInput.put("overrideVariables", overrideVariables);
        }
        executionLogService.logNodeStart(executionId, nodeId, "retry", retryInput);

        String processKey = instance.getProcessDefinitionId();
        executeWithStorageMode(processKey, () -> {
            smartEngine.getExecutionCommandService().jumpTo(
                    executionId,
                    instance.getProcessDefinitionId(),
                    instance.getProcessDefinitionVersion(),
                    InstanceStatus.running,
                    nodeId,
                    tenantId
            );

            executionLogService.logStateChange(executionId, null, ExecutionState.RUNNING.name(),
                    "Retried from node: " + nodeId);

            log.info("Retry from node completed: executionId={}, nodeId={}", executionId, nodeId);
            return null;
        });
    }

    /**
     * Skip a failed node and continue execution with provided output variables.
     * Signals the active execution on the node to advance the process past it.
     */
    public void skipNode(String executionId, String nodeId, Map<String, Object> outputVariables) {
        if (processEngineService.isStrictMode(executionId)) {
            throw new IllegalStateException("Skip node is not allowed in STRICT control mode");
        }

        log.info("Skipping node: executionId={}, nodeId={}", executionId, nodeId);

        String tenantId = MetaContext.getCurrentTenantIdAsString();

        ProcessInstance instance = processEngineService.getProcessInstance(executionId);
        if (instance == null) {
            throw new IllegalArgumentException("Execution not found: " + executionId);
        }

        String processKey = instance.getProcessDefinitionId();
        executeWithStorageMode(processKey, () -> {
            List<ExecutionInstance> activeExecutions = smartEngine.getExecutionQueryService()
                    .findActiveExecutionList(executionId, tenantId);

            ExecutionInstance targetExecution = null;
            if (activeExecutions != null) {
                for (ExecutionInstance exec : activeExecutions) {
                    if (nodeId.equals(exec.getProcessDefinitionActivityId())) {
                        targetExecution = exec;
                        break;
                    }
                }
            }

            if (targetExecution == null) {
                throw new IllegalArgumentException("No active execution found for node: " + nodeId);
            }

            Map<String, Object> signalVars = outputVariables != null ? new HashMap<>(outputVariables) : new HashMap<>();
            signalVars.put("_skipped", true);
            signalVars.put(RequestMapSpecialKeyConstant.TENANT_ID, tenantId);
            smartEngine.getExecutionCommandService().signal(targetExecution.getInstanceId(), signalVars);

            Map<String, Object> skipOutput = new HashMap<>();
            skipOutput.put("skipped", true);
            if (outputVariables != null) {
                skipOutput.putAll(outputVariables);
            }
            executionLogService.logNodeComplete(executionId, nodeId, skipOutput, 0);

            log.info("Node skipped: executionId={}, nodeId={}", executionId, nodeId);
            return null;
        });
    }

    /**
     * Insert a manual checkpoint after a specific node.
     */
    public void insertManualCheckpoint(String executionId, String afterNodeId, String assignee) {
        log.info("Inserting manual checkpoint: executionId={}, afterNodeId={}, assignee={}",
                executionId, afterNodeId, assignee);

        Map<String, Object> checkpointData = Map.of(
                "afterNodeId", afterNodeId,
                "assignee", assignee != null ? assignee : "unassigned",
                "type", "manual_checkpoint"
        );
        executionLogService.logNodeStart(executionId, "checkpoint_" + afterNodeId, "manualCheckpoint", checkpointData);

        // SmartEngine does not support dynamic modification of a deployed process definition graph
        // at runtime. The available ExecutionCommandService APIs (jumpTo, signal) can only move
        // execution to existing nodes — they cannot insert new UserTask nodes into the BPMN model.
        //
        // Current behavior: The checkpoint intent is recorded in the execution log (above) as an
        // application-level audit trail. Callers can query the log to check for pending checkpoints.
        // The engine itself does NOT enforce the checkpoint — execution continues normally.
        //
        // Possible future approaches to enforce checkpoints:
        //   1. Re-deploy a modified process definition with the checkpoint node and migrate the instance
        //   2. Use a "pause + manual signal" pattern: pause at afterNodeId, require manual signal to continue
        //   3. Maintain an application-level checkpoint table outside the engine with a pre-node interceptor
        log.warn("SmartEngine does not support runtime process graph modification. " +
                "Manual checkpoint recorded in execution log but not enforced at engine level. " +
                "executionId={}, afterNodeId={}, assignee={}", executionId, afterNodeId, assignee);
    }

    // ==================== Internal Methods ====================

    /**
     * Resolve the storage mode from the process definition's extension field.
     */
    private StorageMode resolveStorageMode(String processKey) {
        BpmProcessDefinition definition = deploymentService.getByProcessKey(processKey);
        if (definition == null || definition.getExtension() == null) {
            return StorageMode.DATABASE;
        }

        Object mode = definition.getExtension().get(EXTENSION_KEY_EXECUTION_MODE);
        if (mode == null) {
            return StorageMode.DATABASE;
        }

        try {
            return StorageMode.valueOf(mode.toString().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid executionMode '{}' for processKey={}, defaulting to DATABASE", mode, processKey);
            return StorageMode.DATABASE;
        }
    }

    /**
     * Execute an operation with the appropriate storage mode set.
     * Storage mode is resolved from the process definition metadata.
     *
     * <p>For CUSTOM mode, creates a PersisterSession for in-memory storage
     * and sets StorageModeHolder to route SmartEngine to custom persisters.
     */
    private <T> T executeWithStorageMode(String processKey, Supplier<T> operation) {
        StorageMode mode = resolveStorageMode(processKey);
        if (mode == StorageMode.DATABASE) {
            return operation.get();
        }

        log.info("Executing with storage mode: {} for processKey={}", mode, processKey);

        if (mode == StorageMode.CUSTOM) {
            PersisterSession.create();
        }
        StorageModeHolder.set(
                com.alibaba.smart.framework.engine.storage.StorageMode.valueOf(mode.name()));
        try {
            return operation.get();
        } finally {
            StorageModeHolder.clear();
            if (mode == StorageMode.CUSTOM) {
                PersisterSession.destroySession();
            }
        }
    }

    private String getCurrentUserId() {
        return com.auraboot.framework.bpm.util.BpmSecurityUtil.getCurrentUserId();
    }

    private String resolveState(ProcessInstance instance) {
        if (instance.isSuspend()) {
            return ExecutionState.PAUSED.name();
        }
        var status = instance.getStatus();
        if (status == null) {
            return ExecutionState.RUNNING.name();
        }
        return switch (status) {
            case running -> ExecutionState.RUNNING.name();
            case completed -> ExecutionState.COMPLETED.name();
            case aborted -> ExecutionState.CANCELLED.name();
            case suspended -> ExecutionState.PAUSED.name();
        };
    }
}
