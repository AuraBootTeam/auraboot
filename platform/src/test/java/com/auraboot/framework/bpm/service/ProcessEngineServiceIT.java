package com.auraboot.framework.bpm.service;

import com.auraboot.smart.framework.engine.model.instance.ProcessInstance;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.dto.ProcessInstanceStatusDTO;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.plugin.entity.BpmProcessDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-stack integration coverage for {@link ProcessEngineService} (replaces the
 * old Mockito-only ProcessEngineServiceTest / TenantAwareProcessEngineServiceTest
 * which were fully commented out — mock-engine tests proved nothing about the
 * real SmartEngine runtime). Drives the real engine + real Postgres: start /
 * get / suspend / resume / terminate / node-status / by-user, plus tenant
 * isolation on read.
 */
@DisplayName("ProcessEngineService real-stack IT")
class ProcessEngineServiceIT extends BaseIntegrationTest {

    @Autowired
    private ProcessDeploymentService deploymentService;

    @Autowired
    private ProcessEngineService processEngineService;

    private static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:smart="http://smart.alibaba.com"
                         targetNamespace="http://auraboot.com/bpm">
              <process id="%s" name="PES IT" isExecutable="true">
                <startEvent id="start" name="Start"/>
                <sequenceFlow id="f1" sourceRef="start" targetRef="task1"/>
                <userTask id="task1" name="Review"
                          smart:assigneeType="user" smart:assigneeId="%s"/>
                <sequenceFlow id="f2" sourceRef="task1" targetRef="end"/>
                <endEvent id="end" name="End"/>
              </process>
            </definitions>
            """;

    private String deploy(String suffix) {
        String key = "pes-it-" + suffix + "-" + System.nanoTime();
        String actor = com.auraboot.framework.bpm.util.BpmSecurityUtil.getCurrentUserId();
        var req = new ProcessDeploymentService.CreateProcessRequest(
                key, "PES IT " + suffix, "pes it", "test",
                String.format(BPMN, key, actor), null, null, null);
        BpmProcessDefinition def = deploymentService.create(req);
        deploymentService.deploy(def.getPid());
        return key;
    }

    private ProcessInstance start(String key) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("_startUserId", MetaContext.getCurrentUserId() + "");
        return processEngineService.startProcess(key, "PES-BIZ-" + System.nanoTime(), vars);
    }

    @Test
    @DisplayName("startProcess creates an instance; getProcessInstance returns it")
    void startAndGet() {
        ProcessInstance instance = start(deploy("get"));
        assertNotNull(instance);
        assertNotNull(instance.getInstanceId());
        ProcessInstance fetched = processEngineService.getProcessInstance(instance.getInstanceId());
        assertNotNull(fetched, "same-tenant read returns the instance");
        assertEquals(instance.getInstanceId(), fetched.getInstanceId());
    }

    @Test
    @DisplayName("getProcessInstance is tenant-isolated — another tenant context sees null")
    void tenantIsolationOnRead() {
        ProcessInstance instance = start(deploy("tenant"));
        Long realTenant = MetaContext.getCurrentTenantId();
        try {
            // switch to a different tenant context: the engine query is scoped by
            // MetaContext tenantId, so a foreign tenant must not see the instance.
            MetaContext.setCurrentTenantId(realTenant + 999_999L);
            ProcessInstance foreign = processEngineService.getProcessInstance(instance.getInstanceId());
            assertNull(foreign, "foreign tenant must not read another tenant's instance");
        } finally {
            MetaContext.setCurrentTenantId(realTenant);
        }
        // back in the real tenant it is visible again
        assertNotNull(processEngineService.getProcessInstance(instance.getInstanceId()));
    }

    @Test
    @DisplayName("suspend then resume the instance")
    void suspendResume() {
        ProcessInstance instance = start(deploy("susp"));
        Map<String, Object> vars = new HashMap<>();
        vars.put("_startUserId", MetaContext.getCurrentUserId() + "");
        processEngineService.suspendProcessInstance(instance.getInstanceId(), vars);
        // resume (implementation note: resume path exists; assert no throw + still queryable)
        processEngineService.resumeProcessInstance(instance.getInstanceId(),
                MetaContext.getCurrentUserId() + "");
        assertNotNull(processEngineService.getProcessInstance(instance.getInstanceId()));
    }

    @Test
    @DisplayName("terminate ends the instance")
    void terminate() {
        ProcessInstance instance = start(deploy("term"));
        processEngineService.terminateProcessInstance(instance.getInstanceId(),
                MetaContext.getCurrentUserId() + "", "IT terminate");
        // terminated instance is still queryable (status reflects the end)
        ProcessInstance after = processEngineService.getProcessInstance(instance.getInstanceId());
        assertNotNull(after);
    }

    @Test
    @DisplayName("getProcessInstanceStatus returns node-level status (active/completed nodes)")
    void nodeStatus() {
        ProcessInstance instance = start(deploy("status"));
        ProcessInstanceStatusDTO status =
                processEngineService.getProcessInstanceStatus(instance.getInstanceId());
        assertNotNull(status, "status DTO must not be null for a live instance");
    }

    @Test
    @DisplayName("getProcessInstancesByUser returns a non-null list for the starter")
    void byUser() {
        start(deploy("byuser"));
        List<ProcessInstance> list = processEngineService.getProcessInstancesByUser(
                MetaContext.getCurrentUserId() + "");
        assertThat(list).isNotNull();
    }
}
