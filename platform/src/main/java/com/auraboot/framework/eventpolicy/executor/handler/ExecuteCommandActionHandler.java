package com.auraboot.framework.eventpolicy.executor.handler;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.eventpolicy.executor.ActionHandler;
import com.auraboot.framework.eventpolicy.model.ResolvedActionPlan;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.meta.service.CommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Executes a model operation through the existing authorized command pipeline. */
@Component
@RequiredArgsConstructor
public class ExecuteCommandActionHandler implements ActionHandler {
    private final CommandService commandService;
    private final CommandExecutor commandExecutor;

    @Override
    public boolean supports(String actionType) {
        return "EXECUTE_COMMAND".equals(actionType);
    }

    @Override
    public void execute(ResolvedActionPlan plan, DecisionContext context) {
        executeWithResult(plan, context);
    }

    @Override
    public Map<String, Object> executeWithResult(ResolvedActionPlan plan, DecisionContext context) {
        if (!MetaContext.exists() || MetaContext.getCurrentTenantId() == null) {
            throw new IllegalStateException("A tenant execution identity is required");
        }
        Map<String, Object> payload = plan.payload();
        String model = required(payload, "modelCode");
        String code = required(payload, "commandCode");
        var definition = commandService.findByCode(code);
        if (!model.equals(definition.getModelCode())) {
            throw new IllegalArgumentException("The selected command does not belong to the target model");
        }
        Object parameters = payload.get("params");
        if (parameters != null && !(parameters instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Command params must be an object");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        if (parameters instanceof Map<?, ?> values) {
            values.forEach((key, value) -> params.put(String.valueOf(key), value));
        }
        var request = new CommandExecuteRequest();
        request.setPayload(params);
        request.setClientRequestId(plan.idempotencyKey());
        Object target = payload.get("targetRecordPid");
        if (target != null) {
            if (!(target instanceof String pid) || pid.isBlank()) {
                throw new IllegalArgumentException("The target record must be a public record identifier");
            }
            request.setTargetRecordPid((String) target);
        }
        // No ALL permit override: the command guard enforces model, field and record authority.
        Object rawDepth = context.resolve(com.auraboot.framework.decision.ast.Scope.META, "actionDepth").value();
        int depth = rawDepth instanceof Number number ? number.intValue() : 0;
        if (depth >= 16) throw new IllegalStateException("Rule action chain exceeded the execution depth limit");
        request.setAuditContext(Map.of("source", "EVENT_POLICY", "ruleCode", plan.ruleCode(), "policyActionDepth", depth + 1));
        var result = commandExecutor.execute(code, request);
        return Map.of("commandCode", code, "modelCode", model,
                "idempotentReplay", result.isIdempotentReplay(),
                "phaseReached", result.getPhaseReached());
    }

    private static String required(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Command action requires " + key);
        }
        return text;
    }
}
