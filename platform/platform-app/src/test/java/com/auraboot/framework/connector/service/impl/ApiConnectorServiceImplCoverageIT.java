package com.auraboot.framework.connector.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.connector.dto.ApiConnectorCreateRequest;
import com.auraboot.framework.connector.entity.ApiConnector;
import com.auraboot.framework.connector.service.ApiConnectorService;
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
 * Real-stack coverage IT for {@link ApiConnectorServiceImpl} — connector CRUD (create with SSRF
 * validation + authConfig encryption, getByPid, listAll, update, delete). Dedicated synthetic
 * tenant on the real DB; raw-SQL teardown by tenant.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("ApiConnectorServiceImpl Coverage IT — connector CRUD")
class ApiConnectorServiceImplCoverageIT {

    private static final long TENANT_ID = 990_400_001L;
    private final AtomicLong seq = new AtomicLong();

    @Autowired
    private ApiConnectorService apiConnectorService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(TENANT_ID, 990_000_003L, "apicon-test-pid", "apicon-test-user");
    }

    @AfterAll
    void cleanup() {
        try {
            jdbcTemplate.update("DELETE FROM ab_api_connector WHERE tenant_id = ?", TENANT_ID);
        } finally {
            MetaContext.clear();
        }
    }

    private ApiConnectorCreateRequest request(String name) {
        ApiConnectorCreateRequest r = new ApiConnectorCreateRequest();
        r.setName(name);
        r.setBaseUrl("https://api.example.com");
        r.setAuthType("bearer");
        r.setAuthConfig("{\"token\":\"secret-123\"}");
        r.setDefaultHeaders("{\"X-App\":\"aura\"}");
        r.setTimeoutMs(5000);
        r.setEnabled(true);
        return r;
    }

    @Test
    @DisplayName("create -> getByPid -> listAll -> update -> delete")
    void crud() {
        ApiConnector created = apiConnectorService.create(request("conn_" + seq.incrementAndGet()));
        assertNotNull(created.getPid());
        assertEquals(TENANT_ID, created.getTenantId());

        ApiConnector fetched = apiConnectorService.getByPid(created.getPid());
        assertEquals(created.getPid(), fetched.getPid());

        assertTrue(apiConnectorService.listAll().stream()
                .anyMatch(c -> c.getPid().equals(created.getPid())));

        ApiConnectorCreateRequest upd = request("conn_renamed");
        upd.setTimeoutMs(9000);
        ApiConnector updated = apiConnectorService.update(created.getPid(), upd);
        assertEquals("conn_renamed", updated.getName());
        assertEquals(9000, updated.getTimeoutMs());

        apiConnectorService.delete(created.getPid());
        assertTrue(apiConnectorService.listAll().stream()
                .noneMatch(c -> c.getPid().equals(created.getPid())));
    }
}
