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
 * Executor for CALL_API action type.
 * Makes HTTP requests to external APIs.
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CallApiExecutor implements ActionExecutor {

    private final ObjectMapper objectMapper;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public Object execute(AutomationAction action, Map<String, Object> context) {
        Map<String, Object> config = action.getConfig();
        if (config == null) {
            throw new IllegalArgumentException("CALL_API action requires config");
        }

        String url = (String) config.get("url");
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("CALL_API action requires url");
        }

        String method = (String) config.getOrDefault("method", "post");
        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) config.get("headers");
        Object body = config.get("body");
        int timeoutSeconds = config.containsKey("timeoutSeconds")
                ? ((Number) config.get("timeoutSeconds")).intValue() : 30;

        // Process variable substitution in URL
        url = processTemplate(url, context);

        // Validate URL + capture resolved IP so the HTTP call uses the same IP
        // that passed the block-list check (P3-E #1 DNS rebinding TOCTOU).
        SsrfValidator.ValidatedTarget target = SsrfValidator.validate(url);
        if (target == null) {
            throw new IllegalArgumentException("CALL_API target could not be resolved: " + url);
        }

        log.info("Calling API: method={}, url={}", method, url);

        try {
            HttpRequest.Builder requestBuilder = PinnedHttpRequests.newPinnedRequestBuilder(target)
                    .timeout(Duration.ofSeconds(timeoutSeconds));

            // Set headers
            if (headers != null) {
                headers.forEach((k, v) -> requestBuilder.header(k, processTemplate(v, context)));
            }
            requestBuilder.header("Content-Type", "application/json");

            // Set body
            String bodyJson = null;
            if (body != null) {
                if (body instanceof String strBody) {
                    bodyJson = processTemplate(strBody, context);
                } else {
                    bodyJson = objectMapper.writeValueAsString(body);
                }
            }

            switch (method.toUpperCase()) {
                case "get" -> requestBuilder.GET();
                case "post" -> requestBuilder.POST(bodyJson != null
                        ? HttpRequest.BodyPublishers.ofString(bodyJson)
                        : HttpRequest.BodyPublishers.noBody());
                case "put" -> requestBuilder.PUT(bodyJson != null
                        ? HttpRequest.BodyPublishers.ofString(bodyJson)
                        : HttpRequest.BodyPublishers.noBody());
                case "delete" -> requestBuilder.DELETE();
                default -> throw new IllegalArgumentException("Unsupported HTTP method: " + method);
            }

            HttpResponse<String> response = HTTP_CLIENT.send(
                    requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

            int statusCode = response.statusCode();
            log.info("API response: status={}, url={}", statusCode, url);

            if (statusCode >= 400) {
                throw new BusinessException("API call failed with status " + statusCode + ": " + response.body());
            }

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("statusCode", statusCode);
            try {
                result.put("response", objectMapper.readValue(response.body(), Map.class));
            } catch (Exception e) {
                result.put("response", response.body());
            }
            return result;

        } catch (Exception e) {
            throw new BusinessException("API call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean supports(String actionType) {
        return "call_api".equals(actionType);
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
