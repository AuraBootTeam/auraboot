package com.auraboot.framework.bpm.service;

import com.auraboot.framework.bpm.audit.BpmAuditService;
import com.auraboot.smart.framework.engine.SmartEngine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class TaskServiceTest {

    @Test
    void rejectTaskWithoutCommentFailsBeforeEngineAccess() {
        SmartEngine smartEngine = mock(SmartEngine.class);
        TaskService service = new TaskService(
                smartEngine,
                mock(BpmAuditService.class),
                mock(BpmTaskActionsResolver.class)
        );

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.rejectTask("task-1", " ", null)
        );

        assertEquals("Rejection comment is required", error.getMessage());
        verifyNoInteractions(smartEngine);
    }
}
