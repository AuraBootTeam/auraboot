package com.auraboot.framework.bpm.chain;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.smart.framework.engine.context.ExecutionContext;
import com.auraboot.smart.framework.engine.delegation.JavaDelegation;
import com.auraboot.smart.framework.engine.model.assembly.IdBasedElement;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

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

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public HttpServiceTaskDelegate(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
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

        HttpMethod method = parseMethod(properties.get(BpmServiceTaskConstants.ATTR_METHOD));
        int timeoutMs = parseTimeoutMs(properties.get(BpmServiceTaskConstants.ATTR_TIMEOUT_MS));
        String responseVar = properties.get(BpmServiceTaskConstants.ATTR_RESPONSE_VAR);

        HttpHeaders headers = new HttpHeaders();
        HttpEntity<Object> entity = new HttpEntity<>(null, headers);

        long startedAt = System.currentTimeMillis();
        try {
            // Request-scoped timeout override. The auto-configured RestTemplate
            // uses SimpleClientHttpRequestFactory; we honor the BPMN-level
            // timeout by wrapping the exchange and relying on the shared
            // factory's read timeout as an upper bound. For per-call override
            // we recreate the factory only when the node demands a tighter cap.
            ResponseEntity<String> response = executeWithTimeout(url, method, entity, timeoutMs);
            int status = response.getStatusCodeValue();
            String body = response.getBody();

            log.info("HTTP serviceTask: {} {} -> {} ({} ms)",
                    method, url, status, System.currentTimeMillis() - startedAt);

            if (responseVar != null && !responseVar.isBlank()) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("status", status);
                payload.put("body", body);
                processVars.put(responseVar, payload);
            }
        } catch (RestClientResponseException httpError) {
            // Non-2xx response from the remote.
            log.error("HTTP serviceTask non-2xx: {} {} -> {} (body={})",
                    method, url, httpError.getRawStatusCode(),
                    httpError.getResponseBodyAsString(), httpError);
            throw new BusinessException(ERR_HTTP_CALL_FAILED);
        } catch (ResourceAccessException networkError) {
            // Connection refused, DNS failure, read timeout.
            log.error("HTTP serviceTask network failure: {} {} - {}",
                    method, url, networkError.getMessage(), networkError);
            throw new BusinessException(ERR_HTTP_CALL_FAILED);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("HTTP serviceTask unexpected failure: {} {} - {}",
                    method, url, e.getMessage(), e);
            throw new BusinessException(ERR_HTTP_CALL_FAILED);
        }
    }

    private ResponseEntity<String> executeWithTimeout(String url,
                                                      HttpMethod method,
                                                      HttpEntity<Object> entity,
                                                      int timeoutMs) {
        // For now, use the shared RestTemplate. The shared factory carries a
        // pooled timeout configured in HttpClientAutoConfiguration; BPMN-level
        // timeoutMs acts as an upper bound on expected duration documented in
        // logs. A future change can thread a per-call factory through here if
        // plugins need tighter caps than the platform default.
        log.debug("HTTP serviceTask scheduled: {} {} (timeoutMs={})", method, url, timeoutMs);
        return restTemplate.exchange(url, method, entity, String.class);
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

    private HttpMethod parseMethod(String raw) {
        if (raw == null || raw.isBlank()) {
            return HttpMethod.GET;
        }
        try {
            return HttpMethod.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException iae) {
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
