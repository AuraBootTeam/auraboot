package com.auraboot.framework.bpm;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.dto.NodeStatusDTO;
import com.auraboot.framework.bpm.dto.ProcessInstanceStatusDTO;
import com.auraboot.framework.bpm.service.ProcessDeploymentService;
import com.auraboot.framework.bpm.service.ProcessEngineService;
import com.auraboot.framework.bpm.service.TaskService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.plugin.entity.BpmProcessDefinition;
import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.constant.RequestMapSpecialKeyConstant;
import com.auraboot.smart.framework.engine.model.instance.ProcessInstance;
import com.auraboot.smart.framework.engine.model.instance.TaskInstance;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GAP-253 follow-up: 3-way inclusive gateway split/join.
 *
 * <p>{@link InclusiveGatewayJoinTest} covers the 2-branch case (1 of 2 and
 * 2 of 2). Real workflows often have ≥3 inclusive branches (e.g. split by
 * amount / priority / region). This test locks in the N-way generalization
 * by exercising a 3-branch inclusive gateway with three selectivity
 * scenarios: 3 of 3 activate, 2 of 3 activate, 1 of 3 activates.
 *
 * <p>Layout:
 * <pre>
 *   start -&gt; igw_split
 *     -- amount &gt; 100   --&gt; task_high
 *     -- "vip".equals(priority) --&gt; task_vip
 *     -- "APAC".equals(region)  --&gt; task_apac
 *   igw_join -&gt; end
 * </pre>
 */
@Slf4j
@DisplayName("Inclusive Gateway 3-Way Split/Join (GAP-253 follow-up)")
class InclusiveGatewayThreeWayTest extends BaseIntegrationTest {

    @Autowired
    private ProcessDeploymentService deploymentService;

    @Autowired
    private ProcessEngineService processEngineService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private SmartEngine smartEngine;

    private void completeTaskDirect(String taskId) {
        Map<String, Object> vars = new HashMap<>();
        vars.put(RequestMapSpecialKeyConstant.TENANT_ID,
                MetaContext.getCurrentTenantIdAsString());
        smartEngine.getTaskCommandService().complete(taskId, vars);
    }

    private static final String INCLUSIVE_3WAY_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xmlns:smart="http://smart.alibaba.com"
                         targetNamespace="http://auraboot.com/bpm">
              <process id="%s" name="Inclusive 3-Way" isExecutable="true">
                <startEvent id="start"/>
                <sequenceFlow id="f_start_split" sourceRef="start" targetRef="igw_split"/>

                <inclusiveGateway id="igw_split"/>

                <sequenceFlow id="f_split_high" sourceRef="igw_split" targetRef="task_high">
                  <conditionExpression xsi:type="tFormalExpression"><![CDATA[amount > 100]]></conditionExpression>
                </sequenceFlow>
                <sequenceFlow id="f_split_vip" sourceRef="igw_split" targetRef="task_vip">
                  <conditionExpression xsi:type="tFormalExpression"><![CDATA["vip".equals(priority)]]></conditionExpression>
                </sequenceFlow>
                <sequenceFlow id="f_split_apac" sourceRef="igw_split" targetRef="task_apac">
                  <conditionExpression xsi:type="tFormalExpression"><![CDATA["APAC".equals(region)]]></conditionExpression>
                </sequenceFlow>

                <userTask id="task_high" name="High Amount"
                          smart:assigneeType="user" smart:assigneeId="testuser1"/>
                <sequenceFlow id="f_high_join" sourceRef="task_high" targetRef="igw_join"/>

                <userTask id="task_vip" name="VIP Review"
                          smart:assigneeType="user" smart:assigneeId="testuser1"/>
                <sequenceFlow id="f_vip_join" sourceRef="task_vip" targetRef="igw_join"/>

                <userTask id="task_apac" name="APAC Review"
                          smart:assigneeType="user" smart:assigneeId="testuser1"/>
                <sequenceFlow id="f_apac_join" sourceRef="task_apac" targetRef="igw_join"/>

                <inclusiveGateway id="igw_join"/>
                <sequenceFlow id="f_join_end" sourceRef="igw_join" targetRef="end"/>
                <endEvent id="end"/>
              </process>
            </definitions>
            """;

    private ProcessInstance deployAndStart(String suffix, Map<String, Object> startVars) {
        String key = "igw3-" + suffix + "-" + System.nanoTime();
        String bpmn = String.format(INCLUSIVE_3WAY_BPMN, key);
        ProcessDeploymentService.CreateProcessRequest req =
                new ProcessDeploymentService.CreateProcessRequest(
                        key, "IGW 3-way " + suffix, "GAP-253 3-branch", "test",
                        bpmn, null, null, null);
        BpmProcessDefinition def = deploymentService.create(req);
        deploymentService.deploy(def.getPid());

        Map<String, Object> vars = new HashMap<>(startVars);
        vars.put("_startUserId", MetaContext.getCurrentUserId() + "");
        return processEngineService.startProcess(key, "BIZ-" + System.nanoTime(), vars);
    }

    private List<String> activeActivityIds(String instanceId) {
        return taskService.getTasksByProcessInstance(instanceId).stream()
                .map(TaskInstance::getProcessDefinitionActivityId)
                .sorted()
                .toList();
    }

    // =====================================================================
    // 3-of-3: all conditions satisfied → all three branches activate
    // =====================================================================
    @Test
    @DisplayName("3-of-3: all conditions match → 3 parallel tasks; join waits for all three")
    void threeOfThreeBranchesActivate() {
        Map<String, Object> startVars = new HashMap<>();
        startVars.put("amount", 500);
        startVars.put("priority", "vip");
        startVars.put("region", "APAC");
        ProcessInstance instance = deployAndStart("3of3", startVars);

        assertThat(activeActivityIds(instance.getInstanceId()))
                .as("all 3 conditions matched → 3 branches active")
                .containsExactly("task_apac", "task_high", "task_vip");

        // Complete 2 of 3; join must NOT fire yet.
        List<TaskInstance> tasks = taskService
                .getTasksByProcessInstance(instance.getInstanceId());
        TaskInstance high = tasks.stream()
                .filter(t -> "task_high".equals(t.getProcessDefinitionActivityId()))
                .findFirst().orElseThrow();
        TaskInstance vip = tasks.stream()
                .filter(t -> "task_vip".equals(t.getProcessDefinitionActivityId()))
                .findFirst().orElseThrow();
        completeTaskDirect(high.getInstanceId());
        completeTaskDirect(vip.getInstanceId());

        ProcessInstanceStatusDTO mid = processEngineService
                .getProcessInstanceStatus(instance.getInstanceId());
        assertThat(mid.status())
                .as("2 of 3 complete → join still waiting on task_apac")
                .isNotEqualTo("completed");
        List<String> midActive = mid.currentNodes().stream()
                .map(NodeStatusDTO::nodeId)
                .filter(id -> id.startsWith("task_"))
                .toList();
        assertThat(midActive).containsExactly("task_apac");

        // Complete 3rd → join fires → instance ends
        TaskInstance apac = taskService.getTasksByProcessInstance(instance.getInstanceId())
                .stream()
                .filter(t -> "task_apac".equals(t.getProcessDefinitionActivityId()))
                .findFirst().orElseThrow();
        completeTaskDirect(apac.getInstanceId());

        ProcessInstanceStatusDTO finalStatus = processEngineService
                .getProcessInstanceStatus(instance.getInstanceId());
        assertThat(finalStatus.status())
                .as("all three branches completed → join fires → instance ends")
                .isEqualTo("completed");
    }

    // =====================================================================
    // 2-of-3: exactly two conditions satisfied → two branches; join waits
    // =====================================================================
    @Test
    @DisplayName("2-of-3: two conditions match → 2 tasks; join waits for both of them only")
    void twoOfThreeBranchesActivate() {
        Map<String, Object> startVars = new HashMap<>();
        startVars.put("amount", 500);                  // → task_high
        startVars.put("priority", "vip");              // → task_vip
        startVars.put("region", "EMEA");               // NO task_apac
        ProcessInstance instance = deployAndStart("2of3", startVars);

        assertThat(activeActivityIds(instance.getInstanceId()))
                .as("exactly 2 of 3 conditions matched")
                .containsExactly("task_high", "task_vip");

        // Complete task_high only → join must NOT fire; task_vip still active.
        TaskInstance high = taskService.getTasksByProcessInstance(instance.getInstanceId())
                .stream()
                .filter(t -> "task_high".equals(t.getProcessDefinitionActivityId()))
                .findFirst().orElseThrow();
        completeTaskDirect(high.getInstanceId());

        ProcessInstanceStatusDTO mid = processEngineService
                .getProcessInstanceStatus(instance.getInstanceId());
        assertThat(mid.status())
                .as("only 1 of 2 active branches finished → still running")
                .isNotEqualTo("completed");

        // Complete task_vip → join fires (task_apac was never activated, so it
        // is NOT a blocker — this is the defining inclusive-join semantics).
        TaskInstance vip = taskService.getTasksByProcessInstance(instance.getInstanceId())
                .stream()
                .filter(t -> "task_vip".equals(t.getProcessDefinitionActivityId()))
                .findFirst().orElseThrow();
        completeTaskDirect(vip.getInstanceId());

        ProcessInstanceStatusDTO finalStatus = processEngineService
                .getProcessInstanceStatus(instance.getInstanceId());
        assertThat(finalStatus.status())
                .as("all ACTIVATED branches done → join proceeds even though task_apac was never taken")
                .isEqualTo("completed");
    }

    // =====================================================================
    // 1-of-3: only one condition satisfied → join fires immediately on that branch
    // =====================================================================
    @Test
    @DisplayName("1-of-3: one condition matches → 1 task; completing it drives instance to end")
    void oneOfThreeBranchesActivates() {
        Map<String, Object> startVars = new HashMap<>();
        startVars.put("amount", 50);                  // fails > 100
        startVars.put("priority", "regular");         // fails vip
        startVars.put("region", "APAC");              // → task_apac only
        ProcessInstance instance = deployAndStart("1of3", startVars);

        assertThat(activeActivityIds(instance.getInstanceId()))
                .as("only region=APAC matches → task_apac alone")
                .containsExactly("task_apac");

        TaskInstance apac = taskService.getTasksByProcessInstance(instance.getInstanceId())
                .stream()
                .filter(t -> "task_apac".equals(t.getProcessDefinitionActivityId()))
                .findFirst().orElseThrow();
        completeTaskDirect(apac.getInstanceId());

        ProcessInstanceStatusDTO finalStatus = processEngineService
                .getProcessInstanceStatus(instance.getInstanceId());
        assertThat(finalStatus.status())
                .as("sole active branch finished → join immediately fires → instance ends")
                .isEqualTo("completed");
    }
}
