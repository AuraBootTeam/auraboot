package com.auraboot.framework.bpm.listener;

import com.auraboot.framework.bpm.extension.BpmExtensionKeys;
import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.bpmn.assembly.callactivity.CallActivity;
import com.auraboot.smart.framework.engine.constant.AdHocConstant;
import com.auraboot.smart.framework.engine.constant.ExtensionElementsConstant;
import com.auraboot.smart.framework.engine.constant.RequestMapSpecialKeyConstant;
import com.auraboot.smart.framework.engine.context.ExecutionContext;
import com.auraboot.smart.framework.engine.instance.impl.DefaultVariableInstance;
import com.auraboot.smart.framework.engine.listener.Listener;
import com.auraboot.smart.framework.engine.model.assembly.BaseElement;
import com.auraboot.smart.framework.engine.model.assembly.ExtensionElements;
import com.auraboot.smart.framework.engine.model.instance.ProcessInstance;
import com.auraboot.smart.framework.engine.model.instance.VariableInstance;
import com.auraboot.smart.framework.engine.pvm.event.EventConstant;
import org.springframework.jdbc.core.JdbcTemplate;
import com.auraboot.smart.framework.engine.smart.PropertyCompositeKey;
import com.auraboot.smart.framework.engine.smart.PropertyCompositeValue;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bridges SmartEngine's parent↔child request-map isolation on {@code <callActivity>}.
 *
 * <p>SmartEngine's {@code CallActivityBehavior#startChildProcessInstance} explicitly
 * copies only {@code tenantId} from parent request into the child's request map
 * (see the "隔离父子流程的request和response" comment in the SmartEngine fork). As a
 * result, BPMN-level {@code <smart:in>/<smart:out>} is not supported by the
 * runtime — even if it were emitted, the engine would discard it.
 *
 * <p>This listener consumes the {@link BpmExtensionKeys#CALL_MAPPINGS} payload
 * that {@code JsonToBpmnConverter.writeCallActivity} places on each
 * {@code <callActivity>} and performs the variable copy at the two lifecycle
 * boundaries that SmartEngine does expose:
 *
 * <ul>
 *   <li><b>Inputs (parent → child)</b>: on the child process's
 *       {@link EventConstant#PROCESS_START}, when {@code context.getParent()} is
 *       present, walk the parent's call-activity element to read
 *       {@code aura.callMappings.inputs}. For each mapping
 *       {@code parentVar → childVar}, copy {@code parent.getRequest().get(parentVar)}
 *       into {@code child.getRequest().put(childVar, ...)}. The child's StartEvent
 *       then persists this into {@code se_variable_instance} via
 *       {@code AuraVariablePersister}, so downstream child activities see the
 *       mapped variables exactly like top-level process variables.</li>
 *   <li><b>Outputs (child → parent)</b>: on the parent's
 *       {@link EventConstant#ACTIVITY_END} for a {@link CallActivity}, read
 *       {@code aura.callMappings.outputs}. By this point the child process is
 *       fully persisted (CallActivityBehavior only returns from {@code enter()}
 *       after the child completes or pauses). Locate the child instance by
 *       querying {@code ProcessInstanceQueryParam(parentInstanceId = parent,
 *       tenantId)}, pull its persisted variables via
 *       {@code VariableQueryService.findProcessInstanceVariableList}, and write
 *       each mapped pair {@code childVar → parentVar} back onto the parent's
 *       request map. The parent's next activity persists these through the
 *       normal execution-variable path.</li>
 * </ul>
 *
 * <p>This listener deliberately runs last — it is registered as a
 * {@code @Component} and picked up automatically by
 * {@code SmartEngineConfiguration.GlobalListenerExecutor} alongside
 * {@code ProcessEventListener}. Failures are logged but never propagated: a
 * malformed mapping payload should not abort a process, because the underlying
 * BPMN is still valid — only the enrichment is lost, which is surfaced at the
 * E2E / integration layer.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuraCallActivityListener implements Listener {

    private static final TypeReference<Map<String, Map<String, String>>> MAPPINGS_TYPE =
            new TypeReference<>() {};

    private final SmartEngine smartEngine;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void execute(EventConstant event, ExecutionContext context) {
        if (context == null) return;
        try {
            switch (event) {
                case PROCESS_START, start -> handleProcessStart(context);
                case PROCESS_END -> handleProcessEnd(context);
                default -> { /* no-op */ }
            }
        } catch (Exception e) {
            // Non-fatal: carry on with the process. Enrichment is best-effort
            // and any malformed payload should surface via E2E/integration
            // failures rather than aborting a running workflow.
            log.warn("AuraCallActivityListener failed on event {}: {}",
                    event, e.getMessage(), e);
        }
    }

    /**
     * Handle parent → child input mapping at the child's PROCESS_START.
     *
     * <p>PROCESS_START fires twice in a CallActivity chain: once for the parent
     * process (when it first starts — no parent context, handled as no-op) and
     * once for the child process (inside
     * {@code CallActivityBehavior.startChildProcessInstance}). Only the latter
     * has {@code context.getParent()} pointing at the parent context whose
     * current activity is the {@code CallActivity} being invoked.
     */
    private void handleProcessStart(ExecutionContext childContext) {
        ExecutionContext parentContext = childContext.getParent();
        if (parentContext == null) {
            return; // Top-level process start — nothing to propagate.
        }

        // The parent context's current baseElement is the CallActivity that
        // called us. If it's not a CallActivity, we're not in a sub-process
        // chain (defensive — in practice getParent() is only non-null when
        // called via CallActivityBehavior).
        BaseElement parentActivity = parentContext.getBaseElement();
        if (!(parentActivity instanceof CallActivity)) {
            return;
        }
        CallActivity callActivity = (CallActivity) parentActivity;

        Map<String, Object> parentRequest = parentContext.getRequest();
        Map<String, Object> childRequest = childContext.getRequest();
        if (childRequest == null) {
            childRequest = new LinkedHashMap<>();
            childContext.setRequest(childRequest);
        }

        // Always propagate the original starter user id into the child so
        // assignee resolvers (AssigneeTypeConstant.STARTER) on child userTasks
        // route back to whoever started the parent chain. SmartEngine's
        // CallActivityBehavior only copies tenantId; without this forward,
        // "starter" assignment on child tasks silently resolves to null and
        // tasks end up unassigned. This forward happens even when the
        // callActivity has no declared mappings, because preserving the
        // starter identity is a baseline correctness requirement for nested
        // processes, not an opt-in enrichment.
        if (parentRequest != null && !childRequest.containsKey(
                RequestMapSpecialKeyConstant.PROCESS_INSTANCE_START_USER_ID)) {
            Object starter = parentRequest.get(
                    RequestMapSpecialKeyConstant.PROCESS_INSTANCE_START_USER_ID);
            if (starter != null) {
                childRequest.put(
                        RequestMapSpecialKeyConstant.PROCESS_INSTANCE_START_USER_ID,
                        starter);
            }
        }

        Map<String, Map<String, String>> mappings = readCallMappings(callActivity);
        if (mappings == null) return;
        Map<String, String> inputs = mappings.get("inputs");
        if (inputs == null || inputs.isEmpty()) return;

        // Persist input-mapped vars both into the in-memory child request AND
        // into {@code se_variable_instance} at process-scope. The request-map
        // write lets the child's running context see the variable immediately
        // (e.g. for conditional expressions on the first transition); the DB
        // write ensures the variable survives wait-state pauses and is
        // reported by {@code getProcessInstanceStatus}. SmartEngine's
        // {@code CallActivityBehavior.startChildProcessInstance} calls
        // {@code CommonServiceHelper.insertAndPersist(..., null, ...)} with a
        // null request map, so the engine's own auto-persist is bypassed for
        // child processes — we bridge that gap here.
        String childProcessInstanceId = childContext.getProcessInstance() != null
                ? childContext.getProcessInstance().getInstanceId() : null;
        String childTenantId = childContext.getTenantId();

        int copied = 0;
        for (Map.Entry<String, String> mapping : inputs.entrySet()) {
            String parentVar = mapping.getKey();
            String childVar = mapping.getValue();
            if (parentVar == null || childVar == null
                    || parentVar.isBlank() || childVar.isBlank()) {
                continue;
            }
            Object value = parentRequest != null ? parentRequest.get(parentVar) : null;
            if (value == null) {
                // Mapping declared but value absent on parent — skip silently
                // so the child can still run with whatever defaults its
                // activities provide.
                continue;
            }
            childRequest.put(childVar, value);
            if (childProcessInstanceId != null) {
                persistProcessVariable(childProcessInstanceId, childTenantId, childVar, value);
            }
            copied++;
        }

        log.debug("CallActivity input mapping: parent={} child={} copied={} of {}",
                callActivity.getId(),
                childProcessInstanceId,
                copied, inputs.size());
    }

    /**
     * Collect every {@code se_variable_instance} row for a process instance,
     * regardless of execution scope, as a {@code key → value} map. Mirrors
     * {@code ProcessEngineService#mergeExecutionScopeVariables} but without
     * the process-scope precedence filter — the child has already finished
     * by the time output-mapping runs so the latest write is the canonical
     * one, and task-completion variables (most common mapping source) always
     * land in execution scope.
     */
    private Map<String, Object> collectAllChildVariables(String childInstanceId, String tenantId) {
        Map<String, Object> merged = new LinkedHashMap<>();
        // Start with process-scope (typed via VariablePersister.deserialize).
        try {
            List<VariableInstance> processScope = smartEngine.getVariableQueryService()
                    .findProcessInstanceVariableList(childInstanceId, tenantId);
            if (processScope != null) {
                for (VariableInstance vi : processScope) {
                    merged.put(vi.getFieldKey(), vi.getFieldValue());
                }
            }
        } catch (Exception e) {
            log.debug("collectAllChildVariables process-scope query failed for {}: {}",
                    childInstanceId, e.getMessage());
        }
        // Union execution-scope rows so task-completion variables surface.
        try {
            StringBuilder sql = new StringBuilder(
                    "SELECT field_key, field_type, field_string_value, field_long_value, field_double_value "
                            + "FROM se_variable_instance "
                            + "WHERE process_instance_id = ? "
                            + "AND COALESCE(execution_instance_id, 0) <> 0");
            List<Object> params = new ArrayList<>();
            params.add(Long.parseLong(childInstanceId));
            if (tenantId != null) {
                sql.append(" AND tenant_id = ?");
                params.add(tenantId);
            }
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), params.toArray());
            for (Map<String, Object> row : rows) {
                String key = (String) row.get("field_key");
                if (key == null) continue;
                // Execution-scope writes win over process-scope for the same
                // key (task-completion is the authoritative latest value for
                // the fields most likely to be mapped).
                Object value = coerceVariableValue(
                        (String) row.get("field_type"),
                        row.get("field_string_value"),
                        row.get("field_long_value"),
                        row.get("field_double_value"));
                if (value != null) {
                    merged.put(key, value);
                }
            }
        } catch (NumberFormatException e) {
            // non-numeric id — skip silently.
        } catch (Exception e) {
            log.debug("collectAllChildVariables execution-scope query failed for {}: {}",
                    childInstanceId, e.getMessage());
        }
        return merged;
    }

    /**
     * Mirror of {@code ProcessEngineService#coerceVariableValue} — coerce a
     * raw {@code se_variable_instance} row into its declared Java type.
     */
    private Object coerceVariableValue(String type, Object stringValue, Object longValue, Object doubleValue) {
        String s = stringValue != null ? stringValue.toString() : null;
        if (type == null) {
            if (longValue != null) return longValue;
            if (doubleValue != null) return doubleValue;
            return s;
        }
        return switch (type) {
            case "java.lang.Boolean", "boolean", "Boolean" ->
                    s != null ? Boolean.valueOf(s) : null;
            case "java.lang.Long", "long", "Long" ->
                    longValue != null ? longValue : (s != null ? Long.valueOf(s) : null);
            case "java.lang.Integer", "int", "Integer" ->
                    longValue != null ? ((Number) longValue).intValue() : (s != null ? Integer.valueOf(s) : null);
            case "java.lang.Double", "double", "Double" ->
                    doubleValue != null ? doubleValue : (s != null ? Double.valueOf(s) : null);
            case "java.lang.Float", "float", "Float" ->
                    doubleValue != null ? ((Number) doubleValue).floatValue() : (s != null ? Float.valueOf(s) : null);
            default -> s;
        };
    }

    /**
     * Insert a process-scope variable row (execution_instance_id = "0") via
     * SmartEngine's {@link com.auraboot.smart.framework.engine.service.command.VariableCommandService}.
     * Used by both input and output mapping paths — child input mapping must
     * surface in {@code ProcessInstanceStatusDTO.variables()}; parent output
     * mapping writes a new parent variable row so consumers of the completed
     * parent instance (status API, analytics) see the child's contribution.
     */
    private void persistProcessVariable(String processInstanceId, String tenantId,
                                        String key, Object value) {
        try {
            DefaultVariableInstance vi = new DefaultVariableInstance();
            smartEngine.getProcessEngineConfiguration().getIdGenerator().generate(vi);
            vi.setProcessInstanceId(processInstanceId);
            vi.setExecutionInstanceId(AdHocConstant.DEFAULT_ZERO_VALUE);
            vi.setFieldKey(key);
            vi.setFieldType(value.getClass());
            vi.setFieldValue(value);
            vi.setTenantId(tenantId);
            smartEngine.getVariableCommandService().insert(vi);
        } catch (Exception e) {
            // Best effort — if persistence fails the variable still lives in
            // the request map for the running child context. Log loudly so
            // the gap is visible in integration tests.
            log.warn("persistProcessVariable failed for pi={} key={}: {}",
                    processInstanceId, key, e.getMessage());
        }
    }

    /**
     * Handle child → parent output mapping at the child's {@link EventConstant#PROCESS_END}.
     *
     * <p>The {@code PROCESS_END} hook is deliberately chosen over the parent
     * callActivity's {@code ACTIVITY_END} for timing reasons. SmartEngine
     * persists task-completion variables to {@code se_variable_instance}
     * only AFTER {@code pvmProcessInstance.signal(...)} returns — inside
     * {@code CommonServiceHelper.createExecution(..., request, ...)}. The
     * signal chain (child end → child PROCESS_END → parent callActivity
     * leave → parent ACTIVITY_END) all runs BEFORE that persist, so at
     * parent ACTIVITY_END a DB query would not yet see {@code childOutput}.
     *
     * <p>At child PROCESS_END however, {@code context.getRequest()} still
     * carries the original {@code taskCommandService.complete} request map
     * in-memory — we read the output variable directly from there. We also
     * walk the parent context chain to locate the calling CallActivity and
     * read its {@code aura.callMappings} payload.
     */
    private void handleProcessEnd(ExecutionContext childContext) {
        ProcessInstance childInstance = childContext.getProcessInstance();
        if (childInstance == null) return;

        // Identify whether this is a callActivity child: SmartEngine stores
        // the parent pointer directly on the child process instance whenever
        // it was spawned via CallActivityBehavior.startChildProcessInstance.
        // We deliberately do NOT rely on {@code childContext.getParent()} —
        // that reference is present during the initial synchronous child
        // start but becomes null once the child pauses at a wait state and
        // resumes via a fresh {@code executionCommandService.signal} call
        // (PreparePhase rebuilds the context without the parent chain).
        String parentInstanceId = childInstance.getParentInstanceId();
        if (parentInstanceId == null || parentInstanceId.isBlank()) {
            return; // Top-level process end — nothing to propagate.
        }

        String tenantId = childContext.getTenantId();

        // Locate the parent's ProcessDefinition so we can read the
        // aura.callMappings payload off the callActivity element. We need
        // the activityId of the invoking callActivity; SmartEngine stores
        // that pointer on the child as {@code parentExecutionInstanceId},
        // which maps via {@code se_execution_instance} to the activity id.
        String parentExecutionInstanceId = childInstance.getParentExecutionInstanceId();
        CallActivity callActivity = resolveCallingCallActivity(parentInstanceId, parentExecutionInstanceId, tenantId);
        if (callActivity == null) {
            log.debug("handleProcessEnd: no invoking callActivity resolved for child={}",
                    childInstance.getInstanceId());
            return;
        }

        Map<String, Map<String, String>> mappings = readCallMappings(callActivity);
        if (mappings == null) return;
        Map<String, String> outputs = mappings.get("outputs");
        if (outputs == null || outputs.isEmpty()) return;

        // Child's end-state variables: union of persisted DB rows (process +
        // execution scopes) PLUS the live request map, which still holds the
        // task-completion payload not yet flushed by CommonServiceHelper
        // (see DefaultExecutionCommandService.signal: createExecution is
        // called AFTER pvmProcessInstance.signal returns, i.e. AFTER this
        // listener fires, so childOutput lives only in request at this point).
        Map<String, Object> childVarMap = new LinkedHashMap<>();
        childVarMap.putAll(collectAllChildVariables(childInstance.getInstanceId(), tenantId));
        if (childContext.getRequest() != null) {
            childVarMap.putAll(childContext.getRequest());
        }

        int copied = 0;
        for (Map.Entry<String, String> mapping : outputs.entrySet()) {
            String childVar = mapping.getKey();
            String parentVar = mapping.getValue();
            if (childVar == null || parentVar == null
                    || childVar.isBlank() || parentVar.isBlank()) {
                continue;
            }
            if (!childVarMap.containsKey(childVar)) continue;
            Object value = childVarMap.get(childVar);
            if (value != null) {
                // Persist directly to parent's process-scope variable table.
                // The parent's in-memory request map is not accessible here
                // (fresh signal-prepared context for the resumed parent hasn't
                // been built yet), so DB is the authoritative channel. The
                // parent's execution flow reads variables via
                // ProcessEngineService#getProcessInstanceStatus which unions
                // both scopes, so the write is immediately visible.
                persistProcessVariable(parentInstanceId, tenantId, parentVar, value);
            }
            copied++;
        }

        log.debug("CallActivity output mapping: parent={} child={} copied={} of {}",
                parentInstanceId, childInstance.getInstanceId(),
                copied, outputs.size());
    }

    /**
     * Given the parent process instance id + the parent execution instance
     * that invoked us, resolve the {@link CallActivity} element from the
     * parent's deployed {@link ProcessDefinition}.
     *
     * <p>Path: {@code parentExecutionInstance → activityId → parentDefinition.idBasedElementMap}.
     */
    private CallActivity resolveCallingCallActivity(String parentInstanceId,
                                                    String parentExecutionInstanceId,
                                                    String tenantId) {
        if (parentInstanceId == null || parentExecutionInstanceId == null) return null;
        try {
            ProcessInstance parent = smartEngine.getProcessQueryService()
                    .findById(parentInstanceId, tenantId);
            if (parent == null) return null;
            String pdId = parent.getProcessDefinitionId();
            String pdVersion = parent.getProcessDefinitionVersion();
            if (pdId == null) return null;
            var parentDef = smartEngine.getRepositoryQueryService()
                    .getCachedProcessDefinition(pdId, pdVersion, tenantId);
            if (parentDef == null) return null;

            // Look up the parent's execution instance to find which activityId
            // invoked us. SmartEngine's ExecutionQueryService has no findById,
            // so iterate all executions for the parent process and match by id.
            String activityId = null;
            var allExecutions = smartEngine.getExecutionQueryService()
                    .findAll(parentInstanceId, tenantId);
            if (allExecutions != null) {
                for (var ei : allExecutions) {
                    if (parentExecutionInstanceId.equals(ei.getInstanceId())) {
                        activityId = ei.getProcessDefinitionActivityId();
                        break;
                    }
                }
            }
            if (activityId == null) return null;

            Object element = parentDef.getIdBasedElementMap() != null
                    ? parentDef.getIdBasedElementMap().get(activityId) : null;
            return element instanceof CallActivity ? (CallActivity) element : null;
        } catch (Exception e) {
            log.debug("resolveCallingCallActivity failed (parent={}, pe={}): {}",
                    parentInstanceId, parentExecutionInstanceId, e.getMessage());
            return null;
        }
    }

    /**
     * Read the {@code aura.callMappings} payload from the CallActivity's
     * {@code <smart:properties>} extension, as emitted by
     * {@code JsonToBpmnConverter.writeCallActivity}.
     *
     * <p>Returns {@code null} when absent or unparseable — the listener
     * downgrades to a no-op in those cases.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, String>> readCallMappings(CallActivity callActivity) {
        ExtensionElements ext = callActivity.getExtensionElements();
        if (ext == null) return null;
        Map<String, Object> decorationMap = ext.getDecorationMap();
        if (decorationMap == null) return null;
        Object propsObj = decorationMap.get(ExtensionElementsConstant.PROPERTIES);
        if (!(propsObj instanceof Map)) return null;
        Map<PropertyCompositeKey, PropertyCompositeValue> props =
                (Map<PropertyCompositeKey, PropertyCompositeValue>) propsObj;
        PropertyCompositeValue entry =
                props.get(new PropertyCompositeKey(null, BpmExtensionKeys.CALL_MAPPINGS));
        if (entry == null) return null;
        String json = entry.getValue();
        if (json == null || json.isBlank()) return null;
        try {
            Map<String, Map<String, String>> parsed = objectMapper.readValue(json, MAPPINGS_TYPE);
            return parsed == null ? Collections.emptyMap() : parsed;
        } catch (Exception e) {
            log.warn("Malformed aura.callMappings JSON on callActivity {}: {}",
                    callActivity.getId(), json, e);
            return null;
        }
    }
}
