package com.auraboot.framework.automation.bpm;

import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.executor.ActionExecutor;
import com.auraboot.smart.framework.engine.context.ExecutionContext;
import com.auraboot.smart.framework.engine.delegation.JavaDelegation;
import com.auraboot.smart.framework.engine.model.assembly.IdBasedElement;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bridge between a SmartEngine {@code serviceTask} and the existing automation
 * {@link ActionExecutor} chain.
 *
 * <p>Design (DDR-2026-05-23 Option B / T2): SmartEngine orchestrates the graph
 * (sequence / gateways / loops); the actual action work stays in the already-tested
 * {@code CompositeActionExecutor}. The compiler emits one generic serviceTask per
 * automation action with {@code smart:class="automationActionServiceTaskDelegate"};
 * each node's action spec ({@code type} + {@code config}) is passed at start time via
 * the {@link #ACTIONS_VAR} process variable, keyed by node id. This keeps
 * {@code JsonToBpmnConverter} unchanged and preserves full automation action semantics
 * (multi-recipient notification, multi-field / {@code ${var}} update, LLM, command, …).
 */
@Slf4j
@Component(AutomationActionServiceTaskDelegate.BEAN_NAME)
public class AutomationActionServiceTaskDelegate implements JavaDelegation {

    /** {@code smart:class} bean name the compiler emits on every action serviceTask. */
    public static final String BEAN_NAME = "automationActionServiceTaskDelegate";

    /** Process variable carrying per-node action specs: {@code nodeId -> {type, config}}. */
    public static final String ACTIONS_VAR = "_automation_actions";

    private final ActionExecutor actionExecutor;

    public AutomationActionServiceTaskDelegate(
            @Qualifier("compositeActionExecutor") ActionExecutor actionExecutor) {
        this.actionExecutor = actionExecutor;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void execute(ExecutionContext executionContext) {
        Map<String, Object> processVars = executionContext.getRequest();
        if (processVars == null) {
            processVars = new HashMap<>();
        }

        String nodeId = resolveNodeId(executionContext);

        Object actionsObj = processVars.get(ACTIONS_VAR);
        if (!(actionsObj instanceof Map<?, ?> actionsMap)) {
            throw new IllegalStateException(
                    "automation action task: missing '" + ACTIONS_VAR + "' process variable");
        }
        Object specObj = actionsMap.get(nodeId);
        if (!(specObj instanceof Map<?, ?>)) {
            throw new IllegalStateException(
                    "automation action task: no action spec for node '" + nodeId + "'");
        }
        Map<String, Object> spec = (Map<String, Object>) specObj;

        String type = String.valueOf(spec.get("type"));
        Object cfg = spec.get("config");
        Map<String, Object> config =
                cfg instanceof Map ? (Map<String, Object>) cfg : new HashMap<>();

        AutomationAction action = AutomationAction.builder()
                .type(type)
                .config(config)
                .build();

        // control-loop: this SmartEngine fork only expands multi-instance for userTask
        // (会签), not collection-driven serviceTasks, so a loop body iterates here —
        // once per collection element, with the element bound under itemVariable in an
        // isolated per-iteration context copy.
        Object loopObj = spec.get("loop");
        if (loopObj instanceof Map<?, ?> loopMap) {
            String collectionVar = String.valueOf(loopMap.get("collection"));
            String itemVariable = loopMap.get("itemVariable") != null
                    ? String.valueOf(loopMap.get("itemVariable")) : "item";
            List<Object> items = resolveCollection(collectionVar, processVars);
            log.info("AutomationActionDelegate: node={}, actionType={}, loop '{}' -> {} item(s)",
                    nodeId, type, collectionVar, items.size());
            for (Object element : items) {
                Map<String, Object> iterationContext = new HashMap<>(processVars);
                iterationContext.put(itemVariable, element);
                actionExecutor.execute(action, iterationContext);
            }
            return;
        }

        log.info("AutomationActionDelegate: node={}, actionType={}", nodeId, type);
        actionExecutor.execute(action, processVars);
    }

    /**
     * Resolve a loop collection reference to a list of elements. Accepts a bare process
     * variable name or {@code ${var}} template; the value may be a {@link java.util.Collection},
     * an array, or a comma-separated String. Missing / blank → empty (the loop is skipped).
     */
    private List<Object> resolveCollection(String expr, Map<String, Object> ctx) {
        if (expr == null || expr.isBlank()) {
            return List.of();
        }
        String varName = expr.trim();
        if (varName.startsWith("${") && varName.endsWith("}")) {
            varName = varName.substring(2, varName.length() - 1).trim();
        }
        Object value = ctx.get(varName);
        if (value == null) {
            return List.of();
        }
        if (value instanceof java.util.Collection<?> coll) {
            return new java.util.ArrayList<>(coll);
        }
        if (value.getClass().isArray()) {
            return java.util.Arrays.asList((Object[]) value);
        }
        String str = value.toString();
        if (str.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(str.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> (Object) s)
                .toList();
    }

    private String resolveNodeId(ExecutionContext executionContext) {
        if (executionContext.getBaseElement() instanceof IdBasedElement idBased) {
            return idBased.getId();
        }
        throw new IllegalStateException("automation action task: cannot resolve node id");
    }
}
