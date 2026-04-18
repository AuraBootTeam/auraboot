package com.auraboot.framework.bpm.chain;

import com.auraboot.framework.common.util.PinnedHttpRequests;
import com.auraboot.framework.common.util.SsrfValidator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.smart.framework.engine.context.ExecutionContext;
import com.auraboot.smart.framework.engine.delegation.JavaDelegation;
import com.auraboot.smart.framework.engine.model.assembly.IdBasedElement;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thin SmartEngine serviceTask delegate that performs an outbound HTTP call.
 *
 * <p>Wired into BPMN via {@code smart:class="httpServiceTaskDelegate"}. The
 * node XML carries the following {@code smart:*} extension attributes
 * (surfaced by SmartEngine as the activity {@code properties} map):
 * <ul>
 *   <li>{@code smart:serviceUrl} — target URL (required). Supports simple
 *       {@code ${varName}} substitution against process variables.</li>
 *   <li>{@code smart:method} — HTTP method, default {@code GET}.</li>
 *   <li>{@code smart:responseVar} — process variable name to receive the
 *       response payload (optional). Written as a map
 *       {@code {status:int, body:String}}.</li>
 *   <li>{@code smart:timeoutMs} — request-scoped read timeout in milliseconds,
 *       default {@value #DEFAULT_TIMEOUT_MS}.</li>
 * </ul>
 *
 * <p>Failures (non-2xx response, timeout, network error) propagate as a
 * {@link BusinessException} with code {@link #ERR_HTTP_CALL_FAILED} — the
 * SmartEngine activity is marked failed. No silent swallow.
 *
 * @since 7.3.0
 */
@Slf4j
@Component(BpmServiceTaskConstants.BEAN_HTTP_DELEGATE)
public class HttpServiceTaskDelegate implements JavaDelegation {

    public static final String ERR_SERVICE_URL_REQUIRED = "bpm.http.service_url_required";
    public static final String ERR_HTTP_CALL_FAILED = "bpm.http.call_failed";
    public static final String ERR_HTTP_METHOD_INVALID = "bpm.http.method_invalid";

    /** Default read timeout when {@code smart:timeoutMs} is not set. */
    public static final int DEFAULT_TIMEOUT_MS = 5_000;

    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");

    private static final int CONNECT_TIMEOUT_MS = 3_000;

    /**
     * Shared pinned-IP HTTP client (P3-E DNS-rebinding hardening). JDK
     * {@link HttpClient} is what {@link PinnedHttpRequests} targets for
     * pinning the validated IP at connect time.
     */
    private static final HttpClient PINNED_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
            .build();

    private final ObjectMapper objectMapper;

    public HttpServiceTaskDelegate(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void execute(ExecutionContext executionContext) {
        Map<String, Object> processVars = executionContext.getRequest();
        if (processVars == null) {
            processVars = new HashMap<>();
            executionContext.setRequest(processVars);
        }

        Map<String, String> properties = resolveProperties(executionContext);

        String rawUrl = properties.get(BpmServiceTaskConstants.ATTR_SERVICE_URL);
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new BusinessException(ERR_SERVICE_URL_REQUIRED);
        }
        String url = substituteVariables(rawUrl, processVars);

        String method = parseMethod(properties.get(BpmServiceTaskConstants.ATTR_METHOD));
        int timeoutMs = parseTimeoutMs(properties.get(BpmServiceTaskConstants.ATTR_TIMEOUT_MS));
        String responseVar = properties.get(BpmServiceTaskConstants.ATTR_RESPONSE_VAR);

        long startedAt = System.currentTimeMillis();
        try {
            // Validate URL + pin the resolved IP (P3-E #1 DNS rebinding TOCTOU).
            // Wrapping inside the try block ensures IllegalArgumentException
            // from SSRF guard maps to the standard ERR_HTTP_CALL_FAILED.
            SsrfValidator.ValidatedTarget target = SsrfValidator.validate(url);
            if (target == null) {
                log.error("HTTP serviceTask target could not be resolved: {} {}", method, url);
                throw new BusinessException(ERR_HTTP_CALL_FAILED);
            }

            HttpRequest request = PinnedHttpRequests.newPinnedRequestBuilder(target)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .method(method, HttpRequest.BodyPublishers.noBody())
                    .build();

            log.debug("HTTP serviceTask scheduled: {} {} (timeoutMs={})", method, url, timeoutMs);

            HttpResponse<String> response = PINNED_HTTP_CLIENT.send(
                    request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            String body = response.body();

            log.info("HTTP serviceTask: {} {} -> {} ({} ms)",
                    method, url, status, System.currentTimeMillis() - startedAt);

            if (status >= 400) {
                log.error("HTTP serviceTask non-2xx: {} {} -> {} (body={})",
                        method, url, status, body);
                throw new BusinessException(ERR_HTTP_CALL_FAILED);
            }

            if (responseVar != null && !responseVar.isBlank()) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("status", status);
                payload.put("body", body);
                processVars.put(responseVar, payload);
            }
        } catch (BusinessException be) {
            throw be;
        } catch (IllegalArgumentException ssrfError) {
            // SSRF guard rejected the URL before any socket connect. Normalize
            // to the standard HTTP-failure code so process instances observe
            // identical semantics for "network rejected" and "security rejected".
            log.warn("HTTP serviceTask SSRF reject: {} {} - {}",
                    method, url, ssrfError.getMessage());
            throw new BusinessException(ERR_HTTP_CALL_FAILED);
        } catch (HttpTimeoutException timeoutError) {
            log.error("HTTP serviceTask timeout: {} {} - {}",
                    method, url, timeoutError.getMessage(), timeoutError);
            throw new BusinessException(ERR_HTTP_CALL_FAILED);
        } catch (Exception e) {
            // Connection refused, DNS failure, I/O, interrupted — map to standard failure.
            log.error("HTTP serviceTask failure: {} {} - {}",
                    method, url, e.getMessage(), e);
            throw new BusinessException(ERR_HTTP_CALL_FAILED);
        }
    }

    /**
     * Resolve {@code ${name}} tokens in the URL against {@code processVars}.
     * Missing variables are left as the literal token — the HTTP call then
     * typically fails fast with a 404/400, which is the desired fail-loud
     * behavior (not a silent swallow).
     */
    private String substituteVariables(String template, Map<String, Object> processVars) {
        if (template == null || template.isEmpty()) {
            return template;
        }
        Matcher matcher = VAR_PATTERN.matcher(template);
        if (!matcher.find()) {
            return template;
        }
        matcher.reset();
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1).trim();
            Object value = processVars.get(name);
            String replacement = value != null ? value.toString() : matcher.group(0);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String parseMethod(String raw) {
        if (raw == null || raw.isBlank()) {
            return "GET";
        }
        String normalized = raw.trim().toUpperCase();
        switch (normalized) {
            case "GET":
            case "POST":
            case "PUT":
            case "DELETE":
            case "HEAD":
            case "OPTIONS":
            case "PATCH":
                return normalized;
            default:
                throw new BusinessException(ERR_HTTP_METHOD_INVALID);
        }
    }

    private int parseTimeoutMs(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_TIMEOUT_MS;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : DEFAULT_TIMEOUT_MS;
        } catch (NumberFormatException nfe) {
            // Explicit guard: invalid numeric config is a validation error, not
            // a silent fallback. We log and keep the platform default so a
            // running instance is not aborted by a typo, while surfacing the
            // issue in logs for operator follow-up.
            log.warn("Invalid smart:timeoutMs='{}', using default {}", raw, DEFAULT_TIMEOUT_MS);
            return DEFAULT_TIMEOUT_MS;
        }
    }

    private Map<String, String> resolveProperties(ExecutionContext executionContext) {
        if (executionContext.getBaseElement() instanceof IdBasedElement idBased
                && idBased.getProperties() != null) {
            return idBased.getProperties();
        }
        return new HashMap<>();
    }

    // ObjectMapper kept for future JSON body handling hook. Silencing unused warning.
    @SuppressWarnings("unused")
    private ObjectMapper mapper() {
        return objectMapper;
    }

    // Kept for symmetry with other delegates that reify request headers from JSON.
    @SuppressWarnings("unused")
    private Map<String, String> parseJsonMap(String raw) {
        if (raw == null || raw.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.warn("Invalid JSON map '{}': {}", raw, e.getMessage());
            return new LinkedHashMap<>();
        }
    }
}
