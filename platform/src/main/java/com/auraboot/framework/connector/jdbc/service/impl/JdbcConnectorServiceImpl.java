package com.auraboot.framework.connector.jdbc.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.crypto.FieldEncryptionService;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.connector.jdbc.dto.JdbcConnectorCreateRequest;
import com.auraboot.framework.connector.jdbc.dto.JdbcEndpointCreateRequest;
import com.auraboot.framework.connector.jdbc.entity.JdbcConnector;
import com.auraboot.framework.connector.jdbc.entity.JdbcConnectorEndpoint;
import com.auraboot.framework.connector.jdbc.mapper.JdbcConnectorEndpointMapper;
import com.auraboot.framework.connector.jdbc.mapper.JdbcConnectorMapper;
import com.auraboot.framework.connector.jdbc.service.JdbcConnectorService;
import com.auraboot.framework.connector.jdbc.service.JdbcDataSourcePool;
import com.auraboot.framework.exception.BusinessException;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Implementation of JdbcConnectorService.
 * Handles CRUD for JDBC connectors and endpoints, plus query/update invocation
 * against target databases via HikariCP connection pools.
 *
 * @since 5.2.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JdbcConnectorServiceImpl implements JdbcConnectorService {

    private static final int MAX_ROWS = 10_000;
    private static final Pattern PARAM_PATTERN = Pattern.compile(":([A-Za-z_][A-Za-z0-9_]*)");

    private final JdbcConnectorMapper connectorMapper;
    private final JdbcConnectorEndpointMapper endpointMapper;
    private final JdbcDataSourcePool pool;
    private final FieldEncryptionService fieldEncryptionService;

    @Override
    @Transactional
    public JdbcConnector create(JdbcConnectorCreateRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();

        JdbcConnector entity = new JdbcConnector();
        entity.setTenantId(tenantId);
        entity.setPid(UniqueIdGenerator.generate());
        entity.setName(request.getName());
        entity.setJdbcUrl(request.getJdbcUrl());
        entity.setUsername(request.getUsername());
        entity.setPassword(fieldEncryptionService.encrypt(request.getPassword()));
        entity.setMaxPoolSize(request.getMaxPoolSize());
        entity.setConnectionTimeoutMs(request.getConnectionTimeoutMs());
        entity.setEnabled(request.getEnabled());
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());

        connectorMapper.insert(entity);
        log.info("Created JDBC connector: pid={}, name={}", entity.getPid(), entity.getName());
        return entity;
    }

    @Override
    public JdbcConnector getByPid(String pid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return connectorMapper.findByPid(tenantId, pid);
    }

    @Override
    public List<JdbcConnector> listAll() {
        return connectorMapper.findByTenant(MetaContext.getCurrentTenantId());
    }

    @Override
    @Transactional
    public JdbcConnector update(String pid, JdbcConnectorCreateRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        JdbcConnector existing = connectorMapper.findByPid(tenantId, pid);
        if (existing == null) {
            throw new IllegalArgumentException("connector not found: " + pid);
        }

        existing.setName(request.getName());
        existing.setJdbcUrl(request.getJdbcUrl());
        existing.setUsername(request.getUsername());
        existing.setPassword(fieldEncryptionService.encrypt(request.getPassword()));
        existing.setMaxPoolSize(request.getMaxPoolSize());
        existing.setConnectionTimeoutMs(request.getConnectionTimeoutMs());
        existing.setEnabled(request.getEnabled());
        existing.setUpdatedAt(Instant.now());

        connectorMapper.updateById(existing);
        pool.evict(pid);
        log.info("Updated JDBC connector: pid={}", pid);
        return existing;
    }

    @Override
    @Transactional
    public void delete(String pid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        endpointMapper.deleteByConnector(pid);
        connectorMapper.deleteByPid(tenantId, pid);
        pool.evict(pid);
        log.info("Deleted JDBC connector: pid={}", pid);
    }

    @Override
    @Transactional
    public JdbcConnectorEndpoint addEndpoint(String connectorPid, JdbcEndpointCreateRequest request) {
        // Validate connector exists before adding endpoint
        JdbcConnector connector = getByPid(connectorPid);
        if (connector == null) {
            throw new IllegalArgumentException("connector not found: " + connectorPid);
        }

        String code = request.getCode();
        if (endpointMapper.findByCode(connectorPid, code) != null) {
            throw new IllegalArgumentException("endpoint code already exists: " + code);
        }

        JdbcConnectorEndpoint endpoint = new JdbcConnectorEndpoint();
        endpoint.setConnectorPid(connectorPid);
        endpoint.setCode(code);
        endpoint.setName(request.getName());
        endpoint.setOperation(request.getOperation().toLowerCase());
        endpoint.setSqlTemplate(request.getSqlTemplate());

        endpointMapper.insert(endpoint);
        log.info("Added endpoint: connectorPid={}, code={}", connectorPid, code);
        return endpoint;
    }

    @Override
    public List<JdbcConnectorEndpoint> listEndpoints(String connectorPid) {
        return endpointMapper.findByConnector(connectorPid);
    }

    @Override
    public Map<String, Object> invoke(String connectorPid, String endpointCode, Map<String, Object> params) {
        JdbcConnector connector = getByPid(connectorPid);
        if (connector == null) {
            throw new IllegalArgumentException("connector not found: " + connectorPid);
        }

        JdbcConnectorEndpoint endpoint = endpointMapper.findByCode(connectorPid, endpointCode);
        if (endpoint == null) {
            throw new IllegalArgumentException("endpoint not found: " + endpointCode);
        }

        JdbcConnector decryptedConnector = decrypted(connector);
        HikariDataSource dataSource = pool.acquire(decryptedConnector);

        // Parse :name placeholders and replace with ? for PreparedStatement
        String sqlTemplate = endpoint.getSqlTemplate();
        List<String> paramNames = new ArrayList<>();
        Matcher matcher = PARAM_PATTERN.matcher(sqlTemplate);
        StringBuffer resolvedSql = new StringBuffer();
        while (matcher.find()) {
            paramNames.add(matcher.group(1));
            matcher.appendReplacement(resolvedSql, "?");
        }
        matcher.appendTail(resolvedSql);
        String sql = resolvedSql.toString();

        try (var connection = dataSource.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {

            // Bind parameters in order of appearance
            for (int i = 0; i < paramNames.size(); i++) {
                stmt.setObject(i + 1, params != null ? params.get(paramNames.get(i)) : null);
            }

            String operation = endpoint.getOperation();
            if ("query".equals(operation)) {
                try (ResultSet rs = stmt.executeQuery()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int colCount = meta.getColumnCount();
                    List<Map<String, Object>> rows = new ArrayList<>();

                    while (rs.next()) {
                        if (rows.size() >= MAX_ROWS) {
                            log.warn("JDBC invoke MAX_ROWS={} cap reached: connectorPid={}, endpointCode={}",
                                    MAX_ROWS, connectorPid, endpointCode);
                            break;
                        }
                        Map<String, Object> row = new LinkedHashMap<>(colCount);
                        for (int i = 1; i <= colCount; i++) {
                            row.put(meta.getColumnLabel(i), rs.getObject(i));
                        }
                        rows.add(row);
                    }
                    return Map.of("rows", rows);
                }
            } else {
                // update operation (INSERT / UPDATE / DELETE)
                int affected = stmt.executeUpdate();
                return Map.of("affectedRows", affected);
            }
        } catch (SQLException e) {
            // Wrap raw JDBC exceptions so callers get a consistent error type
            throw new BusinessException("JDBC invocation failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean testConnection(String connectorPid) {
        JdbcConnector connector = getByPid(connectorPid);
        if (connector == null) {
            throw new IllegalArgumentException("connector not found: " + connectorPid);
        }

        try {
            JdbcConnector decryptedConnector = decrypted(connector);
            // isValid(timeout) sends a lightweight ping; false means DB is unreachable
            return pool.acquire(decryptedConnector).getConnection().isValid(5);
        } catch (IllegalArgumentException e) {
            // Caller contract: IAE for missing connector must propagate
            throw e;
        } catch (Exception e) {
            // Transport-level failures (TCP refused, auth error, timeout) are surfaced as false
            log.warn("JDBC connection test failed for connector {}: {}", connectorPid, e.getMessage());
            return false;
        }
    }

    /**
     * Returns a transient clone of the connector with the password decrypted.
     * This clone is NEVER persisted — it is only used to initialise the HikariCP pool.
     */
    private JdbcConnector decrypted(JdbcConnector c) {
        JdbcConnector clone = new JdbcConnector();
        clone.setId(c.getId());
        clone.setTenantId(c.getTenantId());
        clone.setPid(c.getPid());
        clone.setName(c.getName());
        clone.setJdbcUrl(c.getJdbcUrl());
        clone.setUsername(c.getUsername());
        clone.setPassword(fieldEncryptionService.decrypt(c.getPassword()));
        clone.setMaxPoolSize(c.getMaxPoolSize());
        clone.setConnectionTimeoutMs(c.getConnectionTimeoutMs());
        clone.setEnabled(c.getEnabled());
        clone.setCreatedAt(c.getCreatedAt());
        clone.setUpdatedAt(c.getUpdatedAt());
        clone.setCreatedBy(c.getCreatedBy());
        clone.setUpdatedBy(c.getUpdatedBy());
        return clone;
    }
}
