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
 * GAP-253 regression: inclusiveGateway join side.
 *
 * <p>The split side works (conditions evaluate, matching branches activate),
 * but before this fix, completing any userTask on an inclusive branch
 * threw {@code ClassCastException: class java.lang.String cannot be cast
 * to class java.util.List} from
 * {@code InclusiveGatewayHelper#findTriggerActivityIdsFromDB} because
 * {@code AuraVariablePersister.deserialize} returned the raw JSON string
 * for the engine-internal {@code $triggerActivityIds$} variable instead
 * of the {@code List<String>} the helper casts to.
 *
 * <p>Process layout:
 * <pre>
 *   start -> igw_split
 *     -- amount > 100  --&gt; task_high    --\
 *     -- priority=vip  --&gt; task_premium --/ igw_join -&gt; end
 * </pre>
 *
 * <p>Starting with {@code amount=200, priority=vip} activates both branches;
 * completing both must drive the instance to {@code completed}.
 */
@Slf4j
@DisplayName("Inclusive Gateway Join (GAP-253)")
class InclusiveGatewayJoinTest extends BaseIntegrationTest {

    @Autowired
    private ProcessDeploymentService deploymentService;

    @Autowired
    private ProcessEngineService processEngineService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private SmartEngine smartEngine;

    /**
     * Bypass {@link TaskService#completeTask} which requires a Spring Security
     * user context. The engine itself only needs the tenant id.
     */
    private void completeTaskDirect(String taskId, Map<String, Object> variables) {
        Map<String, Object> vars = new HashMap<>(variables);
        vars.put(RequestMapSpecialKeyConstant.TENANT_ID,
                MetaContext.getCurrentTenantIdAsString());
        smartEngine.getTaskCommandService().complete(taskId, vars);
    }

    private static final String INCLUSIVE_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xmlns:smart="http://smart.alibaba.com"
                         targetNamespace="http://auraboot.com/bpm">
              <process id="%s" name="Inclusive GW Join" isExecutable="true">
                <startEvent id="start"/>
                <sequenceFlow id="f_start_split" sourceRef="start" targetRef="igw_split"/>

                <inclusiveGateway id="igw_split"/>

                <sequenceFlow id="f_split_high" sourceRef="igw_split" targetRef="task_high">
                  <conditionExpression xsi:type="tFormalExpression"><![CDATA[amount > 100]]></conditionExpression>
                </sequenceFlow>
                <sequenceFlow id="f_split_premium" sourceRef="igw_split" targetRef="task_premium">
                  <conditionExpression xsi:type="tFormalExpression"><![CDATA["vip".equals(priority)]]></conditionExpression>
                </sequenceFlow>

                <userTask id="task_high" name="High Amount"
                          smart:assigneeType="user" smart:assigneeId="testuser1"/>
                <sequenceFlow id="f_high_join" sourceRef="task_high" targetRef="igw_join"/>

                <userTask id="task_premium" name="Premium Review"
                          smart:assigneeType="user" smart:assigneeId="testuser1"/>
                <sequenceFlow id="f_premium_join" sourceRef="task_premium" targetRef="igw_join"/>

                <inclusiveGateway id="igw_join"/>
                <sequenceFlow id="f_join_end" sourceRef="igw_join" targetRef="end"/>
                <endEvent id="end"/>
              </process>
            </definitions>
            """;

    private ProcessInstance deployAndStart(String suffix, Map<String, Object> startVars) {
        String key = "igwjoin-" + suffix + "-" + System.nanoTime();
        String bpmn = String.format(INCLUSIVE_BPMN, key);
        ProcessDeploymentService.CreateProcessRequest req =
                new ProcessDeploymentService.CreateProcessRequest(
                        key, "Inclusive Join " + suffix, "GAP-253 coverage", "test",
                        bpmn, null, null, null);
        BpmProcessDefinition def = deploymentService.create(req);
        deploymentService.deploy(def.getPid());

        Map<String, Object> vars = new HashMap<>(startVars);
        vars.put("_startUserId", MetaContext.getCurrentUserId() + "");
        return processEngineService.startProcess(key, "BIZ-" + System.nanoTime(), vars);
    }

    @Test
    @DisplayName("both branches activate; completing both drives join to end (GAP-253)")
    void inclusiveJoinCompletesAfterEveryActiveBranch() {
        Map<String, Object> startVars = new HashMap<>();
        startVars.put("amount", 200);
        startVars.put("priority", "vip");
        ProcessInstance instance = deployAndStart("both", startVars);
        assertThat(instance).isNotNull();

        List<TaskInstance> active = taskService.getTasksByProcessInstance(instance.getInstanceId());
        List<String> activeIds = active.stream()
                .map(TaskInstance::getProcessDefinitionActivityId)
                .sorted()
                .toList();
        assertThat(activeIds)
                .as("both conditions satisfied → both branches must activate")
                .containsExactly("task_high", "task_premium");

        // Complete task_high first — instance still active on task_premium.
        // Before the fix this call threw ClassCastException at join evaluation.
        TaskInstance highTask = active.stream()
                .filter(t -> "task_high".equals(t.getProcessDefinitionActivityId()))
                .findFirst().orElseThrow();
        completeTaskDirect(highTask.getInstanceId(), new HashMap<>());

        ProcessInstanceStatusDTO midStatus =
                processEngineService.getProcessInstanceStatus(instance.getInstanceId());
        assertThat(midStatus.status())
                .as("instance still running while task_premium is active")
                .isNotEqualTo("completed");
        List<String> midActive = midStatus.currentNodes().stream()
                .map(NodeStatusDTO::nodeId)
                .filter(id -> id.startsWith("task_"))
                .toList();
        assertThat(midActive).containsExactly("task_premium");

        // Complete task_premium → join latch fires → instance completes.
        TaskInstance premiumTask = taskService
                .getTasksByProcessInstance(instance.getInstanceId())
                .stream()
                .filter(t -> "task_premium".equals(t.getProcessDefinitionActivityId()))
                .findFirst().orElseThrow();
        completeTaskDirect(premiumTask.getInstanceId(), new HashMap<>());

        ProcessInstanceStatusDTO finalStatus =
                processEngineService.getProcessInstanceStatus(instance.getInstanceId());
        assertThat(finalStatus.status())
                .as("instance must complete once every activated inclusive branch finishes")
                .isEqualTo("completed");
        assertThat(finalStatus.currentNodes())
                .as("no active nodes after completion")
                .isEmpty();
    }

    @Test
    @DisplayName("only one branch condition matches; completing it drives join to end")
    void inclusiveJoinWithSingleActiveBranch() {
        // amount=50 fails amount>100 ; priority=vip matches → only task_premium active.
        Map<String, Object> startVars = new HashMap<>();
        startVars.put("amount", 50);
        startVars.put("priority", "vip");
        ProcessInstance instance = deployAndStart("one", startVars);

        List<TaskInstance> active = taskService.getTasksByProcessInstance(instance.getInstanceId());
        List<String> activeIds = active.stream()
                .map(TaskInstance::getProcessDefinitionActivityId)
                .toList();
        assertThat(activeIds)
                .as("only priority=vip matches → task_premium alone")
                .containsExactly("task_premium");

        completeTaskDirect(active.get(0).getInstanceId(), new HashMap<>());

        ProcessInstanceStatusDTO finalStatus =
                processEngineService.getProcessInstanceStatus(instance.getInstanceId());
        assertThat(finalStatus.status())
                .as("single activated branch completes → join fires → instance ends")
                .isEqualTo("completed");
    }
}
