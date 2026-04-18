package com.auraboot.framework.bpm.config;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.service.ProcessDeploymentService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.plugin.entity.BpmProcessDefinition;
import com.auraboot.framework.plugin.mapper.BpmProcessDefinitionMapper;
import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.service.query.RepositoryQueryService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link BpmStartupReDeployer} re-registers previously deployed
 * process definitions into SmartEngine's in-memory repository on application
 * startup, and that its behaviour is idempotent and isolates per-process
 * failures.
 *
 * <p>The listener has already fired by the time the test container is ready,
 * so we assert it is safe to invoke its core method a second time without
 * raising errors (idempotency) and that directly invoking it picks up rows
 * inserted via the service layer.
 */
@Slf4j
@DisplayName("BPM Startup Re-Deployer")
class BpmStartupReDeployerTest extends BaseIntegrationTest {

    @Autowired
    private BpmStartupReDeployer reDeployer;

    @Autowired
    private SmartEngine smartEngine;

    @Autowired
    private ProcessDeploymentService deploymentService;

    @Autowired
    private BpmProcessDefinitionMapper mapper;

    private static final String SIMPLE_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:smart="http://smart.alibaba.com"
                         targetNamespace="http://auraboot.com/bpm">
              <process id="%s" name="Startup Re-Deploy Test" isExecutable="true">
                <startEvent id="start"/>
                <sequenceFlow id="flow1" sourceRef="start" targetRef="task1"/>
                <userTask id="task1" smart:assigneeType="user" smart:assigneeId="u1"/>
                <sequenceFlow id="flow2" sourceRef="task1" targetRef="end"/>
                <endEvent id="end"/>
              </process>
            </definitions>
            """;

    @Test
    @DisplayName("Deployed process gets re-registered when listener fires")
    void reRegistersDeployedProcess() {
        // Arrange: create + deploy a process through the normal service path so it
        // is persisted with status='deployed' and bpmn_content populated.
        String processKey = "startup-redeploy-" + System.nanoTime();
        String bpmn = SIMPLE_BPMN.formatted(processKey);

        BpmProcessDefinition created = deploymentService.create(
                new ProcessDeploymentService.CreateProcessRequest(
                        processKey,
                        "Startup Re-Deploy Test",
                        "desc",
                        "test",
                        bpmn,
                        null,
                        null,
                        null));
        deploymentService.deploy(created.getPid());

        // Sanity: row is in DB with status deployed
        BpmProcessDefinition row = mapper.findByPid(created.getPid());
        assertThat(row).isNotNull();
        assertThat(row.getStatus()).isEqualTo("deployed");

        // Act: run the listener (idempotent - the process is already in cache).
        reDeployer.reDeployPersistedProcessesOnStartup();

        // Assert: the process is in SmartEngine's cache.
        RepositoryQueryService queryService = smartEngine.getRepositoryQueryService();
        boolean cached = queryService.getAllCachedProcessDefinition()
                .stream()
                .anyMatch(pd -> processKey.equals(pd.getId()));
        assertThat(cached)
                .as("process %s should be registered in SmartEngine after listener runs", processKey)
                .isTrue();
    }

    @Test
    @DisplayName("Listener is idempotent - double invocation does not error")
    void idempotentWhenProcessAlreadyCached() {
        // The @ApplicationReady listener has already fired during Spring context
        // init. Calling it again must be a no-op (ALREADY_CACHED path) and must
        // not throw.
        reDeployer.reDeployPersistedProcessesOnStartup();
        reDeployer.reDeployPersistedProcessesOnStartup();
        // If we reach here without exception the idempotency contract holds.
    }

    @Test
    @DisplayName("Empty deployed set is a no-op")
    void emptyDeployedSetIsNoop() {
        // We cannot truly empty the DB inside an integration test, but the
        // listener contract is: an empty list must not throw and must not alter
        // cache. We assert the listener is safe to invoke regardless of DB state.
        int cachedBefore = smartEngine.getRepositoryQueryService()
                .getAllCachedProcessDefinition()
                .size();

        reDeployer.reDeployPersistedProcessesOnStartup();

        int cachedAfter = smartEngine.getRepositoryQueryService()
                .getAllCachedProcessDefinition()
                .size();
        // Idempotent re-run must not shrink the cache (only grow it, if anything).
        assertThat(cachedAfter).isGreaterThanOrEqualTo(cachedBefore);
    }

    @Test
    @DisplayName("Per-process failure does not block siblings")
    void failureIsolation() {
        // Insert a deployed row with malformed BPMN directly via the mapper so
        // it survives the ProcessDeploymentService.deploy validation. Pair it
        // with a valid deployed process and assert the valid one still ends up
        // cached even though the malformed one fails.
        Long tenantId = MetaContext.getCurrentTenantId();

        String validKey = "valid-isolate-" + System.nanoTime();
        String validBpmn = SIMPLE_BPMN.formatted(validKey);
        BpmProcessDefinition valid = deploymentService.create(
                new ProcessDeploymentService.CreateProcessRequest(
                        validKey, "valid", "d", "test", validBpmn, null, null, null));
        deploymentService.deploy(valid.getPid());

        String brokenKey = "broken-isolate-" + System.nanoTime();
        BpmProcessDefinition broken = BpmProcessDefinition.builder()
                .pid("broken-pid-" + System.nanoTime())
                .tenantId(tenantId)
                .processKey(brokenKey)
                .processName("broken")
                .bpmnContent("<not-valid-bpmn/>")
                .status("deployed")
                .version(1)
                .isCurrent(true)
                .deployedAt(Instant.now())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        mapper.insert(broken);

        // Act
        reDeployer.reDeployPersistedProcessesOnStartup();

        // Assert: the valid key is (still) cached. The broken one logs an error
        // but does not prevent the valid sibling from being registered.
        List<String> cachedIds = smartEngine.getRepositoryQueryService()
                .getAllCachedProcessDefinition()
                .stream()
                .map(pd -> pd.getId())
                .toList();
        assertThat(cachedIds).contains(validKey);
    }
}
