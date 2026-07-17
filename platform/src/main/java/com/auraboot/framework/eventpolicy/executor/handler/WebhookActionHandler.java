package com.auraboot.framework.eventpolicy.executor.handler;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.eventpolicy.executor.ActionExecutionException;
import com.auraboot.framework.eventpolicy.executor.ActionHandler;
import com.auraboot.framework.eventpolicy.executor.ActionProviderDependency;
import com.auraboot.framework.eventpolicy.model.ResolvedActionPlan;
import com.auraboot.framework.webhook.service.WebhookDispatchResult;
import com.auraboot.framework.webhook.service.WebhookDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Pattern TEMPLATE = Pattern.compile("\\$\\{([^}]+)}");
    private static final int MAX_DELIVERY_EVENT_ID_LENGTH = 64;

    private final WebhookDispatcher webhookDispatcher;

    @Override
    public boolean supports(String actionType) {
        return "WEBHOOK".equals(actionType);
    }

    @Override
    public List<ActionProviderDependency> runtimeProviderDependencies() {
        return List.of(ActionProviderDependencies.webhookDispatcher());
    }

    @Override
    public void execute(ResolvedActionPlan plan, DecisionContext context) {
        executeWithResult(plan, context);
    }

    @Override
    public Map<String, Object> executeWithResult(ResolvedActionPlan plan, DecisionContext context) {
        Map<String, Object> payload = renderPayload(plan.payload() != null ? plan.payload() : Map.of(), context);
        Object eventType = payload.get("eventType");
        if (eventType == null || String.valueOf(eventType).isBlank()) {
            throw new IllegalArgumentException("WEBHOOK requires payload.eventType");
        }
        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context required for WEBHOOK action");
        }
        Map<String, Object> body = new LinkedHashMap<>(payload);
        body.remove("eventType");
        String eventTypeText = String.valueOf(eventType);
        String deliveryEventId = ensureDeliveryEventId(body);
        validateDeliveryEventId(deliveryEventId, eventTypeText);
        WebhookDispatchResult dispatchResult;
        try {
            dispatchResult = webhookDispatcher.dispatchTracked(eventTypeText, body, tenantId);
        } catch (RuntimeException e) {
            throw new ActionExecutionException(
                    "WEBHOOK dispatch failed: " + messageOf(e),
                    dispatchFailurePayload(eventTypeText, tenantId, deliveryEventId, body, e),
                    e);
        }
        List<Map<String, Object>> deliveryReceipts = dispatchResult.receipts().stream()
                .map(this::receiptPayload)
                .toList();
        List<String> deliveryLogPids = dispatchResult.receipts().stream()
                .map(WebhookDispatchResult.Receipt::deliveryLogPid)
                .filter(pid -> pid != null && !pid.isBlank())
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventType", eventTypeText);
        result.put("tenantId", tenantId);
        result.put("dispatchAccepted", true);
        result.put("deliveryEventId", deliveryEventId);
        result.put("deliveryTraceStatus", deliveryLogPids.isEmpty()
                ? "pending_async_delivery"
                : "tracked_delivery_logs");
        result.put("deliveryLogPids", deliveryLogPids);
        result.put("deliveryReceipts", deliveryReceipts);
        putIfPresent(result, "recordPid", body.get("recordPid"));
        putIfPresent(result, "slaRecordPid", body.get("slaRecordPid"));
        result.put("payloadKeys", body.keySet().stream()
                .filter(key -> !"_eventId".equals(key))
                .toList());
        return result;
    }

    private Map<String, Object> dispatchFailurePayload(
            String eventType,
            Long tenantId,
            String deliveryEventId,
            Map<String, Object> body,
            RuntimeException error) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventType", eventType);
        result.put("tenantId", tenantId);
        result.put("dispatchAccepted", false);
        result.put("deliveryEventId", deliveryEventId);
        result.put("deliveryTraceStatus", "dispatch_failed");
        result.put("failureReason", "webhook_dispatch_failed");
        result.put("errorMessage", messageOf(error));
        putIfPresent(result, "recordPid", body.get("recordPid"));
        putIfPresent(result, "slaRecordPid", body.get("slaRecordPid"));
        result.put("payloadKeys", body.keySet().stream()
                .filter(key -> !"_eventId".equals(key))
                .toList());
        return result;
    }

    private Map<String, Object> renderPayload(Map<String, Object> payload, DecisionContext context) {
        Map<String, Object> rendered = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            Object value = entry.getValue();
            rendered.put(entry.getKey(), value instanceof String text ? render(text, context) : value);
        }
        return rendered;
    }

    private static String render(String text, DecisionContext context) {
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

    private static String messageOf(RuntimeException error) {
        return error.getMessage() != null && !error.getMessage().isBlank()
                ? error.getMessage()
                : error.getClass().getSimpleName();
    }

    private void putIfPresent(Map<String, Object> result, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            result.put(key, value);
        }
    }

    private String ensureDeliveryEventId(Map<String, Object> body) {
        Object existing = body.get("_eventId");
        if (existing != null && !String.valueOf(existing).isBlank()) {
            return String.valueOf(existing);
        }
        String deliveryEventId = "ep-webhook-" + UniqueIdGenerator.generate();
        body.put("_eventId", deliveryEventId);
        return deliveryEventId;
    }

    private void validateDeliveryEventId(String deliveryEventId, String eventType) {
        if (deliveryEventId.length() <= MAX_DELIVERY_EVENT_ID_LENGTH) {
            return;
        }
        Map<String, Object> resultPayload = new LinkedHashMap<>();
        resultPayload.put("eventType", eventType);
        resultPayload.put("deliveryEventId", deliveryEventId);
        resultPayload.put("deliveryTraceStatus", "validation_failed");
        resultPayload.put("validationError", "payload._eventId exceeds max length");
        resultPayload.put("field", "payload._eventId");
        resultPayload.put("actualLength", deliveryEventId.length());
        resultPayload.put("maxLength", MAX_DELIVERY_EVENT_ID_LENGTH);
        throw new ActionExecutionException(
                "WEBHOOK payload._eventId must be 64 characters or fewer (current: "
                        + deliveryEventId.length() + ")",
                resultPayload,
                null);
    }

    private Map<String, Object> receiptPayload(WebhookDispatchResult.Receipt receipt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("subscriptionPid", receipt.subscriptionPid());
        payload.put("deliveryLogPid", receipt.deliveryLogPid());
        payload.put("eventId", receipt.eventId());
        payload.put("deliveryStatus", receipt.deliveryStatus());
        payload.put("delivered", receipt.delivered());
        if (receipt.errorMessage() != null && !receipt.errorMessage().isBlank()) {
            payload.put("errorMessage", receipt.errorMessage());
        }
        return payload;
    }
}
