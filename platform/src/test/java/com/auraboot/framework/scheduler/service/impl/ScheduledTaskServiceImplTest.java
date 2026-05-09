package com.auraboot.framework.scheduler.service.impl;

import com.auraboot.framework.scheduler.dto.ScheduledTaskCreateRequest;
import com.auraboot.framework.scheduler.entity.ScheduledTask;
import com.auraboot.framework.scheduler.mapper.ScheduledTaskMapper;
import com.auraboot.framework.scheduler.service.SchedulerEngine;
import com.auraboot.framework.scheduler.service.TaskExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScheduledTaskServiceImplTest {

    @Mock
    private ScheduledTaskMapper taskMapper;
    @Mock
    private SchedulerEngine schedulerEngine;
    @Mock
    private TaskExecutor taskExecutor;

    @InjectMocks
    private ScheduledTaskServiceImpl service;

    private ScheduledTaskCreateRequest baseRequest() {
        ScheduledTaskCreateRequest r = new ScheduledTaskCreateRequest();
        r.setName("nightly");
        r.setTaskType("cron");
        r.setCronExpression("0 0 0 * * *");
        r.setHandlerBean("cleanupHandler");
        r.setHandlerMethod("run");
        r.setEnabled(true);
        return r;
    }

    @Test
    void create_validCron_persistsAndSchedules() {
        ScheduledTask result = service.create(baseRequest());
        assertThat(result.getName()).isEqualTo("nightly");
        assertThat(result.getPid()).isNotBlank();
        verify(taskMapper).insert(any(ScheduledTask.class));
        verify(schedulerEngine).scheduleTask(any(ScheduledTask.class));
    }

    @Test
    void create_disabledTask_doesNotSchedule() {
        ScheduledTaskCreateRequest req = baseRequest();
        req.setEnabled(false);
        service.create(req);
        verify(schedulerEngine, never()).scheduleTask(any());
    }

    @Test
    void create_invalidCron_throws() {
        ScheduledTaskCreateRequest req = baseRequest();
        req.setCronExpression("not-a-cron");
        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid cron expression");
    }

    @Test
    void getByPid_delegates() {
        ScheduledTask t = new ScheduledTask();
        when(taskMapper.findByPid("p")).thenReturn(t);
        assertThat(service.getByPid("p")).isSameAs(t);
    }

    @Test
    void listAll_delegates() {
        when(taskMapper.findAll()).thenReturn(java.util.List.of());
        assertThat(service.listAll()).isEmpty();
    }

    @Test
    void update_existing_reschedules() {
        ScheduledTask existing = new ScheduledTask();
        existing.setPid("p");
        existing.setEnabled(true);
        when(taskMapper.findByPid("p")).thenReturn(existing);

        ScheduledTask result = service.update("p", baseRequest());
        assertThat(result.getName()).isEqualTo("nightly");
        verify(schedulerEngine).unscheduleTask("p");
        verify(schedulerEngine).scheduleTask(existing);
    }

    @Test
    void update_disabled_skipsScheduling() {
        ScheduledTask existing = new ScheduledTask();
        existing.setPid("p");
        when(taskMapper.findByPid("p")).thenReturn(existing);
        ScheduledTaskCreateRequest req = baseRequest();
        req.setEnabled(false);

        service.update("p", req);
        verify(schedulerEngine).unscheduleTask("p");
        verify(schedulerEngine, never()).scheduleTask(any());
    }

    @Test
    void update_missing_throws() {
        when(taskMapper.findByPid("missing")).thenReturn(null);
        assertThatThrownBy(() -> service.update("missing", baseRequest()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void update_invalidCron_throws() {
        ScheduledTask existing = new ScheduledTask();
        when(taskMapper.findByPid("p")).thenReturn(existing);
        ScheduledTaskCreateRequest req = baseRequest();
        req.setCronExpression("???");
        assertThatThrownBy(() -> service.update("p", req))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void delete_unschedulesAndDeletes() {
        service.delete("p");
        verify(schedulerEngine).unscheduleTask("p");
        verify(taskMapper).deleteByPid("p");
    }

    @Test
    void enable_updatesAndSchedules() {
        ScheduledTask t = new ScheduledTask();
        when(taskMapper.findByPid("p")).thenReturn(t);
        service.enable("p");
        verify(taskMapper).updateEnabled("p", true);
        verify(schedulerEngine).scheduleTask(t);
    }

    @Test
    void enable_taskMissing_skipsScheduling() {
        when(taskMapper.findByPid("p")).thenReturn(null);
        service.enable("p");
        verify(schedulerEngine, never()).scheduleTask(any());
    }

    @Test
    void disable_updatesAndUnschedules() {
        service.disable("p");
        verify(taskMapper).updateEnabled("p", false);
        verify(schedulerEngine).unscheduleTask("p");
    }

    @Test
    void triggerManually_existingTask_executes() {
        ScheduledTask t = new ScheduledTask();
        t.setName("n");
        when(taskMapper.findByPid("p")).thenReturn(t);
        service.triggerManually("p");
        verify(taskExecutor).execute(t);
    }

    @Test
    void triggerManually_missingTask_throws() {
        when(taskMapper.findByPid("missing")).thenReturn(null);
        assertThatThrownBy(() -> service.triggerManually("missing"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
