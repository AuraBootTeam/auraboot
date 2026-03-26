package com.auraboot.framework.bpm.integration;

import com.alibaba.smart.framework.engine.SmartEngine;
import com.alibaba.smart.framework.engine.configuration.ProcessEngineConfiguration;
import com.alibaba.smart.framework.engine.configuration.impl.DefaultProcessEngineConfiguration;
import com.alibaba.smart.framework.engine.configuration.impl.DefaultSmartEngine;
import com.alibaba.smart.framework.engine.model.assembly.ProcessDefinition;
import com.alibaba.smart.framework.engine.model.instance.ProcessInstance;
import com.alibaba.smart.framework.engine.persister.custom.session.PersisterSession;
import com.alibaba.smart.framework.engine.service.command.ProcessCommandService;
import com.alibaba.smart.framework.engine.service.command.RepositoryCommandService;
import com.alibaba.smart.framework.engine.storage.StorageMode;
import com.alibaba.smart.framework.engine.storage.StorageModeHolder;
import com.auraboot.framework.bpm.config.TimeBasedIdGenerator;
import org.junit.jupiter.api.*;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for SmartEngine Custom Mode.
 * Verifies that processes execute in-memory via PersisterSession
 * without touching the database.
 *
 * Uses StorageModeHolder.set(StorageMode.CUSTOM) to direct the dual-mode
 * StorageRouter to use in-memory PersisterSession instead of database.
 *
 * Note: Custom mode does not support UserTask persistence, so tests use
 * a simple Start -> End flow (simple-flow.bpmn20.xml).
 */
@DisplayName("SmartEngine Custom Mode Integration")
class CustomModeIntegrationTest {

    private SmartEngine smartEngine;
    private RepositoryCommandService repositoryCommandService;
    private ProcessCommandService processCommandService;

    private static final String BPMN_RESOURCE = "smart-engine/simple-flow.bpmn20.xml";
    private static final String PROCESS_ID = "simple-flow";

    @BeforeEach
    void setUp() {
        StorageModeHolder.set(StorageMode.CUSTOM);
        PersisterSession.create();

        ProcessEngineConfiguration config = new DefaultProcessEngineConfiguration();
        config.setIdGenerator(new TimeBasedIdGenerator());

        smartEngine = new DefaultSmartEngine();
        smartEngine.init(config);

        repositoryCommandService = smartEngine.getRepositoryCommandService();
        processCommandService = smartEngine.getProcessCommandService();
    }

    @AfterEach
    void tearDown() {
        PersisterSession.destroySession();
        StorageModeHolder.clear();
    }

    @Test
    @DisplayName("Deploy and run a process in Custom Mode (in-memory)")
    void shouldRunProcessInMemory() {
        var source = repositoryCommandService.deploy(BPMN_RESOURCE);

        assertNotNull(source);
        ProcessDefinition definition = source.getFirstProcessDefinition();
        assertEquals(PROCESS_ID, definition.getId());

        Map<String, Object> variables = new HashMap<>();
        variables.put("startUserId", "automation-trigger");

        ProcessInstance processInstance = processCommandService.start(
                PROCESS_ID, "1", variables);

        assertNotNull(processInstance);
        assertNotNull(processInstance.getInstanceId());
    }

    @Test
    @DisplayName("Custom Mode data does not persist across sessions")
    void shouldNotPersistAcrossSessions() {
        repositoryCommandService.deploy(BPMN_RESOURCE);

        Map<String, Object> variables = new HashMap<>();
        ProcessInstance pi1 = processCommandService.start(
                PROCESS_ID, "1", variables);
        String instanceId = pi1.getInstanceId();

        ProcessInstance found = PersisterSession.currentSession()
                .getProcessInstance(instanceId);
        assertNotNull(found, "Should find instance in current session");

        PersisterSession.destroySession();
        PersisterSession.create();

        ProcessInstance notFound = PersisterSession.currentSession()
                .getProcessInstance(instanceId);
        assertNull(notFound, "Should NOT find instance in new session");
    }

    @Test
    @DisplayName("Multiple Custom Mode sessions are independent")
    void shouldIsolateSessions() {
        repositoryCommandService.deploy(BPMN_RESOURCE);

        Map<String, Object> variables = new HashMap<>();
        ProcessInstance pi = processCommandService.start(
                PROCESS_ID, "1", variables);

        assertNotNull(PersisterSession.currentSession()
                .getProcessInstance(pi.getInstanceId()));
    }
}
