package com.auraboot.framework.bpm;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.service.ProcessDeploymentService;
import com.auraboot.framework.bpm.service.ProcessEngineService;
import com.auraboot.framework.bpm.service.ProcessOrchestrationService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.plugin.entity.BpmProcessDefinition;
import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.model.instance.ExecutionInstance;
import com.auraboot.smart.framework.engine.model.instance.ProcessInstance;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GAP-252 (receiveTask message) — real-stack runtime verification.
 *
 * <p>A {@code receiveTask} parks the process instance until a named message is delivered.
 * SmartEngine's ReceiveTaskBehavior already parks at enter and resumes on signal; the missing
 * piece was name→execution correlation, now provided by
 * {@link ProcessOrchestrationService#deliverMessage}. This locks in the end-to-end behavior:
 * <ul>
 *   <li>Start → the instance parks at the receiveTask (one active execution at that activity).</li>
 *   <li>Delivering a non-matching message resumes nothing (correlation by messageRef).</li>
 *   <li>Delivering the matching message resumes the receiveTask and the instance completes.</li>
 * </ul>
 */
@Slf4j
@DisplayName("BPM ReceiveTask message correlation (GAP-252)")
class BpmReceiveTaskMessageIT extends BaseIntegrationTest {

    @Autowired
    private ProcessDeploymentService deploymentService;

    @Autowired
    private ProcessEngineService processEngineService;

    @Autowired
    private ProcessOrchestrationService orchestrationService;

    @Autowired
    private SmartEngine smartEngine;

    private static final String MESSAGE_NAME = "orderApproved";
    private static final String RECEIVE_NODE_ID = "wait";

    /** designerJson: start -> receiveTask(messageRef=orderApproved) -> end. */
    private static final String RECEIVE_DESIGNER_JSON = """
            {
              "nodes":[
                {"id":"s","type":"startEvent","position":{"x":0,"y":0},"data":{"type":"startEvent","label":"Start"}},
                {"id":"wait","type":"receiveTask","position":{"x":1,"y":0},
                 "data":{"type":"receiveTask","label":"Wait for approval","config":{"messageRef":"orderApproved"}}},
                {"id":"e","type":"endEvent","position":{"x":2,"y":0},"data":{"type":"endEvent","label":"End"}}
              ],
              "edges":[
                {"id":"f1","source":"s","target":"wait","data":{}},
                {"id":"f2","source":"wait","target":"e","data":{}}
              ]
            }
            """;

    private ProcessInstance deployAndStart() {
        String processKey = "recv-msg-" + System.nanoTime();
        ProcessDeploymentService.CreateProcessRequest req =
                new ProcessDeploymentService.CreateProcessRequest(
                        processKey,
                        "ReceiveTask Message Test",
                        "GAP-252 receiveTask message",
                        "test",
                        null,                    // bpmnContent — compiled from designerJson
                        RECEIVE_DESIGNER_JSON,   // designerJson — also the correlation source
                        null, null);
        BpmProcessDefinition def = deploymentService.create(req);
        deploymentService.deploy(def.getPid());

        Map<String, Object> vars = new HashMap<>();
        vars.put("_startUserId", MetaContext.getCurrentUserId() + "");
        return processEngineService.startProcess(processKey, "RECV-BIZ-" + System.nanoTime(), vars);
    }

    private List<ExecutionInstance> activeAtReceiveTask(String processInstanceId) {
        String tenantId = MetaContext.getCurrentTenantIdAsString();
        List<ExecutionInstance> active = smartEngine.getExecutionQueryService()
                .findActiveExecutionList(processInstanceId, tenantId);
        if (active == null) {
            return List.of();
        }
        return active.stream()
                .filter(e -> RECEIVE_NODE_ID.equals(e.getProcessDefinitionActivityId()))
                .toList();
    }

    @Test
    @DisplayName("GAP-252: receiveTask parks the instance, matching message resumes it to completion")
    void messageResumesReceiveTask() {
        ProcessInstance instance = deployAndStart();
        assertThat(instance).isNotNull();

        // 1. The instance is parked at the receiveTask.
        assertThat(activeAtReceiveTask(instance.getInstanceId()))
                .as("instance must park at the receiveTask waiting for a message")
                .hasSize(1);

        // 2. A non-matching message resumes nothing (correlation by messageRef).
        int resumedWrong = orchestrationService.deliverMessage(
                instance.getInstanceId(), "someOtherMessage", Map.of());
        assertThat(resumedWrong)
                .as("a message that no receiveTask is waiting for resumes nothing")
                .isZero();
        assertThat(activeAtReceiveTask(instance.getInstanceId()))
                .as("instance is still parked after a non-matching message")
                .hasSize(1);

        // 3. The matching message resumes the receiveTask.
        int resumed = orchestrationService.deliverMessage(
                instance.getInstanceId(), MESSAGE_NAME, Map.of("approver", "u1"));
        assertThat(resumed)
                .as("matching message resumes exactly the one waiting receiveTask")
                .isEqualTo(1);

        // 4. The instance advanced past the receiveTask and completed.
        assertThat(activeAtReceiveTask(instance.getInstanceId()))
                .as("no execution remains parked at the receiveTask after the message")
                .isEmpty();

        ProcessInstance finalInstance = processEngineService.getProcessInstance(instance.getInstanceId());
        String status = finalInstance.getStatus().toString().toLowerCase();
        assertThat(status)
                .as("instance completes after the receiveTask is unblocked")
                .isIn("completed", "ended", "finished");
    }
}
