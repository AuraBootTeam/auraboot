package com.auraboot.framework.automation.iot;

import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.executor.ActionExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * IoT rule node — derives new context variables from existing ones using SpEL.
 *
 * <p>Config shape ({@code assignments[]}):
 * <pre>{@code
 *   { "assignments": [
 *       { "target": "tempC",       "expression": "(#temperatureF - 32) * 5 / 9" },
 *       { "target": "isHighTemp",  "expression": "#tempC > 80" }
 *     ]
 *   }
 * }</pre>
 *
 * <p>Each assignment is evaluated in order so later expressions can reference
 * earlier targets (the example above derives {@code tempC} then uses it). All
 * variables become both root-bean readable ({@code context.get("tempC")}) and
 * SpEL-variable readable ({@code #tempC}).
 *
 * <p>Honors {@link IotRuleContextKeys#DROPPED}: dropped runs short-circuit.
 *
 * <p>Action type code: {@code iot_transformation}.
 */
@Slf4j
@Component
public class IotTransformationNode implements ActionExecutor {

    public static final String TYPE = "iot_transformation";

    private static final ExpressionParser SPEL = new SpelExpressionParser();

    @Override
    public boolean supports(String actionType) {
        return TYPE.equals(actionType);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(AutomationAction action, Map<String, Object> context) {
        if (Boolean.TRUE.equals(context.get(IotRuleContextKeys.DROPPED))) {
            return Map.of("transformed", 0, "skipped", true);
        }

        Map<String, Object> config = action.getConfig() != null ? action.getConfig() : Map.of();
        Object asObj = config.get("assignments");
        if (!(asObj instanceof List<?> rawList) || rawList.isEmpty()) {
            return Map.of("transformed", 0);
        }

        Map<String, Object> produced = new HashMap<>();
        int count = 0;
        for (Object item : rawList) {
            if (!(item instanceof Map<?, ?> rawMap)) continue;
            Map<String, Object> assignment = (Map<String, Object>) rawMap;
            String target = (String) assignment.get("target");
            String expression = (String) assignment.get("expression");
            if (target == null || target.isBlank() || expression == null || expression.isBlank()) {
                throw new IllegalArgumentException(
                        "iot_transformation: each assignment requires non-blank target + expression");
            }
            Object value = evaluate(expression, context);
            context.put(target, value);
            produced.put(target, value);
            count++;
        }
        log.debug("IotTransformationNode: applied {} assignment(s) -> {}", count, produced.keySet());
        return Map.of("transformed", count, "values", produced);
    }

    private Object evaluate(String expression, Map<String, Object> ctx) {
        SimpleEvaluationContext ec = SimpleEvaluationContext.forReadOnlyDataBinding()
                .withRootObject(ctx)
                .build();
        ctx.forEach((k, v) -> ec.setVariable(k, v));
        Expression expr = SPEL.parseExpression(expression);
        return expr.getValue(ec);
    }
}
