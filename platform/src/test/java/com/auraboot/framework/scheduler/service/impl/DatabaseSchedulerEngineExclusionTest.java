package com.auraboot.framework.scheduler.service.impl;

import com.auraboot.framework.common.util.TenantClock;
import com.auraboot.framework.scheduler.entity.ScheduledTask;
import com.auraboot.framework.scheduler.mapper.ScheduledTaskMapper;
import com.auraboot.framework.scheduler.service.TaskExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Registration contracts only; no persistence behavior is inferred from mapper mocks. */
class DatabaseSchedulerEngineExclusionTest {
    private final ScheduledTaskMapper mapper = mock(ScheduledTaskMapper.class);
    private final TaskExecutor executor = mock(TaskExecutor.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final DatabaseSchedulerEngine engine = new DatabaseSchedulerEngine(
            mapper, executor, scheduler, mock(TenantClock.class));

    @Test
    void defaultConfigurationKeepsRecoveryScheduling() {
        engine.scheduleTask(task("sys-outbox-poll"));
        verify(scheduler).scheduleAtFixedRate(any(Runnable.class), eq(Duration.ofSeconds(5)));
        verifyNoInteractions(mapper, executor);
    }

    @Test
    void excludedPollerDoesNotRegisterOrDisableOtherTasks() {
        ReflectionTestUtils.setField(engine, "excludedTaskPids", Set.of("sys-outbox-poll"));
        engine.scheduleTask(task("sys-outbox-poll"));
        verifyNoInteractions(scheduler);
        engine.scheduleTask(task("sys-outbox-reconcile"));
        verify(scheduler).scheduleAtFixedRate(any(Runnable.class), eq(Duration.ofSeconds(5)));
        verifyNoInteractions(mapper, executor);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void reloadCannotRetainAnAlreadyRegisteredExcludedFuture() {
        ScheduledFuture future = mock(ScheduledFuture.class);
        when(scheduler.scheduleAtFixedRate(any(Runnable.class), any(Duration.class))).thenReturn(future);
        engine.scheduleTask(task("sys-outbox-poll"));
        ReflectionTestUtils.setField(engine, "excludedTaskPids", Set.of("sys-outbox-poll"));
        engine.scheduleTask(task("sys-outbox-poll"));
        verify(future).cancel(false);
        verify(scheduler, times(1)).scheduleAtFixedRate(any(Runnable.class), any(Duration.class));
        verifyNoInteractions(mapper, executor);
    }

    private ScheduledTask task(String pid) {
        ScheduledTask task = new ScheduledTask();
        task.setPid(pid);
        task.setTaskType("interval");
        task.setIntervalMs(5000L);
        return task;
    }
}
