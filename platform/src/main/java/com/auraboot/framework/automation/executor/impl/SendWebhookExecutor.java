package com.auraboot.framework.automation.executor.impl;

import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.executor.ActionExecutor;
import com.auraboot.framework.common.util.PinnedHttpRequests;
import com.auraboot.framework.common.util.SsrfValidator;
import com.auraboot.framework.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Executor for SEND_WEBHOOK action type.
 *
 * <p>POSTs the configured payload directly to the node's configured {@code url}
 * — exactly what the designer node promises ("发送 Webhook 请求到指定 URL" /
 * "Send a Webhook request to the specified URL"). The previous implementation
 * ignored {@code url} entirely and instead fanned out to registered webhook
 * SUBSCRIPTIONS via {@code WebhookDispatcher#dispatch(eventType, ...)}, so a
 * designer-built send-webhook silently never hit the URL the user typed
 * (golden FINDING-10 — gate-gap: the node "completed" but did nothing useful).
 * The subscription/dispatcher system has its own producers (the command
 * pipeline {@code CompletionPhase} WEBHOOK stage + {@code OutboxWorkerImpl}),
 * so this action is now a self-contained direct POST, mirroring
 * {@link CallApiExecutor} (SSRF-validated + DNS-rebinding-pinned).
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SendWebhookExecutor implements ActionExecutor {

    private final ObjectMapper objectMapper;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public Object execute(AutomationAction action, Map<String, Object> context) {
        Map<String, Object> config = action.getConfig();
        if (config == null) {
            throw new IllegalArgumentException("SEND_WEBHOOK action requires config");
        }

        String url = (String) config.get("url");
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("SEND_WEBHOOK action requires url");
        }
        url = processTemplate(url, context);

        // Build the JSON body from the configured payload (Map or JSON string),
        // or fall back to the trigger context essentials.
        String bodyJson = buildPayloadJson(config.get("payload"), context);

        // Validate URL + capture the resolved IP so the HTTP call uses the same IP
        // that passed the block-list check (P3-E #1 DNS rebinding TOCTOU) — mirrors
        // CallApiExecutor / WebhookDispatcherImpl.
        SsrfValidator.ValidatedTarget target = SsrfValidator.validate(url);
        if (target == null) {
            throw new IllegalArgumentException("SEND_WEBHOOK target could not be resolved: " + url);
        }

        int timeoutSeconds = config.containsKey("timeoutSeconds")
                ? ((Number) config.get("timeoutSeconds")).intValue() : 30;

        log.info("Sending webhook POST: url={}, bodyBytes={}", url, bodyJson != null ? bodyJson.length() : 0);

        try {
            HttpRequest.Builder requestBuilder = PinnedHttpRequests.newPinnedRequestBuilder(target)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json");

            @SuppressWarnings("unchecked")
            Map<String, String> headers = (Map<String, String>) config.get("headers");
            if (headers != null) {
                headers.forEach((k, v) -> requestBuilder.header(k, processTemplate(v, context)));
            }

            requestBuilder.POST(bodyJson != null
                    ? HttpRequest.BodyPublishers.ofString(bodyJson)
                    : HttpRequest.BodyPublishers.noBody());

            HttpResponse<String> response = HTTP_CLIENT.send(
                    requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

            int statusCode = response.statusCode();
            log.info("Webhook response: status={}, url={}", statusCode, url);

            if (statusCode >= 400) {
                throw new BusinessException(
                        "Webhook POST failed with status " + statusCode + ": " + response.body());
            }

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("statusCode", statusCode);
            result.put("url", url);
            return result;

        } catch (BusinessException e) {
            // Already a clear, surfaced reason — propagate as-is (no double-wrap).
            throw e;
        } catch (Exception e) {
            throw new BusinessException("Webhook POST failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean supports(String actionType) {
        return "send_webhook".equals(actionType);
    }

    /**
     * Serialize the configured payload to a JSON body, resolving {@code ${...}}
     * placeholders against the run context. Accepts a Map (per-value substitution,
     * preserving non-string types) or a JSON String (partial template substitution),
     * and falls back to the trigger context when no payload is configured.
     */
    private String buildPayloadJson(Object payloadConfig, Map<String, Object> context) {
        try {
            if (payloadConfig instanceof Map<?, ?> mapPayload) {
                Map<String, Object> processed = new HashMap<>();
                for (Map.Entry<?, ?> entry : mapPayload.entrySet()) {
                    processed.put(String.valueOf(entry.getKey()), resolveValue(entry.getValue(), context));
                }
                return objectMapper.writeValueAsString(processed);
            }
            if (payloadConfig instanceof String strPayload) {
                // A JSON string with ${...} placeholders → substitute then send verbatim.
                return processTemplate(strPayload, context);
            }
            // Default: emit the trigger context essentials.
            Map<String, Object> def = new HashMap<>();
            def.put("automationPid", context.get("automationPid"));
            def.put("recordId", context.get("recordId"));
            def.put("event", context.get("event"));
            if (context.containsKey("record")) {
                def.put("record", context.get("record"));
            }
            return objectMapper.writeValueAsString(def);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("SEND_WEBHOOK payload serialization failed: " + e.getMessage(), e);
        }
    }

    /**
     * Resolve a single payload value: an exact single-placeholder string
     * ({@code ${a.b}}) resolves to the raw context value (preserving type and
     * supporting nested map access); any other string runs partial template
     * substitution; non-strings pass through unchanged.
     */
    private Object resolveValue(Object value, Map<String, Object> context) {
        if (!(value instanceof String str)) {
            return value;
        }
        if (str.startsWith("${") && str.endsWith("}") && str.indexOf("${", 2) == -1) {
            return resolveVariable(str.substring(2, str.length() - 1), context);
        }
        return processTemplate(str, context);
    }

    private Object resolveVariable(String varName, Map<String, Object> context) {
        String[] parts = varName.split("\\.");
        Object current = context;
        for (String part : parts) {
            if (current instanceof Map) {
                current = ((Map<?, ?>) current).get(part);
            } else {
                return null;
            }
        }
        return current;
    }

    private String processTemplate(String template, Map<String, Object> context) {
        if (template == null) return null;
        String result = template;
        for (Map.Entry<String, Object> entry : context.entrySet()) {
            String placeholder = "${" + entry.getKey() + "}";
            if (result.contains(placeholder)) {
                result = result.replace(placeholder,
                        entry.getValue() != null ? entry.getValue().toString() : "");
            }
        }
        return result;
    }
}
