package com.auraboot.framework.integration;

import com.auraboot.framework.event.context.ThreadContextContributor;
import com.auraboot.framework.event.context.ThreadContextScopes;
import com.auraboot.framework.meta.mapper.OutboxEventMapper;
import com.auraboot.framework.meta.service.impl.OutboxWorkerImpl;
import com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Spring transaction/listener seam with controlled IO; native qualification is separate. */
class ReliableOutboxDispatchListenerTest {
    private final OutboxEventMapper mapper = mock(OutboxEventMapper.class);
    private final OutboxWorkerImpl worker = mock(OutboxWorkerImpl.class);
    private final ReliableIntegrationMetrics metrics = mock(ReliableIntegrationMetrics.class);
    private final Queue<Runnable> queued = new ArrayDeque<>();
    private final ThreadLocal<String> requestContext = new ThreadLocal<>();
    private final ThreadContextContributor contributor = new ThreadContextContributor() {
        public Snapshot capture() {
            String captured = requestContext.get();
            return () -> { if (captured == null) requestContext.remove(); else requestContext.set(captured); };
        }
        public Snapshot empty() { return requestContext::remove; }
    };
    private AnnotationConfigApplicationContext context;
    private ReliableIntegrationAccessorImpl accessor;
    private TransactionTemplate transaction;
    private boolean reject;
    private final IntegrationEventEnvelope event = new IntegrationEventEnvelope("1.0", "original-event", "test.ready.v1", "test",
            "purchase-order/100", Instant.parse("2026-11-12T00:00:00Z"), 42L, "request-original", null,
            "purchase-order/100", 0, Map.of("value", "original"), Map.of());

    @Configuration
    @EnableTransactionManagement
    static class ListenerConfiguration { }

    @BeforeEach void setup() {
        context = new AnnotationConfigApplicationContext();
        context.register(ListenerConfiguration.class);
        Executor executor = task -> {
            if (reject) throw new RejectedExecutionException("test saturation");
            queued.add(task);
        };
        context.registerBean(ReliableOutboxDispatchListener.class, () -> new ReliableOutboxDispatchListener(
                worker, executor, new ThreadContextScopes(List.of(contributor)), metrics));
        context.refresh();
        accessor = new ReliableIntegrationAccessorImpl(mapper, new ObjectMapper(), metrics, null, context);
        transaction = new TransactionTemplate(new AbstractPlatformTransactionManager() {
            protected Object doGetTransaction() { return new Object(); }
            protected void doBegin(Object value, TransactionDefinition definition) { }
            protected void doCommit(DefaultTransactionStatus status) { }
            protected void doRollback(DefaultTransactionStatus status) { }
        });
    }

    @AfterEach void close() {
        context.close();
        requestContext.remove();
    }

    @Test void commitSchedulesWithoutProcessingOnTheAcceptingThread() {
        transaction.executeWithoutResult(status -> {
            accessor.enqueue(event);
            verify(mapper).insertEnvelope(any());
            assertThat(queued).isEmpty();
            verifyNoInteractions(worker);
        });
        assertThat(queued).hasSize(1);
        verifyNoInteractions(worker);
        queued.remove().run();
        verify(worker).pollAndDispatch();
        verify(metrics).record("enqueued");
        verify(metrics).record("commit_dispatch_started");
    }

    @Test void rollbackNeverSchedulesOrCountsAnEnqueuedEvent() {
        transaction.executeWithoutResult(status -> { accessor.enqueue(event); status.setRollbackOnly(); });
        assertThat(queued).isEmpty();
        verifyNoInteractions(worker, metrics);
    }

    @Test void failureDuringCommitPreparationCannotLeakAWorkSignal() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            accessor.enqueue(event);
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void beforeCommit(boolean readOnly) {
                            throw new IllegalStateException("Test commit preparation rollback");
                        }
                    });
        })).isInstanceOf(IllegalStateException.class);
        assertThat(queued).isEmpty();
        verifyNoInteractions(worker, metrics);
    }

    @Test void exactIdempotentReplayDoesNotCreateAnotherSignal() {
        when(mapper.insertEnvelopeIfAbsent(any())).thenReturn(1, 0);
        when(mapper.countMatchingEnvelope(any())).thenReturn(1);
        transaction.executeWithoutResult(status -> {
            assertThat(accessor.enqueueIdempotent(event)).isTrue();
            assertThat(accessor.enqueueIdempotent(event)).isFalse();
        });
        assertThat(queued).hasSize(1);
        queued.remove().run();
        verify(worker, times(1)).pollAndDispatch();
    }

    @Test void noBusinessTransactionCannotWriteOrNotify() {
        assertThatThrownBy(() -> accessor.enqueue(event)).isInstanceOf(IllegalStateException.class);
        context.publishEvent(new ReliableOutboxReady());
        assertThat(queued).isEmpty();
        verifyNoInteractions(worker, mapper, metrics);
    }

    @Test void saturationDoesNotTurnCommittedAcceptanceIntoFailureOrCallerRuns() {
        reject = true;
        assertThatCode(() -> transaction.executeWithoutResult(status -> accessor.enqueue(event))).doesNotThrowAnyException();
        verify(mapper).insertEnvelope(any());
        verify(metrics).record("enqueued");
        verify(metrics).record("commit_dispatch_deferred");
        verifyNoInteractions(worker);
        assertThat(queued).isEmpty();
    }

    @Test void processingClearsRequestContextAndRestoresWorkerContextAfterFailure() {
        transaction.executeWithoutResult(status -> accessor.enqueue(event));
        requestContext.set("unrelated-worker-context");
        doAnswer(call -> {
            assertThat(requestContext.get()).isNull();
            throw new IllegalStateException("test worker failure");
        }).when(worker).pollAndDispatch();
        queued.remove().run();
        assertThat(requestContext.get()).isEqualTo("unrelated-worker-context");
        verify(metrics).record("commit_dispatch_failed");
    }

    @Test void mapperFailureRollsBackWithoutSignal() {
        doThrow(new IllegalStateException("test persistence failure")).when(mapper).insertEnvelope(any());
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> accessor.enqueue(event)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(queued).isEmpty();
        verifyNoInteractions(worker, metrics);
    }

    @Test void dedicatedExecutorIsBoundedAndNeverRunsOverflowOnCaller() throws Exception {
        var executor = new ReliableOutboxDispatchConfiguration().reliableOutboxDispatchExecutor();
        executor.initialize();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var completed = new java.util.concurrent.CountDownLatch(1);
        var caller = Thread.currentThread();
        try {
            executor.execute(() -> {
                entered.countDown();
                try {
                    if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        throw new IllegalStateException("Test worker release timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            });
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            executor.execute(completed::countDown);
            assertThatThrownBy(() -> executor.execute(() -> {
                assertThat(Thread.currentThread()).isNotSameAs(caller);
            })).isInstanceOf(RejectedExecutionException.class);
            assertThat(executor.getThreadPoolExecutor().getQueue()).hasSize(1);
            release.countDown();
            assertThat(completed.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }
}
