package com.auraboot.framework.scheduler.xxl;

import com.auraboot.framework.scheduler.entity.ScheduledTask;
import com.auraboot.framework.scheduler.mapper.ScheduledTaskMapper;
import com.auraboot.framework.scheduler.service.TaskExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuraBootScheduledTaskJobHandlerTest {

    @Mock
    private ScheduledTaskMapper taskMapper;
    @Mock
    private TaskExecutor taskExecutor;

    private AuraBootScheduledTaskJobHandler handler;

    @BeforeEach
    void setUp() {
        handler = new AuraBootScheduledTaskJobHandler(new ObjectMapper(), taskMapper, taskExecutor);
    }

    @Test
    void execute_missingTaskPid_rejectsPayload() {
        assertThatThrownBy(() -> handler.execute("{\"tenantId\":1}"))
                .hasMessageContaining("taskPid");

        verifyNoInteractions(taskMapper, taskExecutor);
    }

    @Test
    void execute_unknownTaskPid_rejectsPayload() {
        when(taskMapper.findByPid("missing-task")).thenReturn(null);

        assertThatThrownBy(() -> handler.execute("{\"taskPid\":\"missing-task\",\"tenantId\":1}"))
                .hasMessageContaining("missing-task");

        verify(taskMapper).findByPid("missing-task");
        verifyNoInteractions(taskExecutor);
    }

    @Test
    void execute_validPayload_delegatesToTaskExecutor() {
        ScheduledTask task = new ScheduledTask();
        task.setPid("task-1");
        task.setTenantId(1L);
        when(taskMapper.findByPid("task-1")).thenReturn(task);

        handler.execute("""
                {
                  "taskPid": "task-1",
                  "tenantId": 1,
                  "traceId": "trace-1",
                  "triggerType": "scheduled",
                  "params": {"source":"test"},
                  "shardIndex": 0,
                  "shardTotal": 1
                }
                """);

        verify(taskMapper).findByPid("task-1");
        verify(taskExecutor).execute(task);
    }

    @Test
    void xxlEntryPoint_isRegisteredWithStableJobHandlerName() throws Exception {
        Method method = AuraBootScheduledTaskJobHandler.class.getDeclaredMethod("executeFromXxlJob");
        XxlJob annotation = method.getAnnotation(XxlJob.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).isEqualTo("aurabootScheduledTaskJob");
    }
}
