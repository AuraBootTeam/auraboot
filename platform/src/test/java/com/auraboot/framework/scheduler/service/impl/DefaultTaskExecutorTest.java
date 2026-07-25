package com.auraboot.framework.scheduler.service.impl;

import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.framework.scheduler.entity.ScheduledTask;
import com.auraboot.framework.scheduler.entity.ScheduledTaskLog;
import com.auraboot.framework.scheduler.mapper.ScheduledTaskLogMapper;
import com.auraboot.framework.scheduler.mapper.ScheduledTaskMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guards the handler-bean allowlist DefaultTaskExecutor enforces before it
 * invokes a scheduled task's handler.
 */
@ExtendWith(MockitoExtension.class)
class DefaultTaskExecutorTest {

    @Mock
    private ApplicationContext applicationContext;
    @Mock
    private ScheduledTaskLogMapper logMapper;
    @Mock
    private ScheduledTaskMapper taskMapper;

    /** Handler bean whose method records that it was invoked. */
    public static class Recorder {
        boolean invoked = false;
        public void run() { invoked = true; }
    }

    private DefaultTaskExecutor executor() {
        return new DefaultTaskExecutor(applicationContext, logMapper, taskMapper);
    }

    private ScheduledTask task(String handlerBean, String handlerMethod) {
        ScheduledTask t = new ScheduledTask();
        t.setPid("sys-test");
        t.setTenantId(null);
        t.setHandlerBean(handlerBean);
        t.setHandlerMethod(handlerMethod);
        t.setMaxRetries(0);
        t.setTimeoutMs(5000L);
        return t;
    }

    private ScheduledTaskLog runAndCaptureLog(ScheduledTask t) {
        executor().execute(t);
        ArgumentCaptor<ScheduledTaskLog> captor = ArgumentCaptor.forClass(ScheduledTaskLog.class);
        verify(logMapper, atLeastOnce()).updateById(captor.capture());
        return captor.getValue();
    }

    @Test
    void systemTaskHandlerBeansAreAllOnTheAllowlist() {
        // Every handler bean SystemTaskInitializer registers must pass the allowlist
        // and reach getBean. Before the fix, the *Task/*Impl/*Service beans below hit
        // none of the prefixes and every trigger failed silently — outbox dispatch,
        // idempotency cleanup, field-usage refresh, inbox cleanup, RAG retry and RAG
        // reconcile all never ran.
        for (String bean : new String[]{
                "invariantAlarmWorker", "decisionAlarmWorker",
                "outboxWorkerImpl", "idempotencyServiceImpl", "fieldUsageServiceImpl",
                "inboxCleanupTask", "embeddingRetryService", "documentReconcileService"
        }) {
            Recorder recorder = new Recorder();
            lenient().when(applicationContext.getBean(bean)).thenReturn(recorder);

            ScheduledTaskLog log = runAndCaptureLog(task(bean, "run"));

            assertThat(log.getStatus())
                    .as("system task bean %s should pass the allowlist and be invoked", bean)
                    .isEqualTo(StatusConstants.SUCCESS);
            assertThat(recorder.invoked)
                    .as("system task bean %s should actually be invoked", bean)
                    .isTrue();
        }
    }

    @Test
    void handlerBeanNotOnTheAllowlistIsRejectedBeforeLookup() {
        // A bean outside the allowlist (e.g. a tenant-supplied name) is rejected
        // before getBean, so arbitrary bean invocation stays blocked.
        ScheduledTaskLog log = runAndCaptureLog(task("someArbitraryBean", "run"));

        assertThat(log.getStatus()).isEqualTo(StatusConstants.FAILED);
        assertThat(log.getErrorMessage()).contains("not in allowlist");
        verify(applicationContext, org.mockito.Mockito.never()).getBean(any(String.class));
    }
}
