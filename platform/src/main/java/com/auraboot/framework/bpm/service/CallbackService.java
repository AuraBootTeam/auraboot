package com.auraboot.framework.bpm.service;

import com.alibaba.smart.framework.engine.SmartEngine;
import com.alibaba.smart.framework.engine.model.instance.ExecutionInstance;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.dto.CallbackResult;
import com.auraboot.framework.bpm.dto.PendingCallbackDTO;
import com.auraboot.framework.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Callback service for orchestrated processes.
 * Handles external callbacks for serviceTask/receiveTask nodes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallbackService {

    private final SmartEngine smartEngine;
    private final ExecutionLogService executionLogService;

    /**
     * Handle an external callback for a waiting node.
     */
    public void handleCallback(String executionId, String nodeId, CallbackResult result) {
        log.info("Handling callback: executionId={}, nodeId={}, success={}", executionId, nodeId, result.success());

        String tenantId = MetaContext.getCurrentTenantIdAsString();

        if (result.success()) {
            // Signal the engine to continue execution
            Map<String, Object> variables = new HashMap<>();
            if (result.data() != null) {
                variables.putAll(result.data());
            }

            try {
                smartEngine.getExecutionCommandService().signal(executionId, variables);
                executionLogService.logNodeComplete(executionId, nodeId, result.data(), 0);
                log.info("Callback processed successfully: executionId={}, nodeId={}", executionId, nodeId);
            } catch (Exception e) {
                executionLogService.logNodeFailure(executionId, nodeId, e, result.data());
                throw new BusinessException("Failed to process callback: " + e.getMessage(), e);
            }
        } else {
            // Record the failure
            RuntimeException error = new RuntimeException(
                    result.errorMessage() != null ? result.errorMessage() : "Callback reported failure");
            executionLogService.logNodeFailure(executionId, nodeId, error, result.data());
            log.warn("Callback reported failure: executionId={}, nodeId={}, error={}",
                    executionId, nodeId, result.errorMessage());
        }
    }

    /**
     * Get nodes waiting for callbacks in an execution.
     */
    public List<PendingCallbackDTO> getPendingCallbacks(String executionId) {
        String tenantId = MetaContext.getCurrentTenantIdAsString();

        List<ExecutionInstance> activeExecutions;
        try {
            activeExecutions = smartEngine.getExecutionQueryService()
                    .findActiveExecutionList(executionId, tenantId);
        } catch (NumberFormatException e) {
            log.debug("Invalid execution ID format: {}", executionId);
            return new ArrayList<>();
        }

        List<PendingCallbackDTO> pending = new ArrayList<>();
        if (activeExecutions != null) {
            for (ExecutionInstance exec : activeExecutions) {
                // receiveTask and serviceTask nodes that are active are waiting for signals
                pending.add(new PendingCallbackDTO(
                        executionId,
                        exec.getProcessDefinitionActivityId(),
                        exec.getProcessDefinitionIdAndVersion(),
                        Instant.now()
                ));
            }
        }

        return pending;
    }

    /**
     * Handle a timeout for a waiting node.
     */
    public void handleTimeout(String executionId, String nodeId) {
        log.info("Handling timeout: executionId={}, nodeId={}", executionId, nodeId);

        RuntimeException timeoutError = new RuntimeException("Callback timeout for node: " + nodeId);
        executionLogService.logNodeFailure(executionId, nodeId, timeoutError, Map.of("timeout", true));
    }
}
