package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.InvariantDefinitionCreateRequest;
import com.auraboot.framework.meta.entity.InvariantDefinition;
import com.auraboot.framework.meta.service.InvariantDefinitionService;
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
 * Real-stack coverage IT for {@link InvariantDefinitionServiceImpl} — invariant-definition CRUD
 * (create, getByPid, getCurrentByCode, listByModelCode, update, publish, delete) on a dedicated
 * synthetic tenant; raw teardown.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("InvariantDefinitionServiceImpl Coverage IT — invariant CRUD")
class InvariantDefinitionServiceImplCoverageIT {

    private static final long TENANT_ID = 991_600_001L;
    private final AtomicLong seq = new AtomicLong();

    @Autowired
    private InvariantDefinitionService invariantService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(TENANT_ID, 991_600_002L, "inv-test-pid", "inv-test-user");
    }

    @AfterAll
    void cleanup() {
        try {
            jdbcTemplate.update("DELETE FROM ab_invariant_definition WHERE tenant_id = ?", TENANT_ID);
        } finally {
            MetaContext.clear();
        }
    }

    private InvariantDefinitionCreateRequest request(String code) {
        InvariantDefinitionCreateRequest r = new InvariantDefinitionCreateRequest();
        r.setCode(code);
        r.setDisplayName("Invariant " + code);
        r.setDescription("desc");
        r.setExpression("amount >= 0");
        r.setInvariantType("always");
        r.setSeverity("error");
        r.setScopeType("model");
        r.setModelCode("inv_cov_model");
        r.setEnabled(true);
        return r;
    }

    @Test
    @DisplayName("create -> getByPid -> getCurrentByCode -> listByModelCode -> update -> publish -> delete")
    void crud() {
        String code = "inv_" + seq.incrementAndGet();
        InvariantDefinition created = invariantService.create(request(code));
        assertNotNull(created.getPid());

        assertEquals(created.getPid(), invariantService.getByPid(created.getPid()).getPid());
        assertEquals(code, invariantService.getCurrentByCode(code).getCode());
        assertTrue(invariantService.listByModelCode("inv_cov_model").stream()
                .anyMatch(i -> i.getPid().equals(created.getPid())));

        InvariantDefinitionCreateRequest upd = request(code);
        upd.setDisplayName("renamed invariant");
        InvariantDefinition updated = invariantService.update(created.getPid(), upd);
        assertEquals("renamed invariant", updated.getDisplayName());

        invariantService.publish(updated.getPid());
        invariantService.delete(updated.getPid());
    }
}
