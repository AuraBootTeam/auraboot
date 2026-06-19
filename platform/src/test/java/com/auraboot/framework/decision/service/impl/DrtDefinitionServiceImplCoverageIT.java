package com.auraboot.framework.decision.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.decision.dto.DrtDefinitionCreateRequest;
import com.auraboot.framework.decision.dto.DrtDefinitionDTO;
import com.auraboot.framework.decision.service.DrtDefinitionService;
import com.auraboot.framework.exception.ValidationException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-stack coverage IT for {@link DrtDefinitionServiceImpl} — decision-table definition CRUD
 * (create with unique-code guard, update, findByPid, findByCode, list) on a dedicated synthetic
 * tenant; raw teardown by tenant.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("DrtDefinitionServiceImpl Coverage IT — decision definition CRUD")
class DrtDefinitionServiceImplCoverageIT {

    private static final long TENANT_ID = 990_700_001L;
    private final AtomicLong seq = new AtomicLong();

    @Autowired
    private DrtDefinitionService drtDefinitionService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(TENANT_ID, 990_000_006L, "drt-test-pid", "drt-test-user");
    }

    @AfterAll
    void cleanup() {
        try {
            jdbcTemplate.update("DELETE FROM ab_drt_definition WHERE tenant_id = ?", TENANT_ID);
        } finally {
            MetaContext.clear();
        }
    }

    private DrtDefinitionCreateRequest request(String code) {
        DrtDefinitionCreateRequest r = new DrtDefinitionCreateRequest();
        r.setDecisionCode(code);
        r.setDecisionName("Decision " + code);
        r.setDescription("desc");
        r.setScopeType("global");
        r.setOwnerModule("test");
        r.setEnabled(true);
        return r;
    }

    @Test
    @DisplayName("create -> findByPid -> findByCode -> list -> update")
    void crud() {
        String code = "drt_" + seq.incrementAndGet();
        DrtDefinitionDTO created = drtDefinitionService.create(request(code));
        assertNotNull(created.getPid());

        assertEquals(created.getPid(), drtDefinitionService.findByPid(created.getPid()).getPid());
        assertEquals(code, drtDefinitionService.findByCode(code).getDecisionCode());

        PageResult<DrtDefinitionDTO> page = drtDefinitionService.list(code, 1, 10);
        assertTrue(page.getRecords().stream().anyMatch(d -> d.getPid().equals(created.getPid())));

        DrtDefinitionCreateRequest upd = request(code);
        upd.setDecisionName("renamed decision");
        DrtDefinitionDTO updated = drtDefinitionService.update(created.getPid(), upd);
        assertEquals("renamed decision", updated.getDecisionName());
    }

    @Test
    @DisplayName("create rejects a duplicate decision code")
    void duplicateCodeRejected() {
        String code = "drt_dup_" + seq.incrementAndGet();
        drtDefinitionService.create(request(code));
        assertThrows(ValidationException.class, () -> drtDefinitionService.create(request(code)));
    }
}
