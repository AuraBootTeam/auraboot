package com.auraboot.framework.eventpolicy.executor.handler;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.eventpolicy.entity.DrtActionAuditEntity;
import com.auraboot.framework.eventpolicy.executor.ActionExecutionException;
import com.auraboot.framework.eventpolicy.executor.ActionHandler;
import com.auraboot.framework.eventpolicy.executor.ActionProviderDependency;
import com.auraboot.framework.eventpolicy.mapper.DrtActionAuditMapper;
import com.auraboot.framework.eventpolicy.model.ResolvedActionPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * First production {@link ActionHandler} (docs/2.md §7): the {@code WRITE_AUDIT} action — a
 * policy-author-configured business audit entry written when a rule matches. Self-contained
 * (writes {@code ab_drt_action_audit}; no external service), so it both delivers a real action type
 * and proves the SPI end-to-end with a registered Spring bean. External-service handlers
 * (NOTIFY / START_PROCESS / CREATE_TASK …) follow this same shape.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditActionHandler implements ActionHandler {

    private static final Pattern TEMPLATE = Pattern.compile("\\$\\{([^}]+)}");

    private final DrtActionAuditMapper auditMapper;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(String actionType) {
        return "WRITE_AUDIT".equals(actionType);
    }

    @Override
    public List<ActionProviderDependency> runtimeProviderDependencies() {
        return List.of(ActionProviderDependencies.actionAudit());
    }

    @Override
    public void execute(ResolvedActionPlan plan, DecisionContext context) {
        executeWithResult(plan, context);
    }

    @Override
    public Map<String, Object> executeWithResult(ResolvedActionPlan plan, DecisionContext context) {
        Long tid = MetaContext.exists() ? MetaContext.getCurrentTenantId() : null;
        if (tid == null) {
            throw new ActionExecutionException(
                    "Tenant context required for WRITE_AUDIT action",
                    failurePayload(plan, "audit_tenant_missing", null, null, null)
                            .with("requiredContext", List.of("tenantId"))
                            .build(),
                    null);
        }
        Map<String, Object> payload = renderPayload(plan.payload() != null ? plan.payload() : Map.of(), context);
        String target = render(plan.target(), context);
        DrtActionAuditEntity row = new DrtActionAuditEntity();
        row.setPid(UniqueIdGenerator.generate());
        row.setTenantId(tid);
        row.setRuleCode(plan.ruleCode());
        row.setActionType(plan.type());
        row.setTarget(target);
        Object message = payload.get("message");
        row.setMessage(message != null ? String.valueOf(message) : null);
        row.setPayloadJson(objectMapper.valueToTree(payload));
        row.setIdempotencyKey(plan.idempotencyKey());
        row.setCreatedAt(Instant.now());
        try {
            int inserted = auditMapper.insert(row);
            if (inserted <= 0) {
                throw new IllegalStateException("no audit row inserted");
            }
        } catch (RuntimeException e) {
            throw new ActionExecutionException(
                    "WRITE_AUDIT failed: " + messageOf(e),
                    failurePayload(plan, "audit_write_failed", tid, target, row.getMessage())
                            .with("auditPid", row.getPid())
                            .with("errorMessage", messageOf(e))
                            .build(),
                    e);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("auditPid", row.getPid());
        result.put("tenantId", tid);
        result.put("ruleCode", plan.ruleCode());
        result.put("actionType", plan.type());
        if (row.getTarget() != null) {
            result.put("target", row.getTarget());
        }
        if (row.getMessage() != null) {
            result.put("message", row.getMessage());
        }
        return result;
    }

    private static Map<String, Object> renderPayload(Map<String, Object> payload, DecisionContext context) {
        Map<String, Object> rendered = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            rendered.put(entry.getKey(), renderValue(entry.getValue(), context));
        }
        return rendered;
    }

    private static Object renderValue(Object value, DecisionContext context) {
        if (value instanceof String text) {
            return render(text, context);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> rendered = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    rendered.put(String.valueOf(entry.getKey()), renderValue(entry.getValue(), context));
                }
            }
            return rendered;
        }
        if (value instanceof List<?> list) {
            List<Object> rendered = new ArrayList<>();
            for (Object item : list) {
                rendered.add(renderValue(item, context));
            }
            return rendered;
        }
        return value;
    }

    private static String render(Object value, DecisionContext context) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        Matcher matcher = TEMPLATE.matcher(text);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            Object resolved = resolveToken(matcher.group(1).trim(), context);
            matcher.appendReplacement(out, Matcher.quoteReplacement(resolved != null ? String.valueOf(resolved) : ""));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static Object resolveToken(String token, DecisionContext context) {
        int dot = token.indexOf('.');
        if (dot <= 0) {
            return null;
        }
        try {
            Scope scope = Scope.fromCode(token.substring(0, dot));
            DecisionContext.PathValue pv = context.resolve(scope, token.substring(dot + 1));
            return pv.present() ? pv.value() : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static PayloadBuilder failurePayload(
            ResolvedActionPlan plan,
            String failureReason,
            Long tenantId,
            String target,
            String message) {
        return new PayloadBuilder()
                .with("failureReason", failureReason)
                .with("tenantId", tenantId)
                .with("ruleCode", plan != null ? plan.ruleCode() : null)
                .with("actionType", plan != null ? plan.type() : null)
                .with("target", target)
                .with("message", message);
    }

    private static String messageOf(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static final class PayloadBuilder {
        private final Map<String, Object> payload = new LinkedHashMap<>();

        private PayloadBuilder with(String key, Object value) {
            if (value != null) {
                payload.put(key, value);
            }
            return this;
        }

        private Map<String, Object> build() {
            return payload;
        }
    }
}
