package com.auraboot.framework.bpm.chain;

import com.auraboot.smart.framework.engine.context.ExecutionContext;
import com.auraboot.smart.framework.engine.delegation.JavaDelegation;
import com.auraboot.smart.framework.engine.model.assembly.IdBasedElement;
import com.auraboot.framework.bpm.service.ExecutionLogService;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.service.CommandExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * SmartEngine ServiceTask → AuraBoot Command bridge.
 *
 * <p>This is the critical bridge class that enables SmartEngine BPMN processes
 * to execute AuraBoot Commands through the full 16-phase pipeline.
 *
 * <p>When SmartEngine encounters a ServiceTask with {@code smart:class="commandServiceTaskDelegate"},
 * it resolves this bean from the Spring context and calls {@link #execute(ExecutionContext)}.
 *
 * <h3>How it works:</h3>
 * <ol>
 *   <li>Read chain node configuration from process variable {@code _chain_nodes} (Map&lt;nodeId, nodeConfig&gt;)</li>
 *   <li>Resolve SpEL expressions in params against current process variables</li>
 *   <li>Build {@link CommandExecuteRequest} and call {@link CommandExecutor#execute}</li>
 *   <li>Write results back to process variables for downstream steps</li>
 *   <li>On failure, throw {@link CommandChainStepException} (which causes @Transactional rollback in LOCAL_TX mode)</li>
 * </ol>
 *
 * @author AuraBoot Team
 * @since 3.0.0
 */
@Slf4j
@Component("commandServiceTaskDelegate")
@RequiredArgsConstructor
public class CommandServiceTaskDelegate implements JavaDelegation {

    private final CommandExecutor commandExecutor;
    private final ExecutionLogService executionLogService;

    private final ExpressionParser spelParser = new SpelExpressionParser();

    @Override
    public void execute(ExecutionContext executionContext) {
        Map<String, Object> processVars = executionContext.getRequest();
        if (processVars == null) {
            processVars = new HashMap<>();
        }

        // 1. Determine current activity ID
        String activityId = resolveActivityId(executionContext);
        String executionId = resolveExecutionId(executionContext);

        // 2. Load chain node configuration for this activity
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> chainNodes =
                (Map<String, Map<String, Object>>) processVars.get("_chain_nodes");

        if (chainNodes == null) {
            throw new CommandChainStepException(activityId, "unknown",
                    "_chain_nodes not found in process variables. Was this process started via CommandChainService?");
        }

        Map<String, Object> nodeConfig = chainNodes.get(activityId);
        if (nodeConfig == null) {
            throw new CommandChainStepException(activityId, "unknown",
                    "No configuration found for node '" + activityId + "' in _chain_nodes");
        }

        String commandCode = (String) nodeConfig.get("commandCode");
        String operationType = (String) nodeConfig.get("operationType");
        String onFail = (String) nodeConfig.getOrDefault("onFail", "abort");
        String conditionExpr = (String) nodeConfig.get("condition");

        log.info("Chain step executing: activityId={}, commandCode={}, operationType={}",
                activityId, commandCode, operationType);

        // 3. Evaluate condition (skip if false)
        if (conditionExpr != null && !conditionExpr.isBlank()) {
            Boolean conditionResult = evaluateCondition(conditionExpr, processVars);
            if (!Boolean.TRUE.equals(conditionResult)) {
                log.info("Chain step skipped (condition=false): activityId={}, commandCode={}", activityId, commandCode);
                processVars.put("_step_" + activityId + "_skipped", true);
                return;
            }
        }

        // 4. Resolve parameters from SpEL expressions
        @SuppressWarnings("unchecked")
        Map<String, Object> paramTemplate = (Map<String, Object>) nodeConfig.get("params");
        Map<String, Object> resolvedParams = resolveExpressions(paramTemplate, processVars);

        // 5. Resolve targetRecordId if present
        String targetRecordId = null;
        String targetRecordIdExpr = (String) nodeConfig.get("targetRecordId");
        if (targetRecordIdExpr != null && !targetRecordIdExpr.isBlank()) {
            Object resolved = resolveExpression(targetRecordIdExpr, processVars);
            targetRecordId = resolved != null ? resolved.toString() : null;
        }

        // 6. Log node start
        executionLogService.logNodeStart(executionId, activityId, "serviceTask",
                Map.of("commandCode", commandCode, "operationType", String.valueOf(operationType),
                        "params", resolvedParams));

        long startTime = System.currentTimeMillis();
        try {
            // 7. Build and execute the command
            CommandExecuteRequest request = new CommandExecuteRequest();
            request.setOperationType(operationType);
            request.setPayload(resolvedParams);
            request.setTargetRecordId(targetRecordId);

            CommandExecuteResult result = commandExecutor.execute(commandCode, request);

            long durationMs = System.currentTimeMillis() - startTime;

            // If execute() returned, the command succeeded (failures throw exceptions)
            // 8. Write results back to process variables for downstream steps
            if (result.getData() != null) {
                processVars.put("_step_" + activityId + "_result", result.getData());
            }
            // Extract recordId from result data if present
            if (result.getData() != null && result.getData().containsKey("recordId")) {
                processVars.put("_step_" + activityId + "_recordId",
                        result.getData().get("recordId"));
            }
            processVars.put("_step_" + activityId + "_success", true);

            executionLogService.logNodeComplete(executionId, activityId,
                    Map.of("commandCode", commandCode, "success", true,
                            "phaseReached", String.valueOf(result.getPhaseReached())),
                    durationMs);

            log.info("Chain step completed: activityId={}, commandCode={}, durationMs={}",
                    activityId, commandCode, durationMs);
        } catch (CommandChainStepException e) {
            throw e; // Re-throw chain exceptions
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;
            executionLogService.logNodeFailure(executionId, activityId, e,
                    Map.of("commandCode", commandCode));
            // handleFailure throws CommandChainStepException for onFail=abort;
            // for skip_and_warn it records the skip and returns normally so the
            // process can continue to the next node without propagating the error.
            handleFailure(activityId, commandCode, e.getMessage(), onFail,
                    executionId, durationMs, processVars);
        }
    }

    // ==================== Internal Methods ====================

    private void handleFailure(String activityId, String commandCode, String errorMessage,
                               String onFail, String executionId, long durationMs,
                               Map<String, Object> processVars) {
        processVars.put("_step_" + activityId + "_success", false);
        processVars.put("_step_" + activityId + "_error", errorMessage);

        executionLogService.logNodeComplete(executionId, activityId,
                Map.of("commandCode", commandCode, "success", false, "error", String.valueOf(errorMessage)),
                durationMs);

        if ("skip_and_warn".equals(onFail)) {
            log.warn("Chain step failed but skipped (SKIP_AND_WARN): activityId={}, commandCode={}, error={}",
                    activityId, commandCode, errorMessage);
            processVars.put("_step_" + activityId + "_skipped", true);
        } else {
            // ABORT: throw exception to trigger rollback
            throw new CommandChainStepException(activityId, commandCode, errorMessage);
        }
    }

    /**
     * Resolve SpEL expressions in parameter template values.
     * Supports: "${variableName}", "${_step_nodeId_result.field}", plain values.
     */
    private Map<String, Object> resolveExpressions(Map<String, Object> paramTemplate,
                                                    Map<String, Object> processVars) {
        if (paramTemplate == null || paramTemplate.isEmpty()) {
            return new HashMap<>();
        }

        Map<String, Object> resolved = new HashMap<>();
        for (Map.Entry<String, Object> entry : paramTemplate.entrySet()) {
            resolved.put(entry.getKey(), resolveValue(entry.getValue(), processVars));
        }
        return resolved;
    }

    /**
     * Resolve a single value. If it's a String containing "${...}", evaluate as SpEL.
     * If it's a Map, resolve recursively. Otherwise return as-is.
     */
    @SuppressWarnings("unchecked")
    private Object resolveValue(Object value, Map<String, Object> processVars) {
        if (value instanceof String strValue) {
            if (strValue.startsWith("${") && strValue.endsWith("}")) {
                String expression = strValue.substring(2, strValue.length() - 1);
                return resolveExpression(expression, processVars);
            }
            return strValue;
        } else if (value instanceof Map) {
            return resolveExpressions((Map<String, Object>) value, processVars);
        }
        return value;
    }

    /**
     * Evaluate a SpEL expression against process variables.
     * Uses a sandboxed context (property reads only, no method invocation).
     */
    private Object resolveExpression(String expression, Map<String, Object> processVars) {
        try {
            EvaluationContext context = SimpleEvaluationContext
                    .forPropertyAccessors(new org.springframework.context.expression.MapAccessor())
                    .withRootObject(processVars)
                    .build();

            // Also make each variable directly accessible
            for (Map.Entry<String, Object> entry : processVars.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    context.setVariable(entry.getKey(), entry.getValue());
                }
            }

            return spelParser.parseExpression(expression).getValue(context);
        } catch (Exception e) {
            log.warn("Failed to resolve expression '{}': {}", expression, e.getMessage());
            return null;
        }
    }

    /**
     * Evaluate a condition expression as boolean.
     */
    private Boolean evaluateCondition(String expression, Map<String, Object> processVars) {
        try {
            Object result = resolveExpression(expression, processVars);
            if (result instanceof Boolean) {
                return (Boolean) result;
            }
            return result != null;
        } catch (Exception e) {
            log.warn("Failed to evaluate condition '{}': {}", expression, e.getMessage());
            return false;
        }
    }

    private String resolveActivityId(ExecutionContext context) {
        if (context.getBaseElement() instanceof IdBasedElement idBased) {
            return idBased.getId();
        }
        if (context.getActivityInstance() != null) {
            return context.getActivityInstance().getProcessDefinitionActivityId();
        }
        return "unknown";
    }

    private String resolveExecutionId(ExecutionContext context) {
        if (context.getExecutionInstance() != null) {
            return context.getExecutionInstance().getInstanceId();
        }
        if (context.getProcessInstance() != null) {
            return context.getProcessInstance().getInstanceId();
        }
        return "unknown";
    }
}
