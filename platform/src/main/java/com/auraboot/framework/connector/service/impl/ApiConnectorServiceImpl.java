package com.auraboot.framework.connector.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.crypto.FieldEncryptionService;
import com.auraboot.framework.common.util.PinnedHttpRequests;
import com.auraboot.framework.common.util.SsrfValidator;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.connector.dto.ApiConnectorCreateRequest;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.connector.entity.ApiConnector;
import com.auraboot.framework.connector.entity.ApiConnectorEndpoint;
import com.auraboot.framework.connector.mapper.ApiConnectorEndpointMapper;
import com.auraboot.framework.connector.mapper.ApiConnectorMapper;
import com.auraboot.framework.connector.service.ApiConnectorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Implementation of ApiConnectorService.
 *
 * @since 5.1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiConnectorServiceImpl implements ApiConnectorService {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 30_000;

    /**
     * Shared pinned-IP HTTP client (P3-E DNS-rebinding hardening). JDK
     * {@link HttpClient} is what {@link PinnedHttpRequests} targets for
     * pinning the validated IP at connect time.
     */
    private static final HttpClient PINNED_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
            .build();

    private final ApiConnectorMapper connectorMapper;
    private final ApiConnectorEndpointMapper endpointMapper;
    private final ObjectMapper objectMapper;
    private final FieldEncryptionService fieldEncryptionService;

    @Override
    @Transactional
    public ApiConnector create(ApiConnectorCreateRequest request) {
        // Validate base URL to prevent SSRF at creation time
        SsrfValidator.validateUrl(request.getBaseUrl());

        Long tenantId = MetaContext.getCurrentTenantId();

        ApiConnector entity = new ApiConnector();
        entity.setTenantId(tenantId);
        entity.setPid(UniqueIdGenerator.generate());
        entity.setName(request.getName());
        entity.setBaseUrl(request.getBaseUrl());
        entity.setAuthType(request.getAuthType());
        entity.setAuthConfig(fieldEncryptionService.encrypt(request.getAuthConfig()));
        entity.setDefaultHeaders(request.getDefaultHeaders());
        entity.setTimeoutMs(request.getTimeoutMs());
        entity.setRetryPolicy(request.getRetryPolicy());
        entity.setEnabled(request.isEnabled());
        entity.setCreatedAt(java.time.Instant.now());
        entity.setUpdatedAt(java.time.Instant.now());

        connectorMapper.insert(entity);
        log.info("Created API connector: pid={}, name={}, url={}", entity.getPid(), request.getName(), request.getBaseUrl());
        return entity;
    }

    @Override
    public ApiConnector getByPid(String pid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return connectorMapper.findByPid(tenantId, pid);
    }

    @Override
    public List<ApiConnector> listAll() {
        Long tenantId = MetaContext.getCurrentTenantId();
        return connectorMapper.findByTenant(tenantId);
    }

    @Override
    @Transactional
    public ApiConnector update(String pid, ApiConnectorCreateRequest request) {
        // Validate base URL to prevent SSRF at update time
        SsrfValidator.validateUrl(request.getBaseUrl());

        Long tenantId = MetaContext.getCurrentTenantId();
        ApiConnector existing = connectorMapper.findByPid(tenantId, pid);
        if (existing == null) {
            throw new IllegalArgumentException("API connector not found: " + pid);
        }

        existing.setName(request.getName());
        existing.setBaseUrl(request.getBaseUrl());
        existing.setAuthType(request.getAuthType());
        existing.setAuthConfig(fieldEncryptionService.encrypt(request.getAuthConfig()));
        existing.setDefaultHeaders(request.getDefaultHeaders());
        existing.setTimeoutMs(request.getTimeoutMs());
        existing.setRetryPolicy(request.getRetryPolicy());
        existing.setEnabled(request.isEnabled());

        connectorMapper.updateById(existing);
        log.info("Updated API connector: pid={}", pid);
        return existing;
    }

    @Override
    @Transactional
    public void delete(String pid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        endpointMapper.deleteByConnector(pid);
        connectorMapper.deleteByPid(tenantId, pid);
        log.info("Deleted API connector: pid={}", pid);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> invoke(String connectorPid, String endpointCode, Map<String, Object> params) {
        Long tenantId = MetaContext.getCurrentTenantId();
        ApiConnector connector = connectorMapper.findByPid(tenantId, connectorPid);
        if (connector == null) {
            throw new IllegalArgumentException("API connector not found: " + connectorPid);
        }

        ApiConnectorEndpoint endpoint = endpointMapper.findByCode(connectorPid, endpointCode);
        if (endpoint == null) {
            throw new IllegalArgumentException("Endpoint not found: " + endpointCode);
        }

        String url = connector.getBaseUrl() + endpoint.getPath();

        // Validate URL + pin the resolved IP so the HTTP send cannot be
        // re-resolved (P3-E #1 DNS rebinding TOCTOU).
        SsrfValidator.ValidatedTarget target = SsrfValidator.validate(url);
        if (target == null) {
            throw new BusinessException("API connector target could not be resolved: " + url);
        }

        String method = endpoint.getMethod() == null ? "GET" : endpoint.getMethod().toUpperCase();
        int readTimeoutMs = connector.getTimeoutMs() != null
                ? connector.getTimeoutMs() : DEFAULT_READ_TIMEOUT_MS;

        HttpRequest.Builder builder = PinnedHttpRequests.newPinnedRequestBuilder(target)
                .timeout(Duration.ofMillis(readTimeoutMs));
        applyHeaders(builder, connector);

        String bodyJson = null;
        boolean bodyMethod = !"GET".equals(method) && !"DELETE".equals(method) && !"HEAD".equals(method);
        if (bodyMethod && params != null) {
            try {
                bodyJson = objectMapper.writeValueAsString(params);
            } catch (Exception e) {
                throw new BusinessException("Failed to serialize request body: " + e.getMessage(), e);
            }
        }

        HttpRequest.BodyPublisher publisher = bodyJson != null
                ? HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8)
                : HttpRequest.BodyPublishers.noBody();
        builder.method(method, publisher);

        try {
            HttpResponse<String> response = PINNED_HTTP_CLIENT.send(
                    builder.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            log.debug("API call: connector={}, endpoint={}, status={}",
                    connectorPid, endpointCode, status);
            if (status >= 400) {
                throw new BusinessException(
                        "API call failed with status " + status + ": " + response.body());
            }
            String body = response.body();
            if (body == null || body.isBlank()) {
                return Map.of();
            }
            try {
                return objectMapper.readValue(body, Map.class);
            } catch (Exception parseError) {
                log.warn("API response not JSON: connector={}, endpoint={}",
                        connectorPid, endpointCode);
                return Map.of("raw", body);
            }
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("API call failed: connector={}, endpoint={}, error={}",
                    connectorPid, endpointCode, e.getMessage());
            throw new BusinessException("API call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean testConnection(String connectorPid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        ApiConnector connector = connectorMapper.findByPid(tenantId, connectorPid);
        if (connector == null) {
            throw new IllegalArgumentException("API connector not found: " + connectorPid);
        }

        try {
            // Validate URL + pin the resolved IP (P3-E #1).
            SsrfValidator.ValidatedTarget target = SsrfValidator.validate(connector.getBaseUrl());
            if (target == null) {
                log.warn("Connection test: target could not be resolved: {}", connector.getBaseUrl());
                return false;
            }

            HttpRequest.Builder builder = PinnedHttpRequests.newPinnedRequestBuilder(target)
                    .timeout(Duration.ofMillis(DEFAULT_READ_TIMEOUT_MS))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody());
            applyHeaders(builder, connector);

            HttpResponse<Void> response = PINNED_HTTP_CLIENT.send(
                    builder.build(), HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            return status >= 200 && status < 300;
        } catch (IllegalArgumentException e) {
            log.warn("SSRF blocked: connection test for connector {} rejected: {}", connectorPid, e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("Connection test failed for connector {}: {}", connectorPid, e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private void applyHeaders(HttpRequest.Builder builder, ApiConnector connector) {
        builder.header("Content-Type", "application/json");

        // Apply default headers
        if (connector.getDefaultHeaders() != null) {
            try {
                Map<String, String> defaultHeaders = objectMapper.readValue(
                        connector.getDefaultHeaders(),
                        objectMapper.getTypeFactory().constructMapType(Map.class, String.class, String.class));
                defaultHeaders.forEach(builder::header);
            } catch (Exception e) {
                log.warn("Failed to parse default headers for connector {}", connector.getPid());
            }
        }

        // Apply authentication
        applyAuth(builder, connector);
    }

    private void applyAuth(HttpRequest.Builder builder, ApiConnector connector) {
        String authType = connector.getAuthType();
        if (authType == null || "none".equals(authType)) return;

        try {
            String decryptedConfig = fieldEncryptionService.decrypt(connector.getAuthConfig());
            Map<String, String> authConfig = decryptedConfig != null
                    ? objectMapper.readValue(decryptedConfig,
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, String.class))
                    : Map.of();

            switch (authType) {
                case "api_key":
                    String keyHeader = authConfig.getOrDefault("headerName", "X-API-Key");
                    builder.header(keyHeader, authConfig.getOrDefault("apiKey", ""));
                    break;
                case "bearer":
                    builder.header("Authorization",
                            "Bearer " + authConfig.getOrDefault("token", ""));
                    break;
                case "basic":
                    String creds = authConfig.getOrDefault("username", "") + ":"
                            + authConfig.getOrDefault("password", "");
                    String encoded = Base64.getEncoder().encodeToString(
                            creds.getBytes(StandardCharsets.UTF_8));
                    builder.header("Authorization", "Basic " + encoded);
                    break;
            }
        } catch (Exception e) {
            log.warn("Failed to apply auth for connector {}: {}", connector.getPid(), e.getMessage());
        }
    }
}
