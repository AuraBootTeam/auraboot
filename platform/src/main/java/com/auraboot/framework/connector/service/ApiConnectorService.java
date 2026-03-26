package com.auraboot.framework.connector.service;

import com.auraboot.framework.connector.dto.ApiConnectorCreateRequest;
import com.auraboot.framework.connector.entity.ApiConnector;

import java.util.List;
import java.util.Map;

/**
 * Service for managing external API connectors.
 *
 * @since 5.1.0
 */
public interface ApiConnectorService {

    ApiConnector create(ApiConnectorCreateRequest request);

    ApiConnector getByPid(String pid);

    List<ApiConnector> listAll();

    ApiConnector update(String pid, ApiConnectorCreateRequest request);

    void delete(String pid);

    /**
     * Invoke an external API endpoint.
     */
    Map<String, Object> invoke(String connectorPid, String endpointCode, Map<String, Object> params);

    /**
     * Test connectivity to a connector's base URL.
     */
    boolean testConnection(String connectorPid);
}
