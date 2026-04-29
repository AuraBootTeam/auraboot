package com.auraboot.framework.bpm.controller;

import com.auraboot.framework.bpm.service.CcService;
import com.auraboot.framework.bpm.service.ProcessEngineService;
import com.auraboot.framework.bpm.service.TaskService;
import com.auraboot.framework.bpm.service.WithdrawService;
import com.auraboot.framework.common.dto.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TaskControllerTest {

    @Mock
    private TaskService taskService;

    @Mock
    private ProcessEngineService processEngineService;

    @Mock
    private WithdrawService withdrawService;

    @Mock
    private CcService ccService;

    private TaskController controller;

    @BeforeEach
    void setUp() {
        controller = new TaskController(taskService, processEngineService, withdrawService, ccService);
    }

    @Test
    void rejectTaskWithoutCommentReturnsError() {
        ApiResponse<Void> response =
                controller.rejectTask("task-1", new TaskController.RejectTaskRequest(" ", Map.of()));

        assertFalse(response.isSuccess());
        assertEquals("Rejection comment is required", response.getMessage());
        verify(taskService, never()).rejectTask(anyString(), anyString(), any());
    }

    @Test
    void rejectTaskNullBodyReturnsError() {
        ApiResponse<Void> response = controller.rejectTask("task-1", null);

        assertFalse(response.isSuccess());
        assertEquals("Rejection comment is required", response.getMessage());
        verify(taskService, never()).rejectTask(anyString(), anyString(), any());
    }

    @Test
    void rejectTaskWithCommentDelegates() {
        Map<String, Object> variables = Map.of("taskResult", "rejected");

        ApiResponse<Void> response = controller.rejectTask(
                "task-1",
                new TaskController.RejectTaskRequest("Needs corrected data", variables)
        );

        assertTrue(response.isSuccess());
        verify(taskService).rejectTask("task-1", "Needs corrected data", variables);
    }
}
