package com.auraboot.framework.bpm;

import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.constant.RequestMapSpecialKeyConstant;
import com.auraboot.smart.framework.engine.model.instance.ProcessInstance;
import com.auraboot.smart.framework.engine.model.instance.TaskAssigneeInstance;
import com.auraboot.smart.framework.engine.model.instance.TaskInstance;
import com.auraboot.smart.framework.engine.constant.AdHocConstant;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.service.ProcessDeploymentService;
import com.auraboot.framework.bpm.service.ProcessEngineService;
import com.auraboot.framework.bpm.service.TaskService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.plugin.entity.BpmProcessDefinition;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Integration tests for GAP-249: SmartEngine userTask multi-instance runtime.
 *
 * <p>Verifies the three platform wiring points:
 * <ol>
 *   <li>{@link com.auraboot.framework.bpm.config.DefaultMultiInstanceCounter} registered
 *       on {@code ProcessEngineConfiguration} so SmartEngine's
 *       {@code UserTaskBehavior.handleMultiInstance} can count passed/rejected tasks.
 *   <li>{@link com.auraboot.framework.bpm.converter.JsonToBpmnConverter} writing
 *       {@code smart:miCollection} / {@code smart:miElementVariable} attributes on
 *       the userTask element so the parser surfaces them in {@code activity.properties}.
 *   <li>{@link com.auraboot.framework.bpm.config.IdAndGroupTaskAssigneeDispatcher}
 *       reading {@code miCollection}, resolving it to a list from the process request
 *       context, and returning one candidate per element — SmartEngine then creates
 *       one EI+TI per candidate (1:1) giving true N-way parallel multi-instance.
 * </ol>
 *
 * <p>Process under test: Start → approve_each(userTask, MI parallel over {@code ${approverList}}) → End.
 */
@Slf4j
@DisplayName("BPM Multi-Instance Runtime (GAP-249)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BpmMultiInstanceTest extends BaseIntegrationTest {

    @Autowired
    private ProcessDeploymentService deploymentService;

    @Autowired
    private ProcessEngineService processEngineService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private SmartEngine smartEngine;

    /**
     * Complete a task via the raw SmartEngine API so we bypass AuraBoot's
     * {@code canCompleteTask} authorization — the MI collection elements are
     * synthetic user ids ("u1", "alice" …) that would not match the test
     * user's tenant membership. The GAP-249 wiring under test is about
     * engine-level spawn/complete semantics, not the HTTP authorization layer
     * (which is covered by {@code BpmTaskOperationTest}).
     */
    private void completeRaw(TaskInstance task) {
        Map<String, Object> vars = new HashMap<>();
        vars.put(RequestMapSpecialKeyConstant.TENANT_ID,
                MetaContext.getCurrentTenantIdAsString());
        vars.put(RequestMapSpecialKeyConstant.TASK_INSTANCE_TAG, AdHocConstant.AGREE);
        smartEngine.getTaskCommandService().complete(task.getInstanceId(), vars);
    }

    // BPMN XML as emitted by JsonToBpmnConverter for a userTask with
    // multiInstance.enabled=true, sequential=false, collection=${approverList},
    // elementVariable=currentApprover. We construct it directly here rather than
    // round-tripping through the converter so the test is independent of designer
    // JSON concerns — the converter path is covered by JsonToBpmnConverterTest.
    private static final String MI_BPMN_TEMPLATE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:smart="http://smart.alibaba.com"
                         targetNamespace="http://auraboot.com/bpm">
              <process id="%s" name="Multi-Instance Test" isExecutable="true">
                <startEvent id="start" name="Start"/>
                <sequenceFlow id="flow_start_mi" sourceRef="start" targetRef="approve_each"/>

                <userTask id="approve_each" name="Approve Each"
                          smart:miCollection="${approverList}"
                          smart:miElementVariable="currentApprover"
                          smart:miSequential="false">
                  <multiInstanceLoopCharacteristics isSequential="false">
                    <completionCondition><![CDATA[nrOfCompletedInstances == nrOfInstances]]></completionCondition>
                  </multiInstanceLoopCharacteristics>
                </userTask>

                <sequenceFlow id="flow_mi_end" sourceRef="approve_each" targetRef="end"/>
                <endEvent id="end" name="End"/>
              </process>
            </definitions>
            """;

    private ProcessInstance deployAndStart(String suffix, List<String> approvers) {
        String processKey = "mi-test-" + suffix + "-" + System.nanoTime();
        String bpmn = String.format(MI_BPMN_TEMPLATE, processKey);

        ProcessDeploymentService.CreateProcessRequest req =
                new ProcessDeploymentService.CreateProcessRequest(
                        processKey,
                        "MI Test " + suffix,
                        "GAP-249 multi-instance runtime",
                        "test",
                        bpmn,
                        null, null, null
                );
        BpmProcessDefinition def = deploymentService.create(req);
        deploymentService.deploy(def.getPid());

        Map<String, Object> vars = new HashMap<>();
        vars.put("_startUserId", MetaContext.getCurrentUserId() + "");
        vars.put("approverList", approvers);

        return processEngineService.startProcess(processKey,
                "MI-BIZ-" + System.nanoTime(), vars);
    }

    private List<TaskInstance> activeTasksFor(String processInstanceId) {
        List<TaskInstance> all = taskService.getTasksByProcessInstance(processInstanceId);
        if (all == null) return List.of();
        return all.stream()
                // SmartEngine keeps historical TIs too; filter to approve_each only
                .filter(t -> "approve_each".equals(t.getProcessDefinitionActivityId()))
                .toList();
    }

    // ==================== GAP249-01: N collection → N parallel tasks ====================

    @Test
    @Order(1)
    @DisplayName("GAP249-01: collection of 3 approvers spawns 3 parallel tasks")
    void miSpawnsParallelTasksPerCollectionElement() {
        ProcessInstance instance = deployAndStart("spawn", List.of("alice", "bob", "carol"));
        assertNotNull(instance, "process instance must be created");

        List<TaskInstance> tasks = activeTasksFor(instance.getInstanceId());
        assertThat(tasks)
                .as("MI-enabled userTask with 3-element collection must spawn 3 TaskInstances; "
                        + "without the GAP-249 wiring it would spawn only 1")
                .hasSize(3);

        // Each TI should be assigned to a distinct approver from the collection.
        // We query assignees via the dedicated SmartEngine query API since the
        // TaskInstance list returned by TaskService.getTasksByProcessInstance
        // does not eagerly hydrate TaskAssigneeInstance rows.
        Set<String> assigneeIds = new HashSet<>();
        for (TaskInstance ti : tasks) {
            List<TaskAssigneeInstance> assignees = smartEngine
                    .getTaskAssigneeQueryService()
                    .findList(ti.getInstanceId(),
                            MetaContext.getCurrentTenantIdAsString());
            if (assignees != null) {
                assignees.forEach(a -> assigneeIds.add(a.getAssigneeId()));
            }
        }
        assertThat(assigneeIds)
                .as("three MI tasks must be assigned to the three collection elements")
                .containsExactlyInAnyOrder("alice", "bob", "carol");
    }

    // ==================== GAP249-02: partial complete keeps instance active ====================

    @Test
    @Order(2)
    @DisplayName("GAP249-02: completing 1 of 3 tasks leaves 2 active, instance running")
    void completingOneLeavesRemainingActive() {
        ProcessInstance instance = deployAndStart("partial", List.of("u1", "u2", "u3"));

        List<TaskInstance> tasks = activeTasksFor(instance.getInstanceId());
        assertThat(tasks).hasSize(3);

        // Complete exactly one task with tag=agree.
        completeRaw(tasks.get(0));

        // Use SmartEngine's pending-task query which filters to InstanceStatus==created
        // (i.e. not yet completed). With completionCondition requiring ALL instances
        // to finish, exactly 2 of the original 3 must remain pending.
        List<TaskInstance> pending = smartEngine.getTaskQueryService()
                .findAllPendingTaskList(instance.getInstanceId());
        long remainingCount = pending == null ? 0 : pending.stream()
                .filter(t -> "approve_each".equals(t.getProcessDefinitionActivityId()))
                .count();
        assertThat(remainingCount)
                .as("after completing 1 of 3 MI tasks, 2 must remain pending "
                        + "(completionCondition = nrOfCompletedInstances == nrOfInstances)")
                .isEqualTo(2L);
    }

    // ==================== GAP249-03: complete all → instance ends ====================

    @Test
    @Order(3)
    @DisplayName("GAP249-03: completing all MI tasks drives instance to END")
    void completingAllEndsInstance() {
        ProcessInstance instance = deployAndStart("all", List.of("a", "b"));

        List<TaskInstance> tasks = activeTasksFor(instance.getInstanceId());
        assertThat(tasks).hasSize(2);

        for (TaskInstance t : tasks) {
            completeRaw(t);
        }

        // After all MI tasks complete and completionCondition passes,
        // UserTaskBehavior signals out of the MI activity; the sequence flow
        // leads to endEvent and the instance finishes.
        ProcessInstance finalInstance = processEngineService
                .getProcessInstance(instance.getInstanceId());
        assertThat(finalInstance).isNotNull();
        assertThat(finalInstance.getStatus())
                .as("process instance must be completed after all MI tasks finish")
                .isNotNull();
        String status = finalInstance.getStatus().toString().toLowerCase();
        assertThat(status)
                .as("final status must indicate completion")
                .isIn("completed", "ended", "finished");
    }
}
