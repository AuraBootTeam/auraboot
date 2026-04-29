package com.auraboot.framework.bpm.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.audit.BpmAuditService;
import com.auraboot.framework.bpm.dto.TaskSubmitRequest;
import com.auraboot.framework.bpm.service.BpmFormService;
import com.auraboot.framework.bpm.service.TaskService;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.plugin.mapper.BpmProcessDefinitionMapper;
import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.model.instance.ProcessInstance;
import com.auraboot.smart.framework.engine.model.instance.TaskInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BpmFormControllerTest {

    @Mock
    private BpmFormService formService;

    @Mock
    private TaskService taskService;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private SmartEngine smartEngine;

    @Mock
    private BpmProcessDefinitionMapper processDefinitionMapper;

    @Mock
    private BpmAuditService bpmAuditService;

    private BpmFormController controller;

    @BeforeEach
    void setUp() {
        MetaContext.setCurrentTenantId(202L);
        controller = new BpmFormController(
                formService,
                taskService,
                smartEngine,
                processDefinitionMapper,
                bpmAuditService
        );
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void submitTaskFormRejectWithoutCommentReturnsError() {
        when(taskService.getTask("task-1")).thenReturn(mock(TaskInstance.class));

        TaskSubmitRequest request = TaskSubmitRequest.builder()
                .saveStrategy("variable_only")
                .variables(Map.of("decision", "reject", "comment", " "))
                .build();

        ApiResponse<Map<String, Object>> response = controller.submitTaskForm("task-1", request);

        assertFalse(response.isSuccess());
        assertEquals("Rejection comment is required", response.getMessage());
        verify(formService, never()).submitTaskFormWithStrategy(any(), any(), any(), any());
        verifyNoInteractions(smartEngine);
    }

    @Test
    void submitTaskFormRejectedTaskResultWithoutCommentReturnsError() {
        when(taskService.getTask("task-1")).thenReturn(mock(TaskInstance.class));

        TaskSubmitRequest request = TaskSubmitRequest.builder()
                .saveStrategy("variable_only")
                .variables(Map.of("taskResult", "rejected"))
                .build();

        ApiResponse<Map<String, Object>> response = controller.submitTaskForm("task-1", request);

        assertFalse(response.isSuccess());
        assertEquals("Rejection comment is required", response.getMessage());
        verify(formService, never()).submitTaskFormWithStrategy(any(), any(), any(), any());
        verifyNoInteractions(smartEngine);
    }

    @Test
    void submitTaskFormRejectWithCommentDelegates() {
        TaskInstance task = mock(TaskInstance.class);
        when(task.getProcessInstanceId()).thenReturn("pi-1");
        when(task.getProcessDefinitionActivityId()).thenReturn("review");
        when(taskService.getTask("task-1")).thenReturn(task);

        ProcessInstance processInstance = mock(ProcessInstance.class);
        when(processInstance.getProcessDefinitionId()).thenReturn("process-key");
        when(processInstance.getBizUniqueId()).thenReturn("biz-1");
        when(smartEngine.getProcessQueryService().findById("pi-1", "202")).thenReturn(processInstance);
        when(smartEngine.getVariableQueryService().findProcessInstanceVariableList("pi-1", "202"))
                .thenReturn(List.of());

        TaskSubmitRequest request = TaskSubmitRequest.builder()
                .saveStrategy("variable_only")
                .variables(Map.of("decision", "reject", "comment", "Needs corrected data"))
                .build();

        ApiResponse<Map<String, Object>> response = controller.submitTaskForm("task-1", request);

        assertTrue(response.isSuccess());
        assertNotNull(response.getData());
        verify(formService).submitTaskFormWithStrategy(eq("task-1"), eq(request), isNull(), eq("biz-1"));
    }
}
