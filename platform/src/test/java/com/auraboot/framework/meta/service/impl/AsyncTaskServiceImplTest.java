package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.AsyncTaskSubmitRequest;
import com.auraboot.framework.meta.entity.AsyncTask;
import com.auraboot.framework.meta.mapper.AsyncTaskMapper;
import com.auraboot.framework.meta.service.AsyncTaskExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AsyncTaskServiceImpl#submitTask} execution kickoff timing.
 *
 * <p>Guards against the @Async-before-commit race: when {@code submitTask} runs
 * inside a request transaction (e.g. the command pipeline's HandlerPhase), the
 * task row is not yet committed. Kicking off the @Async executor immediately makes
 * its {@code selectById} (a separate connection) read null → "Task not found" →
 * the task stays {@code pending} forever. The kickoff must therefore be deferred
 * to after-commit when a transaction is active.</p>
 */
class AsyncTaskServiceImplTest {

    private AsyncTaskMapper asyncTaskMapper;
    private AsyncTaskServiceImpl service;
    private AsyncTaskServiceImpl selfProxy;

    @BeforeEach
    void setUp() {
        asyncTaskMapper = mock(AsyncTaskMapper.class);
        AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);
        when(executor.getTaskType()).thenReturn("command-handler");

        service = new AsyncTaskServiceImpl(asyncTaskMapper, List.of(executor));
        service.init();

        // Replace the self-proxy with a mock so we can verify kickoff invocations
        // without actually running on the @Async thread pool.
        selfProxy = mock(AsyncTaskServiceImpl.class);
        ReflectionTestUtils.setField(service, "self", selfProxy);

        // MyBatis assigns the generated id onto the entity on insert; simulate that.
        doAnswer(inv -> {
            AsyncTask t = inv.getArgument(0);
            t.setId(1L);
            return 1;
        }).when(asyncTaskMapper).insert(any(AsyncTask.class));
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private AsyncTaskSubmitRequest request() {
        AsyncTaskSubmitRequest req = new AsyncTaskSubmitRequest();
        req.setTaskType("command-handler");
        req.setTaskName("bom:import_material_library");
        return req;
    }

    @Test
    void defersExecutionUntilAfterCommitWhenTransactionActive() {
        TransactionSynchronizationManager.initSynchronization();

        service.submitTask(request(), 123L, 45L);

        // Must NOT kick off while the tx is still uncommitted.
        verify(selfProxy, never()).executeTaskAsync(anyLong(), anyLong());

        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        assertThat(syncs).hasSize(1);

        // Simulate commit → kickoff now fires.
        syncs.get(0).afterCommit();
        verify(selfProxy, times(1)).executeTaskAsync(eq(1L), eq(123L));
    }

    @Test
    void triggersExecutionImmediatelyWhenNoTransaction() {
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();

        service.submitTask(request(), 123L, 45L);

        verify(selfProxy, times(1)).executeTaskAsync(eq(1L), eq(123L));
    }
}
