package com.auraboot.framework.connector.jdbc.service;

import com.auraboot.framework.connector.jdbc.dto.JdbcConnectorCreateRequest;
import com.auraboot.framework.connector.jdbc.dto.JdbcEndpointCreateRequest;
import com.auraboot.framework.connector.jdbc.entity.JdbcConnector;
import com.auraboot.framework.connector.jdbc.entity.JdbcConnectorEndpoint;

import java.util.List;
import java.util.Map;

public interface JdbcConnectorService {
    JdbcConnector create(JdbcConnectorCreateRequest request);
    JdbcConnector getByPid(String pid);
    List<JdbcConnector> listAll();
    JdbcConnector update(String pid, JdbcConnectorCreateRequest request);
    void delete(String pid);

    JdbcConnectorEndpoint addEndpoint(String connectorPid, JdbcEndpointCreateRequest request);
    List<JdbcConnectorEndpoint> listEndpoints(String connectorPid);

    /** Invoke an endpoint. Result shape: {"rows": List<Map>} for query, {"affectedRows": int} for update. */
    Map<String, Object> invoke(String connectorPid, String endpointCode, Map<String, Object> params);

    /** Test connectivity. Returns false on transport-level errors; throws IllegalArgumentException for missing connector. */
    boolean testConnection(String connectorPid);
}
