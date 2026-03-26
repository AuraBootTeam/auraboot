package com.auraboot.framework.bpm.chain.saga;

import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.service.CommandExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * Executes a single saga step in its own REQUIRES_NEW transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SagaStepRunner {

    private final CommandExecutor commandExecutor;
    private final SagaStateManager stateManager;
    private final ExpressionParser spelParser = new SpelExpressionParser();

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void executeStep(SagaStep step, Map<String, Object> processVars) {
        stateManager.markStepRunning(step);

        // Resolve SpEL params
        Map<String, Object> resolvedParams = resolveExpressions(step.getInputParams(), processVars);

        // Resolve targetRecordId if present in params
        String targetRecordId = null;
        if (resolvedParams.containsKey("targetRecordId")) {
            Object tid = resolvedParams.remove("targetRecordId");
            targetRecordId = tid != null ? tid.toString() : null;
        }

        // Determine operationType from params or default to CREATE
        String operationType = "create";
        if (resolvedParams.containsKey("operationType")) {
            operationType = resolvedParams.remove("operationType").toString();
        }

        // Build and execute command
        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setOperationType(operationType);
        request.setPayload(resolvedParams);
        request.setTargetRecordId(targetRecordId);

        CommandExecuteResult result = commandExecutor.execute(step.getCommandCode(), request);

        // Store results
        step.setOutputData(result.getData() != null ? result.getData() : Map.of());
        if (result.getData() != null && result.getData().containsKey("recordId")) {
            step.setRecordId(result.getData().get("recordId").toString());
        }
        stateManager.updateStepOutput(step);

        // Put results into process vars for downstream steps
        processVars.put("_step_" + step.getNodeId() + "_result", result.getData());
        if (step.getRecordId() != null) {
            processVars.put("_step_" + step.getNodeId() + "_recordId", step.getRecordId());
        }
        processVars.put("_step_" + step.getNodeId() + "_success", true);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveExpressions(Map<String, Object> template,
                                                    Map<String, Object> processVars) {
        if (template == null || template.isEmpty()) return new HashMap<>();
        Map<String, Object> resolved = new HashMap<>();
        for (Map.Entry<String, Object> entry : template.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String strValue && strValue.startsWith("${") && strValue.endsWith("}")) {
                String expr = strValue.substring(2, strValue.length() - 1);
                resolved.put(entry.getKey(), resolveExpression(expr, processVars));
            } else if (value instanceof Map) {
                resolved.put(entry.getKey(), resolveExpressions((Map<String, Object>) value, processVars));
            } else {
                resolved.put(entry.getKey(), value);
            }
        }
        return resolved;
    }

    private Object resolveExpression(String expression, Map<String, Object> processVars) {
        try {
            EvaluationContext context = SimpleEvaluationContext
                    .forPropertyAccessors(new org.springframework.context.expression.MapAccessor())
                    .withRootObject(processVars)
                    .build();
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
}
