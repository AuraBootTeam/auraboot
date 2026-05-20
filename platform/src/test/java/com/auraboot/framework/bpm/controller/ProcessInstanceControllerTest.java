package com.auraboot.framework.bpm.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.bpm.service.ProcessEngineService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ProcessInstanceControllerTest {

    @Mock
    private ProcessEngineService processEngineService;

    @Test
    void terminatePassesUserIdBeforeReason() {
        ProcessInstanceController.TerminateProcessRequest request =
                new ProcessInstanceController.TerminateProcessRequest();
        request.setReason("E2E cleanup");
        ProcessInstanceController controller = new ProcessInstanceController(processEngineService);

        ApiResponse<Void> response = controller.terminateProcessInstance("pi-1", request, 42L);

        assertTrue(response.isSuccess());
        verify(processEngineService).terminateProcessInstance("pi-1", "42", "E2E cleanup");
    }
}
