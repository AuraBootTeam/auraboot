package com.auraboot.framework.bpm.chain;

import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.eventpolicy.executor.ActionExecutionException;
import com.auraboot.framework.eventpolicy.executor.ActionHandler;
import com.auraboot.framework.eventpolicy.model.ResolvedActionPlan;
import com.auraboot.framework.plugin.extension.ServiceTaskActionExtension;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * BPM serviceTask bridge for the shared Rule Center action catalog.
 */
@Component
@RequiredArgsConstructor
public class DecisionActionServiceTaskExtension implements ServiceTaskActionExtension {

    static final String PROP_PAYLOAD_JSON = "payloadJson";
    static final String PROP_TARGET = "target";
    static final String PROP_IDEMPOTENCY_KEY = "idempotencyKey";
    static final String PROP_RESULT_VAR = "resultVar";
    static final String PROP_ORDER = "order";

    private final ObjectProvider<ActionHandler> actionHandlers;
    private final ObjectMapper objectMapper;

    @Override
    public String getActionType() {
        return "decision-action";
    }

    @Override
    public boolean supports(String actionType) {
        return select(actionType).isPresent();
    }

    @Override
    public int getPriority() {
        return -100;
    }

    @Override
    public Object execute(ActionContext context) throws Exception {
        ActionHandler handler = select(context.actionType())
                .orElseThrow(() -> new IllegalArgumentException("No ActionHandler for BPM action " + context.actionType()));
        Map<String, String> properties = context.properties() == null ? Map.of() : context.properties();
        ResolvedActionPlan plan = new ResolvedActionPlan(
                value(properties, BpmServiceTaskConstants.ATTR_RULE_CODE, "bpm-service-task"),
                context.actionType(),
                value(properties, PROP_TARGET, null),
                intValue(properties.get(PROP_ORDER)),
                payload(properties),
                value(properties, PROP_IDEMPOTENCY_KEY, null));
        try {
            Object result = handler.executeWithResult(plan, decisionContext(context, properties));
            writeResultVariable(context, properties, result);
            return result;
        } catch (ActionExecutionException e) {
            Map<String, Object> failure = failurePayload(plan, e);
            writeResultVariable(context, properties, failure);
            throw e;
        }
    }

    private Optional<ActionHandler> select(String actionType) {
        if (actionType == null || actionType.isBlank()) {
            return Optional.empty();
        }
        return actionHandlers.stream()
                .filter(handler -> {
                    try {
                        return handler.supports(actionType);
                    } catch (Exception ignored) {
                        return false;
                    }
                })
                .findFirst();
    }

    private Map<String, Object> payload(Map<String, String> properties) throws Exception {
        String payloadJson = value(properties, PROP_PAYLOAD_JSON, null);
        if (payloadJson == null) {
            return Map.of();
        }
        return objectMapper.readValue(payloadJson, new TypeReference<>() {});
    }

    private DecisionContext decisionContext(ActionContext context, Map<String, String> properties) {
        Map<Scope, Object> scopes = new EnumMap<>(Scope.class);
        scopes.put(Scope.PROCESS, context.variables() == null ? Map.of() : new HashMap<>(context.variables()));
        scopes.put(Scope.META, Map.of(
                "consumerType", "BPM",
                "actionType", context.actionType()));
        scopes.put(Scope.EVENT, Map.of(
                "source", "bpm-service-task",
                "properties", new HashMap<>(properties)));
        return DecisionContext.of(scopes);
    }

    private void writeResultVariable(ActionContext context, Map<String, String> properties, Object result) {
        String resultVar = value(properties, PROP_RESULT_VAR, null);
        if (resultVar != null && context.variables() != null) {
            context.variables().put(resultVar, result);
        }
    }

    private static Map<String, Object> failurePayload(ResolvedActionPlan plan, ActionExecutionException error) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (error.resultPayload() != null) {
            result.putAll(error.resultPayload());
        }
        result.put("status", "FAILED");
        result.put("actionType", plan.type());
        result.put("ruleCode", plan.ruleCode());
        if (plan.idempotencyKey() != null && !plan.idempotencyKey().isBlank()) {
            result.put("idempotencyKey", plan.idempotencyKey());
        }
        result.put("error", error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName());
        return Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    private static String value(Map<String, String> properties, String key, String fallback) {
        String value = properties.get(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int intValue(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
