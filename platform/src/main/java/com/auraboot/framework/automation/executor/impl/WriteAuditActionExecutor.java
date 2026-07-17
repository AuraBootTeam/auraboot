package com.auraboot.framework.automation.executor.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.executor.ActionExecutor;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.eventpolicy.entity.DrtActionAuditEntity;
import com.auraboot.framework.eventpolicy.mapper.DrtActionAuditMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class WriteAuditActionExecutor implements ActionExecutor {

    private final DrtActionAuditMapper auditMapper;
    private final ObjectMapper objectMapper;

    @Override
    public Object execute(AutomationAction action, Map<String, Object> context) {
        Long tenantId = MetaContext.exists() ? MetaContext.getCurrentTenantId() : null;
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context required for WRITE_AUDIT action");
        }

        Map<String, Object> config = action.getConfig() != null ? action.getConfig() : Map.of();
        String automationRef = firstNonBlank(
                AutomationActionValueResolver.resolveString(context.get("automationPid"), context),
                AutomationActionValueResolver.resolveString(context.get("_automation_id"), context),
                "automation");
        String message = AutomationActionValueResolver.resolveString(config.get("message"), context);
        String target = firstNonBlank(
                AutomationActionValueResolver.resolveString(config.get("target"), context),
                automationRef);
        Map<String, Object> payload = AutomationActionValueResolver.resolveMap(config.get("payload"), context);
        if (context.containsKey("recordPid")) {
            payload.putIfAbsent("recordPid", context.get("recordPid"));
        }
        payload.putIfAbsent("automationPid", automationRef);

        DrtActionAuditEntity row = new DrtActionAuditEntity();
        row.setPid(UniqueIdGenerator.generate());
        row.setTenantId(tenantId);
        row.setRuleCode(automationRef);
        row.setActionType(action.getType());
        row.setTarget(target);
        row.setMessage(message);
        row.setPayloadJson(objectMapper.valueToTree(payload));
        row.setIdempotencyKey(AutomationActionValueResolver.resolveString(config.get("idempotencyKey"), context));
        row.setCreatedAt(Instant.now());
        auditMapper.insert(row);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("auditPid", row.getPid());
        result.put("tenantId", tenantId);
        result.put("ruleCode", automationRef);
        result.put("actionType", action.getType());
        if (message != null) {
            result.put("message", message);
        }
        return result;
    }

    @Override
    public boolean supports(String actionType) {
        return "write_audit".equals(actionType);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
