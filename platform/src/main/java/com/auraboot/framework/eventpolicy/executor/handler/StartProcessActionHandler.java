package com.auraboot.framework.eventpolicy.executor.handler;

import com.auraboot.framework.bpm.service.ProcessEngineService;
import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.eventpolicy.executor.ActionHandler;
import com.auraboot.framework.eventpolicy.model.ResolvedActionPlan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Production {@code START_PROCESS} {@link ActionHandler} (docs/2.md §7): starts a BPM process instance
 * via the platform {@link ProcessEngineService} when a policy rule matches (e.g. open an approval
 * flow for a high-value case). Additive — reuses the BPM engine. {@code payload.processDefinitionId}
 * selects the process; the business key defaults to the event's record id; {@code payload.variables}
 * (plus the record id) are passed as process variables.
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
    @SuppressWarnings("unchecked")
    public void execute(ResolvedActionPlan plan, DecisionContext context) {
        Map<String, Object> payload = plan.payload() != null ? plan.payload() : Map.of();
        Object pdId = payload.get("processDefinitionId");
        if (pdId == null || String.valueOf(pdId).isBlank()) {
            throw new IllegalArgumentException("START_PROCESS requires payload.processDefinitionId");
        }
        String recordId = resolveString(context, "recordId");
        Object bk = payload.get("businessKey");
        String businessKey = bk != null ? String.valueOf(bk) : recordId;

        java.util.Map<String, Object> variables = new java.util.HashMap<>();
        Object vars = payload.get("variables");
        if (vars instanceof Map<?, ?> m) {
            variables.putAll((Map<String, Object>) m);
        }
        if (recordId != null) {
            variables.putIfAbsent("recordId", recordId);
        }
        // the policy-initiated process's starter is the current user (drives 'starter' assignee
        // resolution); set it when available unless the caller already provided one
        Long userId = com.auraboot.framework.application.tenant.MetaContext.exists()
                ? com.auraboot.framework.application.tenant.MetaContext.getCurrentUserId() : null;
        if (userId != null) {
            variables.putIfAbsent("_startUserId", String.valueOf(userId));
        }
        processEngineService.startProcess(String.valueOf(pdId), businessKey, variables);
    }

    private static String resolveString(DecisionContext context, String field) {
        DecisionContext.PathValue pv = context.resolve(Scope.RECORD, field);
        return pv.present() && pv.value() != null ? String.valueOf(pv.value()) : null;
    }
}
