package com.auraboot.framework.eventpolicy.executor.handler;

import com.auraboot.framework.bpm.service.ProcessEngineService;
import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.eventpolicy.executor.ActionHandler;
import com.auraboot.framework.eventpolicy.executor.ActionExecutionException;
import com.auraboot.framework.eventpolicy.executor.ActionProviderDependency;
import com.auraboot.framework.eventpolicy.model.ResolvedActionPlan;
import com.auraboot.smart.framework.engine.model.instance.ProcessInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Production {@code START_PROCESS} {@link ActionHandler} (docs/2.md §7): starts a BPM process instance
 * via the platform {@link ProcessEngineService} when a policy rule matches (e.g. open an approval
 * flow for a high-value case). Additive — reuses the BPM engine. {@code payload.processDefinitionId}
 * selects the process; the business key defaults to the event's record pid; {@code payload.variables}
 * (plus the record pid) are passed as process variables.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartProcessActionHandler implements ActionHandler {

    private final ProcessEngineService processEngineService;

    @Override
    public boolean supports(String actionType) {
        return "START_PROCESS".equals(actionType);
    }

    @Override
    public List<ActionProviderDependency> runtimeProviderDependencies() {
        return List.of(ActionProviderDependencies.bpmEngine());
    }

    @Override
    @SuppressWarnings("unchecked")
    public void execute(ResolvedActionPlan plan, DecisionContext context) {
        executeWithResult(plan, context);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> executeWithResult(ResolvedActionPlan plan, DecisionContext context) {
        Map<String, Object> payload = plan.payload() != null ? plan.payload() : Map.of();
        Object pdId = payload.get("processDefinitionId");
        String recordPid = resolveString(context, "recordPid");
        if (pdId == null || String.valueOf(pdId).isBlank()) {
            throw new ActionExecutionException("缺少流程标识，无法启动流程",
                    failurePayload("process_definition_missing", "payload.processDefinitionId",
                            null, null, recordPid), null);
        }
        String processDefinitionId = String.valueOf(pdId);
        Object bk = payload.get("businessKey");
        String businessKey = bk != null ? String.valueOf(bk) : recordPid;

        java.util.Map<String, Object> variables = new java.util.HashMap<>();
        Object vars = payload.get("variables");
        if (vars instanceof Map<?, ?> m) {
            variables.putAll((Map<String, Object>) m);
        }
        if (recordPid != null) {
            variables.putIfAbsent("recordPid", recordPid);
        }
        // the policy-initiated process's starter is the current user (drives 'starter' assignee
        // resolution); set it when available unless the caller already provided one
        Long userId = com.auraboot.framework.application.tenant.MetaContext.exists()
                ? com.auraboot.framework.application.tenant.MetaContext.getCurrentUserId() : null;
        if (userId != null) {
            variables.putIfAbsent("_startUserId", String.valueOf(userId));
        }
        ProcessInstance processInstance;
        try {
            processInstance = processEngineService.startProcess(processDefinitionId, businessKey, variables);
        } catch (Exception e) {
            throw new ActionExecutionException("流程启动失败：流程未部署或流程标识不存在",
                    failurePayload("process_start_failed", null,
                            processDefinitionId, businessKey, recordPid), e);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("processDefinitionId", processDefinitionId);
        result.put("businessKey", businessKey);
        if (processInstance != null && processInstance.getInstanceId() != null) {
            result.put("processInstanceId", processInstance.getInstanceId());
        }
        if (recordPid != null) {
            result.put("recordPid", recordPid);
        }
        return result;
    }

    private static Map<String, Object> failurePayload(String reason, String field,
                                                      String processDefinitionId,
                                                      String businessKey,
                                                      String recordPid) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("failureReason", reason);
        if (field != null) {
            result.put("field", field);
        }
        if (processDefinitionId != null) {
            result.put("processDefinitionId", processDefinitionId);
        }
        if (businessKey != null) {
            result.put("businessKey", businessKey);
        }
        if (recordPid != null) {
            result.put("recordPid", recordPid);
        }
        return result;
    }

    private static String resolveString(DecisionContext context, String field) {
        DecisionContext.PathValue pv = context.resolve(Scope.RECORD, field);
        return pv.present() && pv.value() != null ? String.valueOf(pv.value()) : null;
    }
}
