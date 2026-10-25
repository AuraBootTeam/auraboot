package com.auraboot.framework.connector.jdbc.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.connector.jdbc.dto.JdbcConnectorCreateRequest;
import com.auraboot.framework.connector.jdbc.dto.JdbcEndpointCreateRequest;
import com.auraboot.framework.connector.jdbc.entity.JdbcConnector;
import com.auraboot.framework.connector.jdbc.entity.JdbcConnectorEndpoint;
import com.auraboot.framework.connector.jdbc.service.JdbcConnectorService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-stack coverage IT for {@link JdbcConnectorServiceImpl} — JDBC connector CRUD + endpoint
 * registration. Dedicated synthetic tenant; raw-SQL teardown by tenant. ({@code invoke}, which
 * opens a real pooled connection, is out of scope here.)
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("JdbcConnectorServiceImpl Coverage IT — connector + endpoint CRUD")
class JdbcConnectorServiceImplCoverageIT {

    private static final long TENANT_ID = 990_500_001L;
    private final AtomicLong seq = new AtomicLong();

    @Autowired
    private JdbcConnectorService jdbcConnectorService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(TENANT_ID, 990_000_004L, "jdbccon-test-pid", "jdbccon-test-user");
    }

    @AfterAll
    void cleanup() {
        try {
            // endpoints are parented by connector_pid (no tenant_id column of their own)
            jdbcTemplate.update(
                    "DELETE FROM ab_jdbc_connector_endpoint WHERE connector_pid IN "
                            + "(SELECT pid FROM ab_jdbc_connector WHERE tenant_id = ?)", TENANT_ID);
            jdbcTemplate.update("DELETE FROM ab_jdbc_connector WHERE tenant_id = ?", TENANT_ID);
        } finally {
            MetaContext.clear();
        }
    }

    private JdbcConnectorCreateRequest request(String name) {
        JdbcConnectorCreateRequest r = new JdbcConnectorCreateRequest();
        r.setName(name);
        r.setJdbcUrl("jdbc:postgresql://localhost:5432/aura_boot_clean");
        r.setUsername("ghj");
        r.setPassword("");
        r.setMaxPoolSize(3);
        r.setConnectionTimeoutMs(5000);
        r.setEnabled(true);
        return r;
    }

    @Test
    @DisplayName("create -> getByPid -> listAll -> update -> delete")
    void connectorCrud() {
        JdbcConnector created = jdbcConnectorService.create(request("jc_" + seq.incrementAndGet()));
        assertNotNull(created.getPid());
        assertEquals(TENANT_ID, created.getTenantId());

        assertEquals(created.getPid(), jdbcConnectorService.getByPid(created.getPid()).getPid());
        assertTrue(jdbcConnectorService.listAll().stream().anyMatch(c -> c.getPid().equals(created.getPid())));

        JdbcConnectorCreateRequest upd = request("jc_renamed");
        upd.setMaxPoolSize(8);
        JdbcConnector updated = jdbcConnectorService.update(created.getPid(), upd);
        assertEquals("jc_renamed", updated.getName());
        assertEquals(8, updated.getMaxPoolSize());

        jdbcConnectorService.delete(created.getPid());
        assertTrue(jdbcConnectorService.listAll().stream().noneMatch(c -> c.getPid().equals(created.getPid())));
    }

    @Test
    @DisplayName("addEndpoint + listEndpoints under a connector")
    void endpointCrud() {
        JdbcConnector connector = jdbcConnectorService.create(request("jce_" + seq.incrementAndGet()));

        JdbcEndpointCreateRequest ep = new JdbcEndpointCreateRequest();
        ep.setCode("count_users");
        ep.setName("Count users");
        ep.setOperation("query");
        ep.setSqlTemplate("SELECT count(*) AS cnt FROM ab_user WHERE tenant_id = #{tenantId}");

        JdbcConnectorEndpoint saved = jdbcConnectorService.addEndpoint(connector.getPid(), ep);
        assertNotNull(saved.getId());

        assertTrue(jdbcConnectorService.listEndpoints(connector.getPid()).stream()
                .anyMatch(e -> "count_users".equals(e.getCode())));
    }
}
