package com.auraboot.framework.bpm;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.dto.ProcessInstanceStatusDTO;
import com.auraboot.framework.bpm.service.ProcessDeploymentService;
import com.auraboot.framework.bpm.service.ProcessEngineService;
import com.auraboot.framework.bpm.service.TaskService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.plugin.entity.BpmProcessDefinition;
import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.model.instance.InstanceStatus;
import com.auraboot.smart.framework.engine.model.instance.ProcessInstance;
import com.auraboot.smart.framework.engine.model.instance.TaskInstance;
import com.auraboot.smart.framework.engine.constant.RequestMapSpecialKeyConstant;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives a 3-branch exclusive gateway through SmartEngine MVEL evaluation,
 * asserting each branch reaches the correct UserTask and that the process
 * completes through EndEvent. Companion to {@link BpmGatewayTest}, but unlike
 * that test does NOT skip on engine errors — engine failures are real failures.
 *
 * <p>Process layout:
 * <pre>
 *   start -> submit(UserTask) -> gw(ExclusiveGateway)
 *     -- amount &gt;= 50000 --&gt; high(UserTask) -&gt; end
 *     -- amount &gt;= 10000 --&gt; mid(UserTask)  -&gt; end
 *     -- default          --&gt; auto(UserTask) -&gt; end
 * </pre>
 */
@Slf4j
@DisplayName("Exclusive Gateway Branch Execution (real engine, no skip)")
class GatewayBranchExecutionTest extends BaseIntegrationTest {

    @Autowired
    private ProcessDeploymentService deploymentService;

    @Autowired
    private ProcessEngineService processEngineService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private SmartEngine smartEngine;

    /**
     * Bypass {@link TaskService#completeTask} which requires a Spring Security user
     * context (returns null in tests, causing NPE in canCompleteTask). The engine
     * itself only needs the tenant id.
     */
    private void completeTaskDirect(String taskId, Map<String, Object> variables) {
        Map<String, Object> vars = new HashMap<>(variables);
        vars.put(RequestMapSpecialKeyConstant.TENANT_ID,
                MetaContext.getCurrentTenantIdAsString());
        smartEngine.getTaskCommandService().complete(taskId, vars);
    }

    private static final String THREE_BRANCH_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xmlns:smart="http://smart.alibaba.com"
                         targetNamespace="http://auraboot.com/bpm">
              <process id="%s" name="Three Branch Gateway" isExecutable="true">
                <startEvent id="start"/>
                <sequenceFlow id="f_start_submit" sourceRef="start" targetRef="submit"/>
                <userTask id="submit" name="Submit"
                          smart:assigneeType="user" smart:assigneeId="testuser1"/>
                <sequenceFlow id="f_submit_gw" sourceRef="submit" targetRef="gw"/>

                <exclusiveGateway id="gw"/>

                <sequenceFlow id="f_gw_high" sourceRef="gw" targetRef="high">
                  <conditionExpression xsi:type="tFormalExpression"><![CDATA[amount >= 50000]]></conditionExpression>
                </sequenceFlow>
                <sequenceFlow id="f_gw_mid" sourceRef="gw" targetRef="mid">
                  <conditionExpression xsi:type="tFormalExpression"><![CDATA[amount >= 10000 && amount < 50000]]></conditionExpression>
                </sequenceFlow>
                <sequenceFlow id="f_gw_auto" sourceRef="gw" targetRef="auto">
                  <conditionExpression xsi:type="tFormalExpression"><![CDATA[amount < 10000]]></conditionExpression>
                </sequenceFlow>

                <userTask id="high" name="High Approval"
                          smart:assigneeType="user" smart:assigneeId="testuser1"/>
                <sequenceFlow id="f_high_end" sourceRef="high" targetRef="end"/>

                <userTask id="mid" name="Mid Approval"
                          smart:assigneeType="user" smart:assigneeId="testuser1"/>
                <sequenceFlow id="f_mid_end" sourceRef="mid" targetRef="end"/>

                <userTask id="auto" name="Auto Approval"
                          smart:assigneeType="user" smart:assigneeId="testuser1"/>
                <sequenceFlow id="f_auto_end" sourceRef="auto" targetRef="end"/>

                <endEvent id="end"/>
              </process>
            </definitions>
            """;

    private ProcessInstance deployAndStart(String suffix) {
        String key = "gwbranch-" + suffix + "-" + System.nanoTime();
        String bpmn = String.format(THREE_BRANCH_BPMN, key);
        ProcessDeploymentService.CreateProcessRequest req =
                new ProcessDeploymentService.CreateProcessRequest(
                        key, "Three Branch " + suffix, "Branch coverage", "test",
                        bpmn, null, null, null);
        BpmProcessDefinition def = deploymentService.create(req);
        deploymentService.deploy(def.getPid());

        Map<String, Object> startVars = new HashMap<>();
        startVars.put("_startUserId", MetaContext.getCurrentUserId() + "");
        return processEngineService.startProcess(key, "BIZ-" + System.nanoTime(), startVars);
    }

    private TaskInstance currentTask(String instanceId) {
        List<TaskInstance> tasks = taskService.getTasksByProcessInstance(instanceId);
        assertThat(tasks).as("Pending tasks for instance %s", instanceId).isNotEmpty();
        return tasks.get(0);
    }

    /**
     * Drive a gateway process: complete submit task with given amount, then complete
     * whichever branch task is reached, asserting it matches the expected activityId.
     * Returns the final instance status.
     */
    private InstanceStatus driveBranch(String suffix, Object amount, String expectedBranchTaskId) {
        ProcessInstance instance = deployAndStart(suffix);
        assertThat(instance).isNotNull();

        TaskInstance submit = currentTask(instance.getInstanceId());
        assertThat(submit.getProcessDefinitionActivityId()).isEqualTo("submit");

        Map<String, Object> vars = new HashMap<>();
        vars.put("amount", amount);
        completeTaskDirect(submit.getInstanceId(), vars);

        TaskInstance branchTask = currentTask(instance.getInstanceId());
        assertThat(branchTask.getProcessDefinitionActivityId())
                .as("Routed branch for amount=%s", amount)
                .isEqualTo(expectedBranchTaskId);

        // Drive the branch task to completion so the process reaches EndEvent
        completeTaskDirect(branchTask.getInstanceId(), new HashMap<>());

        ProcessInstanceStatusDTO status = processEngineService.getProcessInstanceStatus(instance.getInstanceId());
        assertThat(status.status()).as("Final status for branch %s", expectedBranchTaskId).isEqualTo("completed");
        assertThat(status.currentNodes()).as("No active nodes after completion").isEmpty();

        ProcessInstance reloaded = processEngineService.getProcessInstance(instance.getInstanceId());
        return reloaded.getStatus();
    }

    @Test
    @DisplayName("amount=60000 routes to high branch and completes")
    void highBranchReachesEnd() {
        InstanceStatus status = driveBranch("high", 60000, "high");
        assertThat(status).isEqualTo(InstanceStatus.completed);
    }

    @Test
    @DisplayName("amount=20000 routes to mid branch and completes")
    void midBranchReachesEnd() {
        InstanceStatus status = driveBranch("mid", 20000, "mid");
        assertThat(status).isEqualTo(InstanceStatus.completed);
    }

    @Test
    @DisplayName("amount=500 routes to default (auto) branch and completes")
    void defaultBranchReachesEnd() {
        InstanceStatus status = driveBranch("default", 500, "auto");
        assertThat(status).isEqualTo(InstanceStatus.completed);
    }

    @Test
    @DisplayName("converter rejects deploy with non-default outgoing edge missing condition")
    void deployFailsForInvalidGateway() {
        // designerJson with the bug pattern: gateway outgoing edge has only a label
        String invalidJson = """
                {
                  "key": "bad-gw-%d",
                  "name": "Bad Gateway",
                  "nodes": [
                    {"id":"start","type":"startEvent","data":{"type":"startEvent"}},
                    {"id":"gw","type":"exclusiveGateway","data":{"type":"exclusiveGateway"}},
                    {"id":"a","type":"userTask","data":{"type":"userTask","label":"A","config":{}}},
                    {"id":"b","type":"userTask","data":{"type":"userTask","label":"B","config":{}}},
                    {"id":"end","type":"endEvent","data":{"type":"endEvent"}}
                  ],
                  "edges": [
                    {"id":"e1","source":"start","target":"gw","data":{}},
                    {"id":"e2","source":"gw","target":"a","data":{"label":"金额>=5万"}},
                    {"id":"e3","source":"gw","target":"b","data":{"isDefault":true}},
                    {"id":"e4","source":"a","target":"end","data":{}},
                    {"id":"e5","source":"b","target":"end","data":{}}
                  ]
                }
                """.formatted(System.nanoTime());

        // Use the converter directly: deploy path eventually calls JsonToBpmnConverter
        com.auraboot.framework.bpm.converter.JsonToBpmnConverter converter =
                applicationContext.getBean(com.auraboot.framework.bpm.converter.JsonToBpmnConverter.class);

        assertThatThrownBy(() -> converter.convert(invalidJson))
                .isInstanceOf(com.auraboot.framework.bpm.converter.BpmnConversionException.class)
                .hasMessageContaining("missing a condition expression")
                .hasMessageContaining("e2");
    }
}
