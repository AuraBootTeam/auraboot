package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.SodCheckResult;
import com.auraboot.framework.meta.dto.SodRuleCreateRequest;
import com.auraboot.framework.meta.dto.SodRuleUpdateRequest;
import com.auraboot.framework.meta.entity.SodRule;
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

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-stack coverage IT for {@link SodService} — segregation-of-duties rule CRUD + validation
 * branches + a passing checkSod evaluation + violation queries. Dedicated synthetic tenant; raw
 * teardown by tenant. (The violation/blocked checkSod path needs prior command history and is
 * out of scope here.)
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("SodService Coverage IT — rule CRUD + validation + checkSod(pass) + violations")
class SodServiceCoverageIT {

    private static final long TENANT_ID = 990_600_001L;
    private final AtomicLong seq = new AtomicLong();

    @Autowired
    private SodService sodService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(TENANT_ID, 990_000_005L, "sod-test-pid", "sod-test-user");
    }

    @AfterAll
    void cleanup() {
        try {
            jdbcTemplate.update("DELETE FROM ab_sod_rule WHERE tenant_id = ?", TENANT_ID);
        } finally {
            MetaContext.clear();
        }
    }

    private SodRuleCreateRequest request(String code) {
        SodRuleCreateRequest r = new SodRuleCreateRequest();
        r.setRuleCode(code);
        r.setRuleName("Rule " + code);
        r.setDescription("desc");
        r.setCommandA("sl:create_order");
        r.setCommandB("sl:approve_order");
        r.setEntityScope("same_record");
        r.setEnforcement("hard");
        r.setEnabled(true);
        return r;
    }

    @Test
    @DisplayName("createRule -> getRule -> listRules -> updateRule -> deleteRule")
    void crud() {
        SodRule created = sodService.createRule(request("sod_" + seq.incrementAndGet()));
        assertNotNull(created.getId());
        assertEquals(TENANT_ID, created.getTenantId());

        assertEquals(created.getId(), sodService.getRule(created.getId()).getId());
        assertTrue(sodService.listRules().stream().anyMatch(r -> r.getId().equals(created.getId())));

        SodRuleUpdateRequest upd = new SodRuleUpdateRequest();
        upd.setRuleName("renamed");
        upd.setEnforcement("soft");
        upd.setEnabled(false);
        SodRule updated = sodService.updateRule(created.getId(), upd);
        assertEquals("renamed", updated.getRuleName());

        sodService.deleteRule(created.getId());
        assertTrue(sodService.listRules().stream().noneMatch(r -> r.getId().equals(created.getId())));
    }

    @Test
    @DisplayName("createRule rejects invalid input (validation branches)")
    void validation() {
        SodRuleCreateRequest noCode = request("x");
        noCode.setRuleCode(null);
        assertThrows(BusinessException.class, () -> sodService.createRule(noCode));

        SodRuleCreateRequest sameCmd = request("sod_same_" + seq.incrementAndGet());
        sameCmd.setCommandB(sameCmd.getCommandA());
        assertThrows(BusinessException.class, () -> sodService.createRule(sameCmd));

        SodRuleCreateRequest badScope = request("sod_scope_" + seq.incrementAndGet());
        badScope.setEntityScope("not_a_scope");
        assertThrows(BusinessException.class, () -> sodService.createRule(badScope));
    }

    @Test
    @DisplayName("checkSod passes when no rule matches the command")
    void checkSodPasses() {
        SodCheckResult result = sodService.checkSod(
                "no_rule_command_xyz", 990_000_005L, "actor", "sl_order", 12345L);
        assertTrue(result.isPassed());
    }

    @Test
    @DisplayName("violation queries return empty when there are none")
    void violationQueries() {
        assertTrue(sodService.getViolations(Instant.now().minusSeconds(3600), Instant.now()).isEmpty());
        assertTrue(sodService.getViolationsByActor(990_999_999L).isEmpty());
    }
}
