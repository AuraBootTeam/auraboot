package com.auraboot.framework.automation.executor.impl;

import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.executor.ActionExecutor;
import com.auraboot.framework.common.util.PinnedHttpRequests;
import com.auraboot.framework.common.util.SsrfValidator;
import com.auraboot.framework.exception.BusinessException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
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
    private static final int RESPONSE_BODY_PREVIEW_CHARS = 500;

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
        url = AutomationActionValueResolver.resolveString(url, context);
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("SEND_WEBHOOK action requires resolved url");
        }

        // Build the JSON body from the configured payload (Map or JSON string),
        // or fall back to the trigger context essentials.
        String bodyJson = buildPayloadJson(config, context);

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
                headers.forEach((k, v) -> requestBuilder.header(k,
                        AutomationActionValueResolver.resolveString(v, context)));
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

            return buildSuccessResult(url, statusCode, response.body());

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

    Map<String, Object> buildSuccessResult(String url, int statusCode, String responseBody) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("deliveryMode", "direct_http");
        result.put("statusCode", statusCode);
        result.put("url", url);
        if (responseBody != null) {
            result.put("responseBodyPreview", previewResponseBody(responseBody));
            result.put("responseBytes", responseBody.getBytes(StandardCharsets.UTF_8).length);
        }
        return result;
    }

    private String previewResponseBody(String responseBody) {
        if (responseBody.length() <= RESPONSE_BODY_PREVIEW_CHARS) {
            return responseBody;
        }
        return responseBody.substring(0, RESPONSE_BODY_PREVIEW_CHARS) + "...";
    }

    /**
     * Serialize the configured payload to a JSON body, resolving {@code ${...}}
     * placeholders against the run context. Accepts a Map (per-value substitution,
     * preserving non-string types) or a JSON String (partial template substitution),
     * and falls back to the trigger context when no payload is configured.
     */
    String buildPayloadJson(Map<String, Object> config, Map<String, Object> context) {
        try {
            Object payloadConfig = config.get("payload");
            String eventType = resolveEventType(config, context);
            if (payloadConfig instanceof Map<?, ?> mapPayload) {
                Map<String, Object> processed = AutomationActionValueResolver.resolveMap(mapPayload, context);
                applyEventType(processed, eventType);
                return objectMapper.writeValueAsString(processed);
            }
            if (payloadConfig instanceof String strPayload) {
                // A JSON string with ${...} placeholders → substitute then send verbatim.
                String processed = AutomationActionValueResolver.resolveString(strPayload, context);
                if (eventType == null) {
                    return processed;
                }
                try {
                    Map<String, Object> parsed = objectMapper.readValue(processed, new TypeReference<>() {});
                    applyEventType(parsed, eventType);
                    return objectMapper.writeValueAsString(parsed);
                } catch (Exception ignored) {
                    return processed;
                }
            }
            // Default: emit the trigger context essentials.
            Map<String, Object> def = new LinkedHashMap<>();
            applyEventType(def, eventType);
            def.put("automationPid", context.get("automationPid"));
            def.put("recordPid", context.get("recordPid"));
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

    private String resolveEventType(Map<String, Object> config, Map<String, Object> context) {
        Object value = AutomationActionValueResolver.resolveValue(config.get("eventType"), context);
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return String.valueOf(value);
    }

    private void applyEventType(Map<String, Object> body, String eventType) {
        if (eventType != null && !eventType.isBlank()) {
            body.put("eventType", eventType);
        }
    }

}
