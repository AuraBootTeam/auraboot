package com.auraboot.framework.bpm.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.audit.BpmAuditService;
import com.auraboot.framework.bpm.dto.TaskSummaryDto;
import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.instance.impl.DefaultProcessInstance;
import com.auraboot.smart.framework.engine.instance.impl.DefaultTaskInstance;
import com.auraboot.smart.framework.engine.model.instance.ProcessInstance;
import com.auraboot.smart.framework.engine.model.instance.TaskInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BpmIntegrationServiceTest {

    @Mock
    private SmartEngine smartEngine;
    @Mock
    private ProcessEngineService processEngineService;
    @Mock
    private TaskService taskService;
    @Mock
    private BpmAuditService bpmAuditService;

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void getUserWorkbenchEnrichesTodoTasksWithBatchedProcessLookup() {
        MetaContext.setCurrentTenantId(314L);
        TaskInstance firstTask = task("task-1", "pi-1");
        TaskInstance secondTask = task("task-2", "pi-2");
        ProcessInstance firstProcess = process("pi-1", "bk-1");
        ProcessInstance secondProcess = process("pi-2", "bk-2");
        when(taskService.getTodoTasks("user-1")).thenReturn(List.of(firstTask, secondTask));
        when(taskService.getCompletedTasks(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        when(processEngineService.getProcessInstancesByUser("user-1")).thenReturn(List.of());
        when(processEngineService.getProcessInstancesByIds(List.of("pi-1", "pi-2")))
                .thenReturn(Map.of("pi-1", firstProcess, "pi-2", secondProcess));
        BpmIntegrationService service =
                new BpmIntegrationService(smartEngine, processEngineService, taskService, bpmAuditService);

        BpmIntegrationService.WorkbenchData workbench = service.getUserWorkbench("user-1");

        assertThat(workbench.getTodoTasks())
                .extracting(TaskSummaryDto::getBusinessKey)
                .containsExactly("bk-1", "bk-2");
        verify(processEngineService).getProcessInstancesByIds(List.of("pi-1", "pi-2"));
        verify(processEngineService, never()).getProcessInstance("pi-1");
        verify(processEngineService, never()).getProcessInstance("pi-2");
    }

    private static TaskInstance task(String taskId, String processInstanceId) {
        DefaultTaskInstance task = new DefaultTaskInstance();
        task.setInstanceId(taskId);
        task.setProcessInstanceId(processInstanceId);
        task.setTitle(taskId);
        return task;
    }

    private static ProcessInstance process(String instanceId, String businessKey) {
        DefaultProcessInstance process = new DefaultProcessInstance();
        process.setInstanceId(instanceId);
        process.setBizUniqueId(businessKey);
        return process;
    }
}
