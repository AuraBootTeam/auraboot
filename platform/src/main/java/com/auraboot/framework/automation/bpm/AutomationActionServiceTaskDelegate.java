package com.auraboot.framework.automation.bpm;

import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.entity.AutomationNodeExecution;
import com.auraboot.framework.automation.executor.ActionExecutor;
import com.auraboot.framework.automation.mapper.AutomationNodeExecutionMapper;
import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.smart.framework.engine.context.ExecutionContext;
import com.auraboot.smart.framework.engine.delegation.JavaDelegation;
import com.auraboot.smart.framework.engine.model.assembly.IdBasedElement;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
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
 *
 * <p>G5 — runtime status recording: when {@link #LOG_ID_VAR} is present in the process
 * variables, the delegate writes one {@link AutomationNodeExecution} row per node
 * entry (status=running) and updates it on exit (status=completed / failed). On
 * failure the row is updated <strong>before</strong> the original exception is rethrown
 * — exception propagation drives SmartEngine's failure semantics; the row is a
 * pure observability artefact (red line §8 — no swallow, no fallback).
 */
@Slf4j
@Component(AutomationActionServiceTaskDelegate.BEAN_NAME)
public class AutomationActionServiceTaskDelegate implements JavaDelegation {

    /** {@code smart:class} bean name the compiler emits on every action serviceTask. */
    public static final String BEAN_NAME = "automationActionServiceTaskDelegate";

    /** Process variable carrying per-node action specs: {@code nodeId -> {type, config}}. */
    public static final String ACTIONS_VAR = "_automation_actions";

    /** Process variable carrying the parent {@code ab_automation_log.id} (Long). */
    public static final String LOG_ID_VAR = "_automation_log_id";

    /** Process variable carrying the {@code Automation.pid} (denormalised on rows). */
    public static final String AUTOMATION_ID_VAR = "_automation_id";

    /** Process variable carrying the {@code tenant_id} for row-level scoping. */
    public static final String TENANT_ID_VAR = "_automation_tenant_id";

    private final ActionExecutor actionExecutor;

    // Optional — null in slim contexts that don't load the mapper bean; in that case
    // recording is silently disabled, the executor still runs.
    private final AutomationNodeExecutionMapper nodeExecutionMapper;

    @Autowired
    public AutomationActionServiceTaskDelegate(
            @Qualifier("compositeActionExecutor") ActionExecutor actionExecutor,
            @Autowired(required = false) AutomationNodeExecutionMapper nodeExecutionMapper) {
        this.actionExecutor = actionExecutor;
        this.nodeExecutionMapper = nodeExecutionMapper;
    }

    /**
     * Back-compat ctor for unit tests that predate the G5 mapper dependency.
     * Recording is disabled (mapper=null) — these tests only verify the executor
     * delegation behaviour. Not used by Spring (no {@code @Autowired}).
     */
    public AutomationActionServiceTaskDelegate(ActionExecutor actionExecutor) {
        this(actionExecutor, null);
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

        // G5: open a per-node execution row if a log id is in scope. null id ==
        // recording disabled (no log id present, e.g. direct unit-test invocation).
        Long executionRowId = beginNodeExecution(processVars, nodeId, executionContext);

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
            try {
                for (Object element : items) {
                    Map<String, Object> iterationContext = new HashMap<>(processVars);
                    iterationContext.put(itemVariable, element);
                    actionExecutor.execute(action, iterationContext);
                }
                completeNodeExecution(executionRowId, StatusConstants.COMPLETED, null);
            } catch (RuntimeException e) {
                // Record failure row first, then propagate. SmartEngine needs the
                // exception for its failure semantics (red line §8: no swallow).
                completeNodeExecution(executionRowId, StatusConstants.FAILED, e.getMessage());
                throw e;
            }
            return;
        }

        log.info("AutomationActionDelegate: node={}, actionType={}", nodeId, type);
        try {
            actionExecutor.execute(action, processVars);
            completeNodeExecution(executionRowId, StatusConstants.COMPLETED, null);
        } catch (RuntimeException e) {
            completeNodeExecution(executionRowId, StatusConstants.FAILED, e.getMessage());
            throw e;
        }
    }

    /**
     * Insert a {@code status='running'} row for this node entry and return its id, or
     * {@code null} when recording is disabled (no log id in scope, no tenant id, or
     * no mapper bean).
     *
     * <p>The recording branch must not impair the action — if the insert itself fails
     * we log and continue. Losing observability is strictly better than losing the
     * user-facing execution. This narrow {@code Exception} catch is deliberate
     * (observability-only, not action error handling — red line §8 still holds).
     */
    private Long beginNodeExecution(Map<String, Object> processVars, String nodeId,
                                    ExecutionContext executionContext) {
        if (nodeExecutionMapper == null) {
            return null;
        }
        Object logIdObj = processVars.get(LOG_ID_VAR);
        if (!(logIdObj instanceof Number logIdNum)) {
            return null;
        }
        Object tenantIdObj = processVars.get(TENANT_ID_VAR);
        Long tenantId = tenantIdObj instanceof Number n ? n.longValue() : null;
        if (tenantId == null) {
            log.debug("G5 node-status recording skipped: no tenant id (node={})", nodeId);
            return null;
        }
        String automationId = String.valueOf(processVars.getOrDefault(AUTOMATION_ID_VAR, ""));
        String processInstanceId = null;
        try {
            if (executionContext.getProcessInstance() != null) {
                processInstanceId = executionContext.getProcessInstance().getInstanceId();
            }
        } catch (Exception ignored) {
            // Best-effort: process instance lookup quirks must not break execution.
        }

        AutomationNodeExecution row = new AutomationNodeExecution();
        row.setTenantId(tenantId);
        row.setAutomationLogId(logIdNum.longValue());
        row.setAutomationId(automationId);
        row.setProcessInstanceId(processInstanceId);
        row.setNodeId(nodeId);
        row.setStatus(StatusConstants.RUNNING);
        row.setStartedAt(Instant.now());
        row.setCreatedAt(Instant.now());
        try {
            nodeExecutionMapper.insert(row);
            return row.getId();
        } catch (Exception e) {
            log.warn("G5 node-status recording failed (insert) for node={}: {}",
                    nodeId, e.getMessage());
            return null;
        }
    }

    private void completeNodeExecution(Long rowId, String status, String errorMessage) {
        if (rowId == null || nodeExecutionMapper == null) {
            return;
        }
        try {
            AutomationNodeExecution update = new AutomationNodeExecution();
            update.setId(rowId);
            update.setStatus(status);
            update.setCompletedAt(Instant.now());
            update.setErrorMessage(errorMessage);
            nodeExecutionMapper.updateById(update);
        } catch (Exception e) {
            log.warn("G5 node-status recording failed (update) rowId={}: {}",
                    rowId, e.getMessage());
        }
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
