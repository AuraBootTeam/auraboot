package com.auraboot.framework.automation.executor.impl;

import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.executor.ActionExecutor;
import com.auraboot.framework.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Executor for control flow nodes: CONDITION, DELAY, LOOP.
 * <p>
 * - CONDITION: evaluates a SpEL expression, returns branching result
 * - DELAY: pauses execution for a configured duration
 * - LOOP: indicates loop metadata (actual loop is handled by the orchestrator)
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Slf4j
@Component
public class ControlNodeExecutor implements ActionExecutor {

    private final SpelExpressionParser spelParser = new SpelExpressionParser();

    @Override
    public Object execute(AutomationAction action, Map<String, Object> context) {
        String type = action.getType();
        Map<String, Object> config = action.getConfig() != null ? action.getConfig() : Map.of();

        return switch (type) {
            case "condition" -> executeCondition(config, context);
            case "delay" -> executeDelay(config);
            case "loop" -> executeLoop(config, context);
            default -> throw new UnsupportedOperationException("Unknown control type: " + type);
        };
    }

    @Override
    public boolean supports(String actionType) {
        return "condition".equals(actionType) || "delay".equals(actionType) || "loop".equals(actionType);
    }

    private Object executeCondition(Map<String, Object> config, Map<String, Object> context) {
        String expression = (String) config.get("expression");
        if (expression == null || expression.isBlank()) {
            log.warn("CONDITION action missing expression, defaulting to true");
            return Map.of("branch", "true", "result", true);
        }

        try {
            Expression expr = spelParser.parseExpression(expression);
            SimpleEvaluationContext evalContext = SimpleEvaluationContext.forReadOnlyDataBinding().build();
            for (Map.Entry<String, Object> entry : context.entrySet()) {
                evalContext.setVariable(entry.getKey(), entry.getValue());
            }
            Boolean result = expr.getValue(evalContext, Boolean.class);
            boolean outcome = Boolean.TRUE.equals(result);

            log.debug("CONDITION evaluated: expression='{}', result={}", expression, outcome);
            return Map.of("branch", String.valueOf(outcome), "result", outcome);
        } catch (Exception e) {
            log.warn("CONDITION evaluation failed: expression='{}', error={}", expression, e.getMessage());
            return Map.of("branch", "false", "result", false, "error", e.getMessage());
        }
    }

    private Object executeDelay(Map<String, Object> config) {
        int delayMs = config.containsKey("delayMs")
                ? ((Number) config.get("delayMs")).intValue() : 0;
        int delaySeconds = config.containsKey("delaySeconds")
                ? ((Number) config.get("delaySeconds")).intValue() : 0;

        long totalMs = delayMs + (delaySeconds * 1000L);
        // Cap at 5 minutes to prevent abuse
        totalMs = Math.min(totalMs, 300_000);

        if (totalMs > 0) {
            log.debug("DELAY: sleeping for {}ms", totalMs);
            try {
                Thread.sleep(totalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException("Delay interrupted", e);
            }
        }

        return Map.of("delayed", true, "durationMs", totalMs);
    }

    private Object executeLoop(Map<String, Object> config, Map<String, Object> context) {
        int maxIterations = config.containsKey("maxIterations")
                ? ((Number) config.get("maxIterations")).intValue() : 1;
        // Cap at 100 iterations
        maxIterations = Math.min(maxIterations, 100);

        log.debug("LOOP: maxIterations={}", maxIterations);

        // The loop node records metadata; actual iteration is handled at orchestration level
        return Map.of(
                "loopType", config.getOrDefault("loopType", "count"),
                "maxIterations", maxIterations,
                "executed", true
        );
    }
}
