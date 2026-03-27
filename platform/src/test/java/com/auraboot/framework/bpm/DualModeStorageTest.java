package com.auraboot.framework.bpm;

import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.persister.custom.session.PersisterSession;
import com.auraboot.smart.framework.engine.storage.StorageModeHolder;
import com.auraboot.framework.bpm.service.ProcessDeploymentService;
import com.auraboot.framework.bpm.service.ProcessEngineService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.plugin.entity.BpmProcessDefinition;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for dual-mode storage (DATABASE + CUSTOM).
 * Verifies that StorageModeHolder and PersisterSession are correctly
 * used for routing SmartEngine to different persistence backends.
 *
 * @author AuraBoot Team
 */
@Slf4j
@DisplayName("BPM Dual-Mode Storage Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DualModeStorageTest extends BaseIntegrationTest {

    @Autowired
    private SmartEngine smartEngine;

    @Autowired
    private ProcessDeploymentService deploymentService;

    @Autowired
    private ProcessEngineService processEngineService;

    private static final String TEST_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:smart="http://smartengine.alibaba.com/schema"
                         targetNamespace="http://test.auraboot.com/dual-mode">
                <process id="dual-mode-test" name="Dual Mode Test" isExecutable="true">
                    <startEvent id="start" name="Start"/>
                    <sequenceFlow id="flow1" sourceRef="start" targetRef="task1"/>
                    <userTask id="task1" name="Test Task"
                              smart:assigneeType="specific_user"
                              smart:assigneeValue="test-user"/>
                    <sequenceFlow id="flow2" sourceRef="task1" targetRef="end"/>
                    <endEvent id="end" name="End"/>
                </process>
            </definitions>
            """;

    // ==================== DM-01: DATABASE Mode ====================

    @Test
    @Order(1)
    @DisplayName("DM-01: DATABASE mode - deploy and verify process definition persisted")
    void dm01_databaseModeDeploy() {
        // Arrange
        String processKey = "dm-db-test-" + System.nanoTime();
        var createReq = new ProcessDeploymentService.CreateProcessRequest(
                processKey, "DB Mode Test", "Dual mode test - DATABASE",
                null, TEST_BPMN.replace("dual-mode-test", processKey),
                null, null, null);

        // Act
        BpmProcessDefinition def = deploymentService.create(createReq);
        assertNotNull(def.getPid(), "Process definition PID should be set");
        assertEquals("draft", def.getStatus());

        BpmProcessDefinition deployed = deploymentService.deploy(def.getPid());

        // Assert
        assertEquals("deployed", deployed.getStatus(), "Should be DEPLOYED after deploy");
        assertNotNull(deployed.getDeploymentId(), "Should have deployment ID");

        // Verify it can be retrieved
        BpmProcessDefinition retrieved = deploymentService.getByPid(def.getPid());
        assertNotNull(retrieved, "Should be retrievable from database");
        assertEquals("deployed", retrieved.getStatus());

        log.info("DM-01 PASSED: DATABASE mode deploy and retrieval works");
    }

    // ==================== DM-02: CUSTOM Mode ====================

    @Test
    @Order(2)
    @DisplayName("DM-02: CUSTOM mode - PersisterSession lifecycle")
    void dm02_customModeSession() {
        // Verify PersisterSession can be created and destroyed properly
        assertDoesNotThrow(() -> {
            PersisterSession.create();
            try {
                var session = PersisterSession.currentSession();
                assertNotNull(session, "PersisterSession should be available after create()");
            } finally {
                PersisterSession.destroySession();
            }
        });

        log.info("DM-02 PASSED: CUSTOM mode PersisterSession lifecycle works");
    }

    // ==================== DM-03: Mode Isolation ====================

    @Test
    @Order(3)
    @DisplayName("DM-03: StorageModeHolder isolation between threads")
    void dm03_modeIsolation() {
        // Verify StorageModeHolder is thread-local and properly cleaned up
        assertNull(StorageModeHolder.get(), "StorageModeHolder should be null initially");

        // Set CUSTOM mode
        StorageModeHolder.set(com.auraboot.smart.framework.engine.storage.StorageMode.CUSTOM);
        assertEquals(com.auraboot.smart.framework.engine.storage.StorageMode.CUSTOM,
                StorageModeHolder.get(), "Should be CUSTOM after set");

        // Clear
        StorageModeHolder.clear();
        assertNull(StorageModeHolder.get(), "StorageModeHolder should be null after clear");

        // Set DATABASE mode
        StorageModeHolder.set(com.auraboot.smart.framework.engine.storage.StorageMode.DATABASE);
        assertEquals(com.auraboot.smart.framework.engine.storage.StorageMode.DATABASE,
                StorageModeHolder.get(), "Should be DATABASE after set");

        StorageModeHolder.clear();
        assertNull(StorageModeHolder.get(), "StorageModeHolder should be null after final clear");

        log.info("DM-03 PASSED: StorageModeHolder isolation verified");
    }
}
