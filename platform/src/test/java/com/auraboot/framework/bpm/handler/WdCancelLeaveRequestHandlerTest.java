package com.auraboot.framework.bpm.handler;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.bpm.service.WithdrawService;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.DataAccessor;
import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.model.instance.TaskInstance;
import com.auraboot.smart.framework.engine.service.query.TaskQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WdCancelLeaveRequestHandlerTest {

    @Mock
    private SmartEngine smartEngine;

    @Mock
    private TaskQueryService taskQueryService;

    @Mock
    private WithdrawService withdrawService;

    @Mock
    private DataAccessor dataAccessor;

    @Mock
    private TaskInstance pendingTask;

    @InjectMocks
    private WdCancelLeaveRequestHandler handler;

    @Test
    void execute_withCustomReason_withdrawsPendingWorkflowTask() throws Exception {
        when(smartEngine.getTaskQueryService()).thenReturn(taskQueryService);
        when(dataAccessor.getById("wd_leave_request", "record-1"))
                .thenReturn(Map.of("wd_req_process_instance", "proc-1"));
        when(taskQueryService.findAllPendingTaskList("proc-1", "1"))
                .thenReturn(List.of(pendingTask));
        when(pendingTask.getInstanceId()).thenReturn("task-1");

        Object result = handler.execute(context(Map.of("reason", "Applicant requested cancel")));

        assertEquals(Map.of("withdrawnProcessInstanceId", "proc-1"), result);
        verify(withdrawService).withdraw("task-1", "Applicant requested cancel");
    }

    @Test
    void execute_withoutReason_usesDefaultReason() throws Exception {
        when(smartEngine.getTaskQueryService()).thenReturn(taskQueryService);
        when(dataAccessor.getById("wd_leave_request", "record-1"))
                .thenReturn(Map.of("wd_req_process_instance", "proc-1"));
        when(taskQueryService.findAllPendingTaskList("proc-1", "1"))
                .thenReturn(List.of(pendingTask));
        when(pendingTask.getInstanceId()).thenReturn("task-1");

        handler.execute(context(Map.of()));

        verify(withdrawService).withdraw("task-1", "Applicant cancelled leave request");
    }

    @Test
    void execute_withoutPendingTask_throwsBusinessException() {
        when(smartEngine.getTaskQueryService()).thenReturn(taskQueryService);
        when(dataAccessor.getById("wd_leave_request", "record-1"))
                .thenReturn(Map.of("wd_req_process_instance", "proc-1"));
        when(taskQueryService.findAllPendingTaskList("proc-1", "1"))
                .thenReturn(List.of());

        BusinessException error = assertThrows(BusinessException.class, () -> handler.execute(context(Map.of())));

        assertEquals("No pending workflow task found for this leave request", error.getMessage());
        verify(withdrawService, never()).withdraw(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    private CommandHandlerExtension.CommandContext context(Map<String, Object> payload) {
        return CommandHandlerExtension.CommandContext.builder()
                .tenantId(1L)
                .commandType(WdCancelLeaveRequestHandler.COMMAND_CODE)
                .modelCode("wd_leave_request")
                .recordId("record-1")
                .payload(payload)
                .settings(Map.of(CommandHandlerExtension.DATA_ACCESSOR_KEY, dataAccessor))
                .build();
    }
}
