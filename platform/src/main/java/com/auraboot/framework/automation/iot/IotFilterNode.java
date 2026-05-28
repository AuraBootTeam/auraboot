package com.auraboot.framework.automation.iot;

import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.executor.ActionExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * IoT rule node — narrows execution to telemetry the rule actually cares about.
 *
 * <p>Filters in priority order:
 * <ol>
 *   <li>{@code productIds} / {@code deviceIds} membership (string equality)</li>
 *   <li>{@code tenantIds} membership (long equality)</li>
 *   <li>{@code predicate} SpEL expression evaluated against the process
 *       variables (must yield {@code Boolean.TRUE})</li>
 * </ol>
 *
 * <p>Match → no-op (downstream nodes proceed). No match → sets
 * {@link IotRuleContextKeys#DROPPED} on the process variables; the
 * downstream {@code exclusiveGateway} in the rule definition is expected to
 * route to {@code endEvent} when {@code _iot_dropped == true}.
 *
 * <p>Action type code: {@code iot_filter}.
 */
@Slf4j
@Component
public class IotFilterNode implements ActionExecutor {

    public static final String TYPE = "iot_filter";

    private static final ExpressionParser SPEL = new SpelExpressionParser();

    @Override
    public boolean supports(String actionType) {
        return TYPE.equals(actionType);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(AutomationAction action, Map<String, Object> context) {
        Map<String, Object> config = action.getConfig() != null ? action.getConfig() : Map.of();

        Set<String> productIds = asStringSet(config.get("productIds"));
        Set<String> deviceIds = asStringSet(config.get("deviceIds"));
        Set<String> tenantIds = asStringSet(config.get("tenantIds"));
        String predicate = (String) config.get("predicate");

        String deviceId = stringOrNull(context.get(IotRuleContextKeys.DEVICE_ID));
        String productId = stringOrNull(context.get(IotRuleContextKeys.PRODUCT_ID));
        String tenantId = stringOrNull(context.get(IotRuleContextKeys.TENANT_ID));

        String dropReason = null;
        if (!productIds.isEmpty() && (productId == null || !productIds.contains(productId))) {
            dropReason = "product " + productId + " not in scope " + productIds;
        } else if (!deviceIds.isEmpty() && (deviceId == null || !deviceIds.contains(deviceId))) {
            dropReason = "device " + deviceId + " not in scope " + deviceIds;
        } else if (!tenantIds.isEmpty() && (tenantId == null || !tenantIds.contains(tenantId))) {
            dropReason = "tenant " + tenantId + " not in scope " + tenantIds;
        } else if (predicate != null && !predicate.isBlank()) {
            Boolean match = evaluatePredicate(predicate, context);
            if (!Boolean.TRUE.equals(match)) {
                dropReason = "predicate '" + predicate + "' did not match";
            }
        }

        if (dropReason != null) {
            log.debug("IotFilterNode drop: {}", dropReason);
            context.put(IotRuleContextKeys.DROPPED, Boolean.TRUE);
            context.put(IotRuleContextKeys.DROP_REASON, dropReason);
            return Map.of("matched", false, "reason", dropReason);
        }
        // Explicitly clear any prior drop state so the gateway re-routes correctly
        // if the rule is re-executed against new telemetry within the same context.
        context.put(IotRuleContextKeys.DROPPED, Boolean.FALSE);
        return Map.of("matched", true);
    }

    private Boolean evaluatePredicate(String predicate, Map<String, Object> ctx) {
        SimpleEvaluationContext ec = SimpleEvaluationContext.forReadOnlyDataBinding()
                .withRootObject(ctx)
                .build();
        // Bind variables so authors can write `#temperature > 80` instead of
        // navigating into the root map.
        ctx.forEach((k, v) -> ec.setVariable(k, v));
        Expression expr = SPEL.parseExpression(predicate);
        Object value = expr.getValue(ec);
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.FALSE;
    }

    private static Set<String> asStringSet(Object value) {
        if (value == null) {
            return Set.of();
        }
        if (value instanceof Collection<?> coll) {
            return coll.stream().filter(java.util.Objects::nonNull)
                    .map(Object::toString).collect(Collectors.toUnmodifiableSet());
        }
        if (value.getClass().isArray()) {
            return java.util.Arrays.stream((Object[]) value)
                    .filter(java.util.Objects::nonNull)
                    .map(Object::toString).collect(Collectors.toUnmodifiableSet());
        }
        return Set.of(value.toString());
    }

    private static String stringOrNull(Object v) {
        return v == null ? null : v.toString();
    }
}
