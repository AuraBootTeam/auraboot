package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.mapper.AsyncTaskMapper;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AsyncTaskStartupRecoveryRunnerTest {

    @Test
    void marksRunningTasksFailedOnStartup() {
        AsyncTaskMapper mapper = mock(AsyncTaskMapper.class);
        when(mapper.markRunningTasksFailedOnStartup(notNull(), contains("Application restarted"))).thenReturn(2);

        new AsyncTaskStartupRecoveryRunner(mapper).run();

        verify(mapper).markRunningTasksFailedOnStartup(notNull(), contains("Application restarted"));
    }

    @Test
    void doesNotFailStartupWhenNoRunningTasksRemain() {
        AsyncTaskMapper mapper = mock(AsyncTaskMapper.class);
        when(mapper.markRunningTasksFailedOnStartup(notNull(), contains("Application restarted"))).thenReturn(0);

        new AsyncTaskStartupRecoveryRunner(mapper).run();

        verify(mapper).markRunningTasksFailedOnStartup(notNull(), contains("Application restarted"));
    }
}
