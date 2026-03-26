package com.auraboot.framework.connector.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.crypto.FieldEncryptionService;
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
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

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

    private final ApiConnectorMapper connectorMapper;
    private final ApiConnectorEndpointMapper endpointMapper;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
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

        // Validate URL to prevent SSRF attacks
        SsrfValidator.validateUrl(url);

        HttpMethod method = HttpMethod.valueOf(endpoint.getMethod());

        HttpHeaders headers = buildHeaders(connector);
        HttpEntity<Map<String, Object>> request;

        if (method == HttpMethod.GET || method == HttpMethod.DELETE) {
            request = new HttpEntity<>(headers);
        } else {
            request = new HttpEntity<>(params, headers);
        }

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, method, request, Map.class);
            log.debug("API call: connector={}, endpoint={}, status={}",
                    connectorPid, endpointCode, response.getStatusCode());
            return response.getBody() != null ? response.getBody() : Map.of();
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
            // Validate URL to prevent SSRF attacks
            SsrfValidator.validateUrl(connector.getBaseUrl());

            HttpHeaders headers = buildHeaders(connector);
            HttpEntity<Void> request = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    connector.getBaseUrl(), HttpMethod.HEAD, request, String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (IllegalArgumentException e) {
            log.warn("SSRF blocked: connection test for connector {} rejected: {}", connectorPid, e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("Connection test failed for connector {}: {}", connectorPid, e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private HttpHeaders buildHeaders(ApiConnector connector) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Apply default headers
        if (connector.getDefaultHeaders() != null) {
            try {
                Map<String, String> defaultHeaders = objectMapper.readValue(
                        connector.getDefaultHeaders(),
                        objectMapper.getTypeFactory().constructMapType(Map.class, String.class, String.class));
                defaultHeaders.forEach(headers::set);
            } catch (Exception e) {
                log.warn("Failed to parse default headers for connector {}", connector.getPid());
            }
        }

        // Apply authentication
        applyAuth(headers, connector);
        return headers;
    }

    private void applyAuth(HttpHeaders headers, ApiConnector connector) {
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
                    headers.set(keyHeader, authConfig.getOrDefault("apiKey", ""));
                    break;
                case "bearer":
                    headers.setBearerAuth(authConfig.getOrDefault("token", ""));
                    break;
                case "basic":
                    headers.setBasicAuth(
                            authConfig.getOrDefault("username", ""),
                            authConfig.getOrDefault("password", ""));
                    break;
            }
        } catch (Exception e) {
            log.warn("Failed to apply auth for connector {}: {}", connector.getPid(), e.getMessage());
        }
    }
}
