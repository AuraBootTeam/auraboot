package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.AsyncTaskSubmitRequest;
import com.auraboot.framework.meta.entity.AsyncTask;
import com.auraboot.framework.meta.mapper.AsyncTaskMapper;
import com.auraboot.framework.meta.service.AsyncTaskExecutor;
import com.auraboot.framework.meta.service.AsyncTaskResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.scheduling.annotation.Scheduled;
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

    @Test
    void leaseHeartbeatUsesDedicatedScheduler() throws Exception {
        Scheduled scheduled = AsyncTaskServiceImpl.class
                .getMethod("renewExecutionLeases")
                .getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.scheduler()).isEqualTo("asyncTaskLeaseScheduler");
    }

    private AsyncTaskMapper asyncTaskMapper;
    private AsyncTaskServiceImpl service;
    private AsyncTaskServiceImpl selfProxy;
    private AsyncTaskExecutor executor;

    @BeforeEach
    void setUp() {
        asyncTaskMapper = mock(AsyncTaskMapper.class);
        when(asyncTaskMapper.claimPending(any(), any(), any())).thenReturn(1);
        when(asyncTaskMapper.finishOwned(any())).thenReturn(1);
        executor = mock(AsyncTaskExecutor.class);
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
        com.auraboot.framework.application.tenant.MetaContext.clear();
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
    void duplicate_dispatch_cannot_execute_a_task_after_another_worker_claims_it() throws Exception {
        AsyncTask task = new AsyncTask();
        task.setId(11L);
        task.setTenantId(10L);
        task.setTaskCode("dedupe");
        task.setTaskType("command-handler");
        task.setStatus(AsyncTask.STATUS_PENDING);
        when(asyncTaskMapper.selectById(11L)).thenReturn(task);
        when(asyncTaskMapper.claimPending(eq(11L), eq(10L), any())).thenReturn(0);
        service.executeTaskAsync(11L, 10L);
        verify(executor, org.mockito.Mockito.never()).execute(any(), any());
        verify(asyncTaskMapper, org.mockito.Mockito.never()).updateById(any(AsyncTask.class));
    }

    @Test
    void recovery_dispatches_only_opted_in_pending_tasks_using_persisted_tenant() {
        AsyncTask task = new AsyncTask();
        task.setId(11L);
        task.setTenantId(10L);
        when(asyncTaskMapper.findResumablePendingTasks(100)).thenReturn(List.of(task));
        service.recoverResumablePendingTasks();
        verify(selfProxy).executeTaskAsync(11L, 10L);
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

    @Test
    void deterministicFailureIsTerminalWithoutWholeTaskRetry() {
        AsyncTask task = executableTask();
        when(asyncTaskMapper.selectById(1L)).thenReturn(task);
        when(executor.execute(any(), any()))
                .thenReturn(AsyncTaskResult.nonRetryableFailure("invalid quote state"));

        service.executeTaskAsync(1L, 123L);

        assertThat(task.getStatus()).isEqualTo(AsyncTask.STATUS_FAILED);
        assertThat(task.getRetryCount()).isZero();
        assertThat(task.getErrorMessage()).isEqualTo("invalid quote state");
        verify(selfProxy, never()).executeTaskAsync(anyLong(), anyLong());
    }

    @Test
    void retryableFailureSchedulesOneWholeTaskRetry() {
        AsyncTask task = executableTask();
        when(asyncTaskMapper.selectById(1L)).thenReturn(task);
        when(executor.execute(any(), any()))
                .thenReturn(AsyncTaskResult.retryableFailure("connection refused"));

        service.executeTaskAsync(1L, 123L);

        assertThat(task.getStatus()).isEqualTo(AsyncTask.STATUS_PENDING);
        assertThat(task.getRetryCount()).isEqualTo(1);
        verify(selfProxy, times(1)).executeTaskAsync(1L, 123L);
    }

    @Test
    void staleRetryCannotRedispatchAfterLeaseLoss() {
        AsyncTask task = executableTask();
        when(asyncTaskMapper.selectById(1L)).thenReturn(task);
        when(executor.execute(any(), any())).thenReturn(AsyncTaskResult.retryableFailure("temporary"));
        when(asyncTaskMapper.finishOwned(any())).thenReturn(0);
        service.executeTaskAsync(1L, 123L);
        verify(selfProxy, never()).executeTaskAsync(anyLong(), anyLong());
        verify(asyncTaskMapper, never()).updateById(any(AsyncTask.class));
    }

    @Test
    void completionCarriesClaimTokenAndCannotUseUnfencedUpdate() {
        AsyncTask task = executableTask();
        when(asyncTaskMapper.selectById(1L)).thenReturn(task);
        when(executor.execute(any(), any())).thenReturn(AsyncTaskResult.nonRetryableFailure("failure"));
        service.executeTaskAsync(1L, 123L);
        assertThat(task.getExecutionToken()).isNotBlank();
        verify(asyncTaskMapper).claimPending(eq(1L), eq(123L), eq(task.getExecutionToken()));
        verify(asyncTaskMapper).finishOwned(task);
        verify(asyncTaskMapper, never()).updateById(any(AsyncTask.class));
    }

    @Test
    void lostLeaseInterruptsWorkerAndFinishedWorkerIsRemovedFromRenewal() throws Exception {
        AsyncTask task = executableTask();
        when(asyncTaskMapper.selectById(1L)).thenReturn(task);
        var started = new java.util.concurrent.CountDownLatch(1);
        var interrupted = new java.util.concurrent.CountDownLatch(1);
        when(executor.execute(any(), any())).thenAnswer(invocation -> {
            started.countDown();
            try {
                new java.util.concurrent.CountDownLatch(1).await();
                throw new AssertionError("worker should be interrupted");
            } catch (InterruptedException expected) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                return AsyncTaskResult.nonRetryableFailure("lease lost");
            }
        });
        Thread worker = new Thread(() -> service.executeTaskAsync(1L, 123L));
        worker.start();
        try {
            assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            service.renewExecutionLeases();
            assertThat(interrupted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            worker.join(5000);
            assertThat(worker.isAlive()).isFalse();
            service.renewExecutionLeases();
            verify(asyncTaskMapper, times(1)).renewLease(eq(1L), eq(123L), eq(task.getExecutionToken()));
        } finally {
            worker.interrupt();
            worker.join(5000);
        }
    }

    @Test void executorIdentityComesFromTheClaimedRowWithoutPropagatingRegistryBypass() {
        com.auraboot.framework.application.tenant.MetaContext.clear();
        AsyncTask task=executableTask();task.setCreatedBy(45L);
        task.setInputParams(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("tenantId",999).put("createdBy",999));
        when(asyncTaskMapper.selectById(1L)).thenAnswer(invocation->{
            assertThat(com.auraboot.framework.application.tenant.MetaContext.isTenantFilterBypassed()).isTrue();return task;
        });
        when(asyncTaskMapper.updateOwnedProgress(eq(1L),eq(123L),any(),eq(50),eq("progress"))).thenAnswer(invocation->{
            assertThat(com.auraboot.framework.application.tenant.MetaContext.isTenantFilterBypassed()).isTrue();return 1;
        });
        when(executor.execute(any(),any())).thenAnswer(invocation->{
            var claimed=AsyncTaskExecutionContext.require();
            assertThat(claimed.taskId()).isEqualTo(1L);assertThat(claimed.taskCode()).isEqualTo("TASK-1");
            assertThat(claimed.tenantId()).isEqualTo(123L);assertThat(claimed.createdBy()).isEqualTo(45L);
            assertThat(claimed.token()).isEqualTo(task.getExecutionToken());
            assertThat(com.auraboot.framework.application.tenant.MetaContext.isTenantFilterBypassed()).isFalse();
            assertThat(com.auraboot.framework.application.tenant.MetaContext.exists()).isFalse();
            ((AsyncTaskExecutor.ProgressCallback)invocation.getArgument(1)).report(50,"progress");
            assertThat(com.auraboot.framework.application.tenant.MetaContext.isTenantFilterBypassed()).isFalse();
            return AsyncTaskResult.ok(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("completed",true));
        });
        service.executeTaskAsync(1L,123L);
        assertThat(task.getStatus()).isEqualTo(AsyncTask.STATUS_COMPLETED);
        org.assertj.core.api.Assertions.assertThatThrownBy(AsyncTaskExecutionContext::require).isInstanceOf(IllegalStateException.class);
        assertThat(com.auraboot.framework.application.tenant.MetaContext.isTenantFilterBypassed()).isFalse();
    }
    @Test void dispatchCannotClaimARecordBelongingToADifferentPersistedTenant() {
        AsyncTask task=executableTask();when(asyncTaskMapper.selectById(1L)).thenReturn(task);
        service.executeTaskAsync(1L,999L);
        verify(asyncTaskMapper,never()).claimPending(any(),any(),any());
        verify(executor,never()).execute(any(),any());
    }

    @Test void saturatedCallerDispatchLeavesDurableWorkPendingUntilTheCallerTransactionIsGone() {
        var task=executableTask();task.setInputParams(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("resumeOnRestart",true));
        when(asyncTaskMapper.selectById(1L)).thenReturn(task);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {service.executeTaskAsync(1L,123L);} finally {TransactionSynchronizationManager.setActualTransactionActive(false);}
        verify(asyncTaskMapper,never()).claimPending(any(),any(),any());
        verify(executor,never()).execute(any(),any());
        assertThat(task.getStatus()).isEqualTo(AsyncTask.STATUS_PENDING);
    }

    private AsyncTask executableTask() {
        AsyncTask task = new AsyncTask();
        task.setId(1L);
        task.setTenantId(123L);
        task.setTaskCode("TASK-1");
        task.setTaskType("command-handler");
        task.setStatus(AsyncTask.STATUS_PENDING);
        task.setRetryCount(0);
        task.setMaxRetries(3);
        return task;
    }
    @Test void publicSubmissionCannotDispatchHostCreatedCommandTasks() {
        com.auraboot.framework.application.tenant.MetaContext.setContext(1L,2L,"user","User");
        org.assertj.core.api.Assertions.assertThatThrownBy(()->service.submitPublicTask(request(),1L,2L))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verify(asyncTaskMapper,never()).insert(any(AsyncTask.class));
    }
    @Test void optedInPublicExecutorKeepsTheAuthenticatedPersistedOwner() {
        com.auraboot.framework.application.tenant.MetaContext.setContext(1L,2L,"user","User");
        when(executor.supportsPublicSubmission()).thenReturn(true);
        service.submitPublicTask(request(),1L,2L);
        var captor=org.mockito.ArgumentCaptor.forClass(AsyncTask.class);
        verify(asyncTaskMapper).insert(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo(1L);
        assertThat(captor.getValue().getCreatedBy()).isEqualTo(2L);
        verify(selfProxy).executeTaskAsync(1L,1L);
    }
    @Test void executorOptInCannotChangePublicSubmissionIdentity() {
        com.auraboot.framework.application.tenant.MetaContext.setContext(1L,2L,"user","User");
        when(executor.supportsPublicSubmission()).thenReturn(true);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->service.submitPublicTask(request(),99L,2L))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verify(asyncTaskMapper,never()).insert(any(AsyncTask.class));
    }
    @Test void publicTaskLookupRequiresBothTenantAndOwnerAndDoesNotLeakMissingCodes() {
        AsyncTask task=new AsyncTask();task.setTaskCode("owned");task.setTenantId(1L);task.setCreatedBy(2L);
        when(asyncTaskMapper.findByTaskCode("owned")).thenReturn(task);
        com.auraboot.framework.application.tenant.MetaContext.setContext(1L,2L,"user","User");
        assertThat(service.getOwnedTask("owned").getTaskCode()).isEqualTo("owned");
        com.auraboot.framework.application.tenant.MetaContext.setContext(99L,2L,"user","User");
        org.assertj.core.api.Assertions.assertThatThrownBy(()->service.getOwnedTask("owned"))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        com.auraboot.framework.application.tenant.MetaContext.setContext(1L,3L,"other","Other");
        org.assertj.core.api.Assertions.assertThatThrownBy(()->service.requireCurrentOwner("owned"))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->service.requireCurrentOwner("missing"))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

}
