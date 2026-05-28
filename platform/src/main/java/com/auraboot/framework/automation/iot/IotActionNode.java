package com.auraboot.framework.automation.iot;

import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.executor.ActionExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * IoT rule node — terminal step that publishes the rule outcome.
 *
 * <p>Config shape:
 * <pre>{@code
 *   { "kind": "alarm" | "command" | "record" | "workflow",
 *     "topic": "iot.alarm.v1",                 // optional, for kind=alarm/command
 *     "payload": {                              // literal map merged with SpEL expansions
 *       "deviceId": "${deviceId}",
 *       "metric": "temperature",
 *       "value": "${temperature}",
 *       "severity": "MAJOR"
 *     }
 *   }
 * }</pre>
 *
 * <p>{@code ${var}} tokens inside string payload values are replaced with the
 * corresponding process-variable values. Non-string values pass through.
 *
 * <p>Outcomes are appended to {@link IotRuleContextKeys#ACTION_OUTCOMES} on the
 * context (for traceability + downstream nodes) and emitted to every wired
 * {@link IotActionSink}. The spike registers an in-memory recording sink in
 * tests so we can assert on the envelopes without Kafka.
 *
 * <p>Honors {@link IotRuleContextKeys#DROPPED}: dropped runs do not emit.
 *
 * <p>Action type code: {@code iot_action}.
 */
@Slf4j
@Component
public class IotActionNode implements ActionExecutor {

    public static final String TYPE = "iot_action";

    private static final ExpressionParser SPEL = new SpelExpressionParser();

    private final List<IotActionSink> sinks;

    public IotActionNode(List<IotActionSink> sinks) {
        this.sinks = sinks;
    }

    @Override
    public boolean supports(String actionType) {
        return TYPE.equals(actionType);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(AutomationAction action, Map<String, Object> context) {
        if (Boolean.TRUE.equals(context.get(IotRuleContextKeys.DROPPED))) {
            return Map.of("emitted", false, "reason", "dropped upstream");
        }

        Map<String, Object> config = action.getConfig() != null ? action.getConfig() : Map.of();
        String kind = (String) config.getOrDefault("kind", "alarm");
        String topic = (String) config.get("topic");

        Object payloadObj = config.get("payload");
        Map<String, Object> payload = payloadObj instanceof Map
                ? expandTemplates((Map<String, Object>) payloadObj, context)
                : new HashMap<>();

        Map<String, Object> envelope = new HashMap<>();
        envelope.put("kind", kind);
        if (topic != null) {
            envelope.put("topic", topic);
        }
        Object deviceId = context.get(IotRuleContextKeys.DEVICE_ID);
        if (deviceId != null) envelope.put("deviceId", deviceId);
        Object tenantId = context.get(IotRuleContextKeys.TENANT_ID);
        if (tenantId != null) envelope.put("tenantId", tenantId);
        envelope.put("payload", payload);
        envelope.put("emittedAt", System.currentTimeMillis());

        Object existing = context.get(IotRuleContextKeys.ACTION_OUTCOMES);
        List<Object> outcomes = existing instanceof List
                ? new ArrayList<>((List<Object>) existing)
                : new ArrayList<>();
        outcomes.add(envelope);
        context.put(IotRuleContextKeys.ACTION_OUTCOMES, outcomes);

        for (IotActionSink sink : sinks) {
            sink.emit(kind, envelope);
        }
        log.info("IotActionNode emitted kind={} topic={} payloadKeys={}", kind, topic, payload.keySet());
        return Map.of("emitted", true, "kind", kind, "sinks", sinks.size());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> expandTemplates(Map<String, Object> template, Map<String, Object> ctx) {
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<String, Object> entry : template.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String str) {
                out.put(entry.getKey(), expandString(str, ctx));
            } else if (value instanceof Map<?, ?> nested) {
                out.put(entry.getKey(), expandTemplates((Map<String, Object>) nested, ctx));
            } else {
                out.put(entry.getKey(), value);
            }
        }
        return out;
    }

    /**
     * Expand a string template:
     * <ul>
     *   <li>{@code ${var}} — pure substitution against the context map</li>
     *   <li>{@code #{spel-expression}} — full SpEL evaluation</li>
     * </ul>
     * A bare value (no token) is returned verbatim.
     *
     * <p>SpEL note: the evaluator runs under {@link SimpleEvaluationContext#forReadOnlyDataBinding()},
     * which restricts attacker surface but also forbids arbitrary method calls. To read nested
     * map fields use the SpEL indexer ({@code #{#deviceMeta?.['site']}}) rather than
     * {@code .get('site')}.
     */
    private Object expandString(String template, Map<String, Object> ctx) {
        if (template == null) return null;
        // Pure single-token substitution preserves the value's original type
        // (so `${temperature}` keeps the Double, not its toString).
        if (template.startsWith("${") && template.endsWith("}") && template.indexOf('$', 2) < 0) {
            String key = template.substring(2, template.length() - 1).trim();
            return ctx.get(key);
        }
        if (template.startsWith("#{") && template.endsWith("}")) {
            String expr = template.substring(2, template.length() - 1);
            SimpleEvaluationContext ec = SimpleEvaluationContext.forReadOnlyDataBinding()
                    .withRootObject(ctx)
                    .build();
            ctx.forEach((k, v) -> ec.setVariable(k, v));
            Expression e = SPEL.parseExpression(expr);
            return e.getValue(ec);
        }
        // Mixed template — string interpolation of all ${var} occurrences.
        String result = template;
        for (Map.Entry<String, Object> e : ctx.entrySet()) {
            String token = "${" + e.getKey() + "}";
            if (result.contains(token)) {
                result = result.replace(token, e.getValue() == null ? "" : e.getValue().toString());
            }
        }
        return result;
    }
}
