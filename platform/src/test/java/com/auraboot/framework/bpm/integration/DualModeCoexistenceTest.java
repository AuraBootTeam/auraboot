package com.auraboot.framework.bpm.integration;

import com.alibaba.smart.framework.engine.SmartEngine;
import com.alibaba.smart.framework.engine.configuration.ProcessEngineConfiguration;
import com.alibaba.smart.framework.engine.configuration.impl.DefaultProcessEngineConfiguration;
import com.alibaba.smart.framework.engine.configuration.impl.DefaultSmartEngine;
import com.alibaba.smart.framework.engine.model.instance.ProcessInstance;
import com.alibaba.smart.framework.engine.persister.custom.session.PersisterSession;
import com.alibaba.smart.framework.engine.service.command.ProcessCommandService;
import com.alibaba.smart.framework.engine.service.command.RepositoryCommandService;
import com.alibaba.smart.framework.engine.service.query.ProcessQueryService;
import com.alibaba.smart.framework.engine.storage.StorageMode;
import com.alibaba.smart.framework.engine.storage.StorageModeHolder;
import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.bpm.config.TimeBasedIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test verifying Database Mode and Custom Mode
 * can coexist in the same JVM process.
 *
 * Uses StorageModeHolder to switch between DATABASE and CUSTOM modes
 * per-thread via ThreadLocal.
 *
 * Database Mode uses simple-approval.bpmn20.xml (with userTask).
 * Custom Mode uses simple-flow.bpmn20.xml (start → end, no userTask).
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
@DisplayName("SmartEngine Dual-Mode Coexistence")
class DualModeCoexistenceTest {

    @Autowired
    private SmartEngine databaseEngine;

    private SmartEngine customEngine;

    @BeforeEach
    void setUp() {
        StorageModeHolder.set(StorageMode.CUSTOM);
        ProcessEngineConfiguration customConfig = new DefaultProcessEngineConfiguration();
        customConfig.setIdGenerator(new TimeBasedIdGenerator());
        customEngine = new DefaultSmartEngine();
        customEngine.init(customConfig);
        StorageModeHolder.clear();
    }

    @Test
    @DisplayName("Database Mode and Custom Mode engines coexist independently")
    void shouldCoexistIndependently() {
        // === Database Mode: Deploy and start ===
        StorageModeHolder.set(StorageMode.DATABASE);
        RepositoryCommandService dbRepo = databaseEngine.getRepositoryCommandService();
        ProcessCommandService dbProcess = databaseEngine.getProcessCommandService();

        dbRepo.deploy("smart-engine/simple-approval.bpmn20.xml");

        Map<String, Object> dbVars = new HashMap<>();
        dbVars.put("startUserId", "db-user");
        ProcessInstance dbInstance = dbProcess.start("simple-approval", "1", dbVars);
        assertNotNull(dbInstance, "Database mode should create instance");
        String dbInstanceId = dbInstance.getInstanceId();
        StorageModeHolder.clear();

        // === Custom Mode: Deploy and start (in separate PersisterSession) ===
        StorageModeHolder.set(StorageMode.CUSTOM);
        PersisterSession.create();
        try {
            RepositoryCommandService customRepo = customEngine.getRepositoryCommandService();
            ProcessCommandService customProcess = customEngine.getProcessCommandService();

            customRepo.deploy("smart-engine/simple-flow.bpmn20.xml");

            Map<String, Object> customVars = new HashMap<>();
            customVars.put("startUserId", "custom-user");
            ProcessInstance customInstance = customProcess.start("simple-flow", "1", customVars);
            assertNotNull(customInstance, "Custom mode should create instance");
            String customInstanceId = customInstance.getInstanceId();

            // === Verify isolation ===
            ProcessInstance inSession = PersisterSession.currentSession()
                    .getProcessInstance(customInstanceId);
            assertNotNull(inSession, "Custom instance should be in PersisterSession");

            StorageModeHolder.set(StorageMode.DATABASE);
            ProcessQueryService dbQuery = databaseEngine.getProcessQueryService();
            ProcessInstance dbFound = dbQuery.findById(dbInstanceId, null);
            assertNotNull(dbFound, "Database instance should be queryable");

            assertNotEquals(dbInstanceId, customInstanceId,
                    "Database and Custom instances should have different IDs");

        } finally {
            StorageModeHolder.clear();
            PersisterSession.destroySession();
        }
    }

    @Test
    @DisplayName("Database Mode persists data after Custom Mode session ends")
    void databaseModeSurvivesCustomSessionLifecycle() {
        StorageModeHolder.set(StorageMode.DATABASE);
        RepositoryCommandService dbRepo = databaseEngine.getRepositoryCommandService();
        ProcessCommandService dbProcess = databaseEngine.getProcessCommandService();
        ProcessQueryService dbQuery = databaseEngine.getProcessQueryService();

        dbRepo.deploy("smart-engine/simple-approval.bpmn20.xml");

        Map<String, Object> vars = new HashMap<>();
        ProcessInstance dbInstance = dbProcess.start("simple-approval", "1", vars);
        String dbInstanceId = dbInstance.getInstanceId();
        StorageModeHolder.clear();

        // Start and destroy a custom session
        StorageModeHolder.set(StorageMode.CUSTOM);
        PersisterSession.create();
        RepositoryCommandService customRepo = customEngine.getRepositoryCommandService();
        customRepo.deploy("smart-engine/simple-flow.bpmn20.xml");
        customEngine.getProcessCommandService()
                .start("simple-flow", "1", vars);
        PersisterSession.destroySession();
        StorageModeHolder.clear();

        // Database mode instance should still be available
        StorageModeHolder.set(StorageMode.DATABASE);
        ProcessInstance stillThere = dbQuery.findById(dbInstanceId, null);
        assertNotNull(stillThere,
                "Database instance should survive Custom Mode session lifecycle");
        StorageModeHolder.clear();
    }
}
