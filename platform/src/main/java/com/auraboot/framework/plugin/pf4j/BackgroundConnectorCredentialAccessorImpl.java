package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.connector.entity.ApiConnector;
import com.auraboot.framework.connector.service.ApiConnectorService;
import com.auraboot.framework.plugin.extension.BackgroundConnectorCredentialAccessor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Default {@link BackgroundConnectorCredentialAccessor} implementation —
 * wraps {@link ApiConnectorService#getByPid} and projects the entity
 * into a stable credentials snapshot.
 *
 * <p>Returns empty when the pid is blank, the connector doesn't exist,
 * or the host throws. Plugins should treat empty as "no overlay
 * credentials" and fall back to their own defaults.
 *
 * @since 2.5.0
 */
@Slf4j
@Service
public class BackgroundConnectorCredentialAccessorImpl implements BackgroundConnectorCredentialAccessor {

    private static final TypeReference<Map<String, String>> HEADERS_TYPE = new TypeReference<>() { };

    private final ApiConnectorService connectorService;
    private final ObjectMapper objectMapper;

    public BackgroundConnectorCredentialAccessorImpl(ApiConnectorService connectorService,
                                                     ObjectMapper objectMapper) {
        this.connectorService = connectorService;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<ConnectorCredentials> lookupByPid(String connectorPid) {
        if (connectorPid == null || connectorPid.isBlank()) {
            return Optional.empty();
        }
        ApiConnector entity;
        try {
            entity = connectorService.getByPid(connectorPid);
        } catch (RuntimeException e) {
            log.debug("[connector-accessor] lookup failed pid={} : {}", connectorPid, e.getMessage());
            return Optional.empty();
        }
        if (entity == null) {
            return Optional.empty();
        }
        return Optional.of(new ConnectorCredentials(
                entity.getPid(),
                entity.getBaseUrl(),
                entity.getAuthType(),
                entity.getAuthConfig(),
                parseHeaders(entity.getDefaultHeaders())
        ));
    }

    private Map<String, String> parseHeaders(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            Map<String, String> parsed = objectMapper.readValue(rawJson, HEADERS_TYPE);
            return parsed == null ? Collections.emptyMap() : new LinkedHashMap<>(parsed);
        } catch (Exception e) {
            log.debug("[connector-accessor] defaultHeaders not JSON-string-map; ignoring");
            return Collections.emptyMap();
        }
    }
}
