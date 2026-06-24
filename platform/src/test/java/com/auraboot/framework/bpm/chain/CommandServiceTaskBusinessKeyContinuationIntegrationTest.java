package com.auraboot.framework.bpm.chain;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.service.ProcessDeploymentService;
import com.auraboot.framework.bpm.service.ProcessEngineService;
import com.auraboot.framework.bpm.service.TaskService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.plugin.entity.BpmProcessDefinition;
import com.auraboot.smart.framework.engine.model.instance.ProcessInstance;
import com.auraboot.smart.framework.engine.model.instance.TaskInstance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Real-engine integration test for F1: process-variable persistence across a userTask.
 *
 * <p>Proves the end-to-end mechanism with a real SmartEngine and a real userTask
 * continuation (not mocks): a {@code commandServiceTaskDelegate} serviceTask placed
 * <em>after</em> a userTask can still resolve the record it operates on, because the
 * delegate exposes the process's persisted business key as {@code ${processBusinessKey}}.
 *
 * <p>The start-time process variables are dropped on continuation — only the
 * task-completion variables flow into the next request — so the userTask here is
 * completed with an <strong>empty</strong> variable map (mirroring a production operator
 * who does not re-pass {@code alarmEventPid}). The business key, persisted on the
 * {@link ProcessInstance}, must still drive {@code targetRecordPid}.
 *
 * <p>The downstream command pipeline is mocked ({@link CommandExecutor}) so the assertion
 * isolates the F1 resolution behaviour; the full command→handler→DB chain is already
 * covered by the iot host-first golden (#106).
 */
@DisplayName("CommandServiceTaskDelegate businessKey survives userTask (F1, real SmartEngine)")
class CommandServiceTaskBusinessKeyContinuationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ProcessDeploymentService deploymentService;

    @Autowired
    private ProcessEngineService processEngineService;

    @Autowired
    private TaskService taskService;

    @MockitoBean
    private CommandExecutor commandExecutor;

    /** start → handle (userTask) → auto_clear (commandServiceTaskDelegate) → end. */
    private static final String BPMN_TEMPLATE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:smart="http://smartengine.org/schema/process"
                              targetNamespace="http://auraboot.com/bpm"
                              id="%1$s-defs">
              <bpmn:process id="%1$s" name="F1 businessKey continuation" isExecutable="true">
                <bpmn:startEvent id="start"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="handle"/>
                <bpmn:userTask id="handle" name="Handle" smart:assigneeType="starter">
                  <bpmn:incoming>f1</bpmn:incoming>
                  <bpmn:outgoing>f2</bpmn:outgoing>
                </bpmn:userTask>
                <bpmn:sequenceFlow id="f2" sourceRef="handle" targetRef="auto_clear"/>
                <bpmn:serviceTask id="auto_clear" name="Clear"
                                  smart:class="commandServiceTaskDelegate"
                                  smart:commandCode="it_f1:clear"
                                  smart:operationType="update"
                                  smart:targetRecordPid="${processBusinessKey}">
                  <bpmn:incoming>f2</bpmn:incoming>
                  <bpmn:outgoing>f3</bpmn:outgoing>
                </bpmn:serviceTask>
                <bpmn:sequenceFlow id="f3" sourceRef="auto_clear" targetRef="end"/>
                <bpmn:endEvent id="end"><bpmn:incoming>f3</bpmn:incoming></bpmn:endEvent>
              </bpmn:process>
            </bpmn:definitions>
            """;

    @Test
    @DisplayName("serviceTask after userTask resolves targetRecordPid from the persisted business key")
    void serviceTaskAfterUserTaskResolvesTargetFromBusinessKey() {
        // The command pipeline is mocked: succeed and capture the request.
        when(commandExecutor.execute(eq("it_f1:clear"), any(CommandExecuteRequest.class)))
                .thenReturn(CommandExecuteResult.builder()
                        .commandCode("it_f1:clear").phaseReached("completed")
                        .data(Map.of()).executionTimeMs(1).build());

        // Deploy start → userTask → commandServiceTaskDelegate → end.
        String processKey = "it-f1-bizkey-" + System.nanoTime();
        ProcessDeploymentService.CreateProcessRequest request =
                new ProcessDeploymentService.CreateProcessRequest(
                        processKey, "F1 businessKey continuation", "F1 IT",
                        "test", String.format(BPMN_TEMPLATE, processKey),
                        null, null, null);
        BpmProcessDefinition def = deploymentService.create(request);
        deploymentService.deploy(def.getPid());

        // Start with businessKey = the alarm pid. The record id is NOT a normal start var.
        String businessKey = "alarm-pid-" + System.nanoTime();
        Map<String, Object> startVars = new HashMap<>();
        startVars.put("_startUserId", MetaContext.getCurrentUserId() + "");
        ProcessInstance instance = processEngineService.startProcess(processKey, businessKey, startVars);
        assertThat(instance).as("process instance created").isNotNull();

        // The serviceTask must not have run yet (we're paused at the userTask).
        List<TaskInstance> tasks = taskService.getTasksByProcessInstance(instance.getInstanceId());
        assertThat(tasks).as("handle userTask is pending").isNotEmpty();
        TaskInstance handleTask = tasks.get(0);
        assertThat(handleTask.getProcessDefinitionActivityId()).isEqualTo("handle");

        // Complete the userTask with NO variables — exactly what a production operator
        // completion does. The start-time variables (incl. the business key) are gone from
        // the continuation request; only the persisted ProcessInstance.bizUniqueId remains.
        taskService.completeTask(handleTask.getInstanceId(), new HashMap<>());

        // The serviceTask ran on continuation and resolved targetRecordPid from the
        // persisted business key — not from a (now absent) request variable.
        ArgumentCaptor<CommandExecuteRequest> captor = ArgumentCaptor.forClass(CommandExecuteRequest.class);
        org.mockito.Mockito.verify(commandExecutor).execute(eq("it_f1:clear"), captor.capture());
        assertThat(captor.getValue().getTargetRecordId())
                .as("targetRecordPid resolves from the persisted business key after the userTask")
                .isEqualTo(businessKey);
    }
}
