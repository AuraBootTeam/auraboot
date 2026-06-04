package com.auraboot.framework.iot.broker;

import com.auraboot.framework.meta.exception.MetaServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal EMQX 5 management-API client for device authn/ACL reconciliation.
 *
 * <p>Operations follow the EMQX 5 HTTP API surface
 * (<a href="https://docs.emqx.com/en/emqx/latest/admin/api.html">docs</a>):
 * <ul>
 *   <li>{@code PUT /api/v5/authentication/{id}/users/{username}} — upsert user</li>
 *   <li>{@code DELETE /api/v5/authentication/{id}/users/{username}} — revoke</li>
 *   <li>{@code POST /api/v5/authorization/sources/built_in_database/rules/users} — push ACL</li>
 * </ul>
 *
 * <p>Behaviour:
 * <ul>
 *   <li>{@code 2xx}: success.</li>
 *   <li>{@code 4xx}: thrown as {@link MetaServiceException} immediately (no retry).</li>
 *   <li>{@code 5xx} or network: retried up to {@link #MAX_RETRIES} times; final
 *       failure surfaces as {@link MetaServiceException} so caller can route to dead-letter.</li>
 * </ul>
 *
 * <p>When {@code iot.emqx.enabled=false} (default), all calls become no-ops.
 * This keeps unit tests and OSS profiles from accidentally reaching a broker.
 *
 * @since 2.6.0
 */
@Slf4j
@Service
@EnableConfigurationProperties(EmqxAclProperties.class)
public class EmqxAclSyncService {

    static final int MAX_RETRIES = 3;

    private final EmqxAclProperties props;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    public EmqxAclSyncService(EmqxAclProperties props,
                              ObjectMapper objectMapper,
                              WebClient.Builder webClientBuilder) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.webClient = buildClient(webClientBuilder);
    }

    private WebClient buildClient(WebClient.Builder builder) {
        if (props.getBaseUrl() == null || props.getBaseUrl().isBlank()) {
            return builder.build();
        }
        String creds = (props.getApiKey() == null ? "" : props.getApiKey())
                + ":" + (props.getApiSecret() == null ? "" : props.getApiSecret());
        String basic = Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.UTF_8));
        return builder
                .baseUrl(props.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .build();
    }

    /** Upsert a device principal on the broker. ACL patterns are pushed as a follow-up. */
    public void syncDeviceUser(long tenantId, String username, String passwordOrPublicKey, List<String> aclPatterns) {
        if (!props.isEnabled()) {
            log.debug("[emqx] disabled — skipping syncDeviceUser tenant={} user={}", tenantId, username);
            return;
        }
        validateBasics(tenantId, username);

        // EMQX built-in-DB user upsert: POST /users creates (user_id in body); on 409
        // the user already exists, so PUT /users/{user_id} updates (NO user_id in body —
        // it lives in the path, and EMQX rejects it as an unknown field otherwise).
        String authBase = "/api/v5/authentication/" + uri(props.getAuthenticatorId());
        Map<String, Object> createBody = new LinkedHashMap<>();
        createBody.put("user_id", username);
        createBody.put("password", passwordOrPublicKey);
        createBody.put("is_superuser", false);
        try {
            callWithRetry("POST", authBase + "/users", createBody, "upsert device user");
        } catch (MetaServiceException e) {
            if (e.getMessage() != null && e.getMessage().contains("status=409")) {
                Map<String, Object> updateBody = new LinkedHashMap<>();
                updateBody.put("password", passwordOrPublicKey);
                updateBody.put("is_superuser", false);
                callWithRetry("PUT", authBase + "/users/" + uri(username), updateBody, "upsert device user");
            } else {
                throw e;
            }
        }

        if (aclPatterns != null && !aclPatterns.isEmpty()) {
            pushAclRules(username, aclPatterns);
        }
    }

    /** Delete a device principal on the broker. 404 is treated as a successful no-op. */
    public void revokeDeviceUser(long tenantId, String username) {
        if (!props.isEnabled()) {
            log.debug("[emqx] disabled — skipping revokeDeviceUser tenant={} user={}", tenantId, username);
            return;
        }
        validateBasics(tenantId, username);
        String path = "/api/v5/authentication/" + uri(props.getAuthenticatorId())
                + "/users/" + uri(username);
        try {
            callWithRetry("DELETE", path, null, "revoke device user");
        } catch (MetaServiceException e) {
            // Swallow 404 — already gone.
            if (e.getMessage() != null && e.getMessage().contains("status=404")) {
                log.info("[emqx] revoke {} returned 404 (already absent) — treated as success", username);
                return;
            }
            throw e;
        }
    }

    /**
     * Tenant-scope full reconciliation hook. The platform-side caller passes
     * the resolved username list; this method does not enumerate the device
     * table itself (separation of concerns — accessor layer handles that).
     */
    public void syncTenantAcl(long tenantId, List<DevicePrincipal> principals) {
        if (!props.isEnabled()) {
            log.debug("[emqx] disabled — skipping syncTenantAcl tenant={}", tenantId);
            return;
        }
        if (tenantId <= 0) {
            throw new IllegalArgumentException("tenantId must be > 0");
        }
        if (principals == null) {
            return;
        }
        for (DevicePrincipal p : principals) {
            syncDeviceUser(tenantId, p.username(), p.secret(), p.aclPatterns());
        }
    }

    private void pushAclRules(String username, List<String> aclPatterns) {
        List<Map<String, Object>> rules = new java.util.ArrayList<>();
        for (String topic : aclPatterns) {
            if (topic == null || topic.isBlank()) continue;
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("action", "all");
            rule.put("permission", "allow");
            rule.put("topic", topic);
            rules.add(rule);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("rules", rules);
        callWithRetry("POST",
                "/api/v5/authorization/sources/built_in_database/rules/users",
                List.of(body),
                "push acl rules");
    }

    private void callWithRetry(String method, String path, Object body, String op) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                doCall(method, path, body);
                return;
            } catch (WebClientResponseException e) {
                int status = e.getStatusCode().value();
                if (status >= 400 && status < 500) {
                    // 4xx is a client/configuration error; do not retry.
                    throw new MetaServiceException(
                            "iot.error.emqx_" + op.replace(' ', '_') + " status=" + status
                                    + " body=" + truncate(e.getResponseBodyAsString()), e);
                }
                last = e;
                log.warn("[emqx] {} {} attempt {}/{} status={}", method, path, attempt, MAX_RETRIES, status);
            } catch (RuntimeException e) {
                last = e;
                log.warn("[emqx] {} {} attempt {}/{} error={}", method, path, attempt, MAX_RETRIES, e.toString());
            }
            sleepBackoff(attempt);
        }
        throw new MetaServiceException("iot.error.emqx_" + op.replace(' ', '_') + " exhausted retries", last);
    }

    private void doCall(String method, String path, Object body) {
        // Build an absolute URI by prepending the configured base URL to the (already
        // percent-encoded) path. WebClient's uri(URI) overload uses the URI as-is —
        // which both (a) honours the host/port here and (b) preserves our single
        // encoding (the authenticatorId's ':' was encoded once by uri()). Passing the
        // relative path alone made WebClient ignore iot.emqx.base-url (→ localhost:80);
        // passing it via uri(String) double-encoded the path (':' → %3A → %253A).
        WebClient.RequestBodySpec req = webClient
                .method(org.springframework.http.HttpMethod.valueOf(method))
                .uri(URI.create(props.getBaseUrl() + path));
        if (body != null) {
            req.bodyValue(toJson(body));
        }
        req.retrieve()
                .toBodilessEntity()
                .block(Duration.ofMillis(props.getTimeoutMs()));
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new MetaServiceException("iot.error.emqx_payload_encode_failed", e);
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 256 ? s.substring(0, 256) + "..." : s;
    }

    private static String uri(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static void sleepBackoff(int attempt) {
        try {
            Thread.sleep(Math.min(500L * attempt, 2000L));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static void validateBasics(long tenantId, String username) {
        if (tenantId <= 0) {
            throw new IllegalArgumentException("tenantId must be > 0");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }
    }

    /** Snapshot for tenant-scope sync. */
    public record DevicePrincipal(String username, String secret, List<String> aclPatterns) {
    }
}
