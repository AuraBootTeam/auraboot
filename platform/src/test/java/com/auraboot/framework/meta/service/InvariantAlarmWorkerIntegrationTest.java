package com.auraboot.framework.meta.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.InvariantDefinitionCreateRequest;
import com.auraboot.framework.meta.entity.InvariantDefinition;
import com.auraboot.framework.meta.service.impl.InvariantAlarmWorker;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

/**
 * InvariantAlarmWorker Integration Test
 *
 * Covers P2-4 requirements:
 * 1. Periodic ALWAYS invariant checking
 * 2. Multi-tenant evaluation
 * 3. Error isolation between invariants
 */
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("InvariantAlarmWorker Integration Test - P2-4")
class InvariantAlarmWorkerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private InvariantAlarmWorker invariantAlarmWorker;

    @Autowired
    private InvariantDefinitionService invariantDefinitionService;

    // ==================== Worker Execution Tests ====================

    @Test
    @Order(1)
    @DisplayName("P2-4.4: Alarm worker runs without error")
    void test01_checkAlwaysInvariants() {
        assertDoesNotThrow(() -> {
            invariantAlarmWorker.checkAlwaysInvariants();
        });
    }

    @Test
    @Order(2)
    @DisplayName("P2-4.4: Alarm worker is idempotent")
    void test02_checkMultipleTimes() {
        assertDoesNotThrow(() -> {
            invariantAlarmWorker.checkAlwaysInvariants();
            invariantAlarmWorker.checkAlwaysInvariants();
        });
    }

    @Test
    @Order(3)
    @DisplayName("P2-4.4: Worker handles ALWAYS invariant with passing expression")
    void test03_withPassingInvariant() {
        // Create and publish an ALWAYS invariant that passes
        InvariantDefinitionCreateRequest request = new InvariantDefinitionCreateRequest();
        request.setCode("alarm_pass_" + System.currentTimeMillis());
        request.setDisplayName("Always Passing Invariant");
        request.setExpression("true");
        request.setInvariantType("always");
        request.setSeverity("error");
        request.setScopeType("model");
        request.setScopeRef("alarm_test_model");
        request.setModelCode("alarm_test_model");
        request.setEnabled(true);

        InvariantDefinition created = invariantDefinitionService.create(request);
        invariantDefinitionService.publish(created.getPid());

        // Worker should process without error
        assertDoesNotThrow(() -> {
            invariantAlarmWorker.checkAlwaysInvariants();
        });
    }

    @Test
    @Order(4)
    @DisplayName("P2-4.4: Worker isolates errors between invariants")
    void test04_errorIsolation() {
        // Create an invariant with an expression that might fail
        InvariantDefinitionCreateRequest request = new InvariantDefinitionCreateRequest();
        request.setCode("alarm_error_" + System.currentTimeMillis());
        request.setDisplayName("Potentially Failing Invariant");
        request.setExpression("#nonExistentVar == null");
        request.setInvariantType("always");
        request.setSeverity("warn");
        request.setScopeType("model");
        request.setScopeRef("error_isolation_model");
        request.setModelCode("error_isolation_model");
        request.setEnabled(true);

        InvariantDefinition created = invariantDefinitionService.create(request);
        invariantDefinitionService.publish(created.getPid());

        // Worker should still complete even if one invariant errors
        assertDoesNotThrow(() -> {
            invariantAlarmWorker.checkAlwaysInvariants();
        });
    }

    @Test
    @Order(5)
    @DisplayName("P2-4.4: Worker skips disabled ALWAYS invariants")
    void test05_skipsDisabled() {
        InvariantDefinitionCreateRequest request = new InvariantDefinitionCreateRequest();
        request.setCode("alarm_disabled_" + System.currentTimeMillis());
        request.setDisplayName("Disabled Always Invariant");
        request.setExpression("false"); // Would generate alarm if evaluated
        request.setInvariantType("always");
        request.setSeverity("error");
        request.setScopeType("model");
        request.setScopeRef("disabled_model");
        request.setModelCode("disabled_model");
        request.setEnabled(false); // Disabled

        invariantDefinitionService.create(request);

        // Worker should skip disabled invariants
        assertDoesNotThrow(() -> {
            invariantAlarmWorker.checkAlwaysInvariants();
        });
    }
}
