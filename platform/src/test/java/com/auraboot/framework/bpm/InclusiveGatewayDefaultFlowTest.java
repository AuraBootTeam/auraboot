package com.auraboot.framework.bpm;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.service.ProcessDeploymentService;
import com.auraboot.framework.bpm.service.ProcessEngineService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.plugin.entity.BpmProcessDefinition;
import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.model.instance.ProcessInstance;
import com.auraboot.smart.framework.engine.model.instance.TaskInstance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Inclusive Gateway Default Flow (real engine)")
class InclusiveGatewayDefaultFlowTest extends BaseIntegrationTest {

    @Autowired private ProcessDeploymentService deploymentService;
    @Autowired private ProcessEngineService processEngineService;
    @Autowired private SmartEngine smartEngine;

    private static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xmlns:smart="http://smart.alibaba.com"
                         targetNamespace="http://auraboot.com/bpm">
              <process id="%s" name="Inclusive Default" isExecutable="true">
                <startEvent id="start"/>
                <sequenceFlow id="f_start_split" sourceRef="start" targetRef="split"/>
                <inclusiveGateway id="split" default="f_fallback"/>

                <sequenceFlow id="f_high" sourceRef="split" targetRef="high">
                  <conditionExpression xsi:type="tFormalExpression"><![CDATA[amount > 100]]></conditionExpression>
                </sequenceFlow>
                <sequenceFlow id="f_vip" sourceRef="split" targetRef="vip">
                  <conditionExpression xsi:type="tFormalExpression"><![CDATA["vip".equals(priority)]]></conditionExpression>
                </sequenceFlow>
                <sequenceFlow id="f_fallback" sourceRef="split" targetRef="fallback">
                  <conditionExpression xsi:type="tFormalExpression"><![CDATA[1 == 2]]></conditionExpression>
                </sequenceFlow>

                <userTask id="high" name="High" smart:assigneeType="user" smart:assigneeId="testuser1"/>
                <userTask id="vip" name="Vip" smart:assigneeType="user" smart:assigneeId="testuser1"/>
                <userTask id="fallback" name="Fallback" smart:assigneeType="user" smart:assigneeId="testuser1"/>

                <sequenceFlow id="f_high_join" sourceRef="high" targetRef="join"/>
                <sequenceFlow id="f_vip_join" sourceRef="vip" targetRef="join"/>
                <sequenceFlow id="f_fallback_join" sourceRef="fallback" targetRef="join"/>
                <inclusiveGateway id="join"/>
                <sequenceFlow id="f_join_end" sourceRef="join" targetRef="end"/>
                <endEvent id="end"/>
              </process>
            </definitions>
            """;

    @Test
    @DisplayName("zero conditional matches activates exactly the default branch")
    void zeroMatchesActivatesDefaultOnly() {
        String key = "igw-default-" + System.nanoTime();
        String bpmn = String.format(BPMN, key);
        ProcessDeploymentService.CreateProcessRequest req =
                new ProcessDeploymentService.CreateProcessRequest(
                        key, "Inclusive Default", "Zero-match fallback", "test",
                        bpmn, null, null, null);
        BpmProcessDefinition definition = deploymentService.create(req);
        deploymentService.deploy(definition.getPid());

        Map<String, Object> vars = new HashMap<>();
        vars.put("amount", 50);
        vars.put("priority", "standard");
        vars.put("_startUserId", MetaContext.getCurrentUserId() + "");
        ProcessInstance instance = processEngineService.startProcess(
                key, "BIZ-" + System.nanoTime(), vars);

        List<TaskInstance> pending = smartEngine.getTaskQueryService().findAllPendingTaskList(
                instance.getInstanceId(), MetaContext.getCurrentTenantIdAsString());
        assertThat(pending).hasSize(1);
        assertThat(pending.getFirst().getProcessDefinitionActivityId()).isEqualTo("fallback");
    }
}
