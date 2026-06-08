package com.auraboot.framework.eventpolicy.executor.handler;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.eventpolicy.executor.ActionHandler;
import com.auraboot.framework.eventpolicy.model.ResolvedActionPlan;
import com.auraboot.framework.webhook.service.WebhookDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Production {@code WEBHOOK} {@link ActionHandler} (docs/2.md §7): fans an event out to the platform
 * {@link WebhookDispatcher} (which delivers to the tenant's webhook subscriptions) when a policy rule
 * matches. Additive — reuses the existing webhook subsystem rather than calling raw external HTTP, so
 * delivery, retry and signing stay centralized. {@code payload.eventType} selects the webhook event;
 * the rest of the payload is the body.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookActionHandler implements ActionHandler {

    private final WebhookDispatcher webhookDispatcher;

    @Override
    public boolean supports(String actionType) {
        return "WEBHOOK".equals(actionType);
    }

    @Override
    public void execute(ResolvedActionPlan plan, DecisionContext context) {
        Map<String, Object> payload = plan.payload() != null ? plan.payload() : Map.of();
        Object eventType = payload.get("eventType");
        if (eventType == null || String.valueOf(eventType).isBlank()) {
            throw new IllegalArgumentException("WEBHOOK requires payload.eventType");
        }
        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context required for WEBHOOK action");
        }
        Map<String, Object> body = new HashMap<>(payload);
        body.remove("eventType");
        webhookDispatcher.dispatch(String.valueOf(eventType), body, tenantId);
    }
}
