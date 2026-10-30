package com.auraboot.framework.notification.sourcebinding;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.extension.DataPage;
import com.auraboot.framework.plugin.extension.NotificationDeliveryAccessor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.mockito.ArgumentCaptor;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Hermetic scheduler admission and composed worker contracts. No database or autonomous polling. */
class RegisteredSourcePollingAdapterTest {
    @AfterEach
    void cleanup() {
        MetaContext.clear();
        TransactionSynchronizationManager.clear();
    }

    @Test
    void defaultDisabledDoesNotDiscoverOrScheduleOrProcessAnything() {
        var p = new HostSourceBindingProperties();
        var planner = mock(RegisteredSourcePlanResolver.class);
        var worker = mock(RegisteredSourceWorker.class);
        var scheduler = factory();
        var adapter = new RegisteredSourcePollingAdapter(p, planner, worker, scheduler);
        adapter.initialize();
        adapter.start();
        assertThat(adapter.tick().status()).isEqualTo(RegisteredSourcePollingAdapter.Status.DISABLED);
        adapter.stop();
        verifyNoInteractions(planner, worker, scheduler);
    }

    @Test
    void enabledRequiresExplicitBoundsAndNonEmptyApprovedRegistry() {
        var p = configured();
        var planner = mock(RegisteredSourcePlanResolver.class);
        var worker = mock(RegisteredSourceWorker.class);
        var scheduler = factory();
        when(planner.registeredBindings()).thenReturn(List.of());
        assertThatThrownBy(() -> new RegisteredSourcePollingAdapter(p, planner, worker, scheduler).initialize())
                .isInstanceOf(IllegalArgumentException.class);
        for (Long value : new Long[] {null, 0L, -1L, 86400001L}) {
            p.getWorker().setPollIntervalMs(value);
            assertThatThrownBy(() -> new RegisteredSourcePollingAdapter(p, planner, worker, scheduler).initialize())
                    .isInstanceOf(IllegalArgumentException.class);
        }
        p.getWorker().setPollIntervalMs(1000L);
        p.getWorker().setMaxSourcesPerBinding(0);
        assertThatThrownBy(() -> new RegisteredSourcePollingAdapter(p, planner, worker, scheduler).initialize())
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(worker, scheduler);
    }

    @Test
    void frozenScopeIsExactAndBindingFailureDoesNotHideIndependentResults() {
        var f = new RegisteredSourcePlanResolverTest.Fixture();
        var b = f.binding;
        var other = new SourceBindingTypes.Binding(b.schemaVersion(), "other", 2, b.approvals(), b.source(),
                b.owner(), b.principal(), b.verification(), b.ruleId(), b.templateCode(), b.variableToSourceField());
        var planner = mock(RegisteredSourcePlanResolver.class);
        when(planner.registeredBindings()).thenReturn(List.of(b, other));
        var worker = mock(RegisteredSourceWorker.class);
        when(worker.runPage(1, "notice", 1, 2)).thenThrow(new IllegalArgumentException("private diagnostics"));
        when(worker.runPage(2, "other", 1, 2)).thenReturn(new RegisteredSourceWorker.BatchResult(2, 1, 1, 1, List.of()));
        var p = configured();
        var adapter = new RegisteredSourcePollingAdapter(p, planner, worker, factory());
        adapter.initialize();
        p.getWorker().setMaxSourcesPerBinding(999);
        when(planner.registeredBindings()).thenReturn(List.of());
        var result = adapter.tick();
        assertThat(result.status()).isEqualTo(RegisteredSourcePollingAdapter.Status.FAILED);
        assertThat(result.processedPlans()).isEqualTo(2);
        assertThat(result.failures()).containsExactly(new RegisteredSourcePollingAdapter.Failure(1, "notice", null, IllegalArgumentException.class.getName()));
        assertThatThrownBy(adapter::poll).hasMessage("NOTIFICATION_SOURCE_POLL_FAILED");
        verify(worker, never()).runPage(anyLong(), anyString(), anyInt(), org.mockito.ArgumentMatchers.eq(999));
    }

    @Test
    void boundedPagesAdvanceDespiteOldFailedOrReplayedPendingSources() {
        var f = new RegisteredSourcePlanResolverTest.Fixture();
        var worker = mock(RegisteredSourceWorker.class);
        when(worker.runPage(1, "notice", 1, 2)).thenReturn(new RegisteredSourceWorker.BatchResult(0, 3, 2, 2,
                List.of(new RegisteredSourceWorker.SourceFailure("old", IllegalArgumentException.class.getName()))));
        when(worker.runPage(1, "notice", 2, 2)).thenReturn(new RegisteredSourceWorker.BatchResult(2, 3, 1, 1, List.of()));
        var adapter = new RegisteredSourcePollingAdapter(configured(), f.resolver, worker, factory());
        adapter.initialize();
        var first = adapter.tick();
        assertThat(first.status()).isEqualTo(RegisteredSourcePollingAdapter.Status.FAILED);
        assertThat(first.matchedSources()).isEqualTo(3);
        assertThat(first.selectedSources()).isEqualTo(2);
        var second = adapter.tick();
        assertThat(second.status()).isEqualTo(RegisteredSourcePollingAdapter.Status.SUCCEEDED);
        assertThat(second.selectedSources()).isEqualTo(1);
        adapter.tick();
        verify(worker, times(2)).runPage(1, "notice", 1, 2);
        verify(worker).runPage(1, "notice", 2, 2);
    }

    @Test
    void actualWorkerIsolatesChangedSourceAndUsesSerializableTransactionsWithContextRestore() {
        var f = new RegisteredSourcePlanResolverTest.Fixture();
        var snapshot = new HashMap<>(f.records.get("source/src"));
        var second = new HashMap<>(snapshot);
        second.put("pid", "src2");
        f.records.put("source/src2", second);
        f.scanPage = new DataPage(List.of(snapshot, second), 2, 1, 2);
        // Model a source changed after selection; the second selected source remains eligible.
        f.records.get("source/src").put("state", "cancelled");
        var delivery = mock(NotificationDeliveryAccessor.class);
        when(delivery.enqueue(any())).thenReturn(new NotificationDeliveryAccessor.Result(NotificationDeliveryAccessor.Status.QUEUED, null, "key"));
        List<Integer> isolation = new ArrayList<>();
        var worker = new RegisteredSourceWorker(f.resolver, f, delivery, transactionManager(isolation));
        var adapter = new RegisteredSourcePollingAdapter(configured(), f.resolver, worker, factory());
        adapter.initialize();
        MetaContext.setContext(9L, 10L, "prior", "prior");
        var result = adapter.tick();
        assertThat(result.status()).isEqualTo(RegisteredSourcePollingAdapter.Status.FAILED);
        assertThat(result.processedPlans()).isEqualTo(2);
        assertThat(result.selectedSources()).isEqualTo(2);
        assertThat(result.failures().getFirst().sourcePid()).isEqualTo("src");
        assertThat(isolation).containsExactly(8, 8, 8);
        verify(delivery, times(2)).enqueue(any());
        assertThat(MetaContext.getCurrentTenantId()).isEqualTo(9);
        assertThat(MetaContext.getCurrentUserPid()).isEqualTo("prior");
    }

    @Test
    void malformedSelectedPageRejectsBeforeAnyEnqueue() {
        var f = new RegisteredSourcePlanResolverTest.Fixture();
        f.scanPage = new DataPage(List.of(f.records.get("source/src"), f.records.get("source/src")), 2, 1, 2);
        var delivery = mock(NotificationDeliveryAccessor.class);
        var worker = new RegisteredSourceWorker(f.resolver, f, delivery, transactionManager(new ArrayList<>()));
        var adapter = new RegisteredSourcePollingAdapter(configured(), f.resolver, worker, factory());
        adapter.initialize();
        assertThat(adapter.tick().status()).isEqualTo(RegisteredSourcePollingAdapter.Status.FAILED);
        verifyNoInteractions(delivery);
        f.scanPage = new DataPage(List.of(f.records.get("source/src")), 2, 1, 2);
        assertThat(adapter.tick().status()).isEqualTo(RegisteredSourcePollingAdapter.Status.FAILED);
        verifyNoInteractions(delivery);
    }

    @Test
    void unavailableWechatIsPartialWithoutTurningValidInAppIntoASchedulerFailure() {
        var f=new RegisteredSourcePlanResolverTest.Fixture();var worker=mock(RegisteredSourceWorker.class);
        when(worker.runPage(1,"notice",1,2)).thenReturn(new RegisteredSourceWorker.BatchResult(2,1,1,1,
                List.of(new RegisteredSourceWorker.SourceFailure("src",null,SourceFailureCode.WECHAT_DELIVERY_BLOCKED))));
        var adapter=new RegisteredSourcePollingAdapter(configured(),f.resolver,worker,factory());adapter.initialize();
        var result=adapter.tick();assertThat(result.status()).isEqualTo(RegisteredSourcePollingAdapter.Status.PARTIAL);
        assertThat(result.processedPlans()).isEqualTo(2);assertThat(result.failures()).hasSize(1);
        org.assertj.core.api.Assertions.assertThatCode(adapter::poll).doesNotThrowAnyException();
    }
    @Test
    void actualSourceFailureCannotBeDowngradedToPartialWechat() {
        var f=new RegisteredSourcePlanResolverTest.Fixture();var worker=mock(RegisteredSourceWorker.class);
        when(worker.runPage(1,"notice",1,2)).thenReturn(new RegisteredSourceWorker.BatchResult(2,2,2,1,List.of(
                new RegisteredSourceWorker.SourceFailure("src",null,SourceFailureCode.WECHAT_DELIVERY_BLOCKED),
                new RegisteredSourceWorker.SourceFailure("src2",IllegalArgumentException.class.getName(),SourceFailureCode.BINDING_INVALID))));
        var adapter=new RegisteredSourcePollingAdapter(configured(),f.resolver,worker,factory());adapter.initialize();
        assertThat(adapter.tick().status()).isEqualTo(RegisteredSourcePollingAdapter.Status.FAILED);
        assertThatThrownBy(adapter::poll).hasMessage("NOTIFICATION_SOURCE_POLL_FAILED");
    }
    @Test
    void managedDedicatedResourceStartsOnceAndShutdownCancelsOnlyOwnedFuture() {
        var f = new RegisteredSourcePlanResolverTest.Fixture();
        var worker = mock(RegisteredSourceWorker.class);
        var factory = factory();
        var scheduler = mock(ThreadPoolTaskScheduler.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        when(factory.get()).thenReturn(scheduler);
        org.mockito.Mockito.doReturn(future).when(scheduler).scheduleWithFixedDelay(any(Runnable.class), any(Instant.class), any(Duration.class));
        var p = configured();
        var adapter = new RegisteredSourcePollingAdapter(p, f.resolver, worker, factory);
        adapter.initialize();
        p.getWorker().setPollIntervalMs(10L);
        Instant before = Instant.now();
        adapter.start();
        adapter.start();
        verify(factory).get();
        verify(scheduler).setPoolSize(1);
        verify(scheduler).initialize();
        var first = ArgumentCaptor.forClass(Instant.class);
        verify(scheduler).scheduleWithFixedDelay(any(Runnable.class), first.capture(), org.mockito.ArgumentMatchers.eq(Duration.ofMillis(1000)));
        assertThat(first.getValue()).isAfterOrEqualTo(before.plusMillis(1000));
        adapter.stop();
        verify(future).cancel(false);
        verify(scheduler).shutdown();
        verifyNoInteractions(worker);
    }

    @Test
    void failedScheduleClosesResourceAndRemainsObservable() {
        var f = new RegisteredSourcePlanResolverTest.Fixture();
        var factory = factory();
        var scheduler = mock(ThreadPoolTaskScheduler.class);
        when(factory.get()).thenReturn(scheduler);
        var adapter = new RegisteredSourcePollingAdapter(configured(), f.resolver, mock(RegisteredSourceWorker.class), factory);
        adapter.initialize();
        assertThatThrownBy(adapter::start).hasMessage("NOTIFICATION_SOURCE_SCHEDULE_UNAVAILABLE");
        verify(scheduler).shutdown();
    }

    @Test
    void overlappingTickIsExplicitBusyAndDoesNotDuplicateDispatch() throws Exception {
        var f = new RegisteredSourcePlanResolverTest.Fixture();
        var worker = mock(RegisteredSourceWorker.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(worker.runPage(1, "notice", 1, 2)).thenAnswer(call -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return new RegisteredSourceWorker.BatchResult(2, 1, 1, 1, List.of());
        });
        var adapter = new RegisteredSourcePollingAdapter(configured(), f.resolver, worker, factory());
        adapter.initialize();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(adapter::tick);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(adapter.tick().status()).isEqualTo(RegisteredSourcePollingAdapter.Status.BUSY);
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).status()).isEqualTo(RegisteredSourcePollingAdapter.Status.SUCCEEDED);
            verify(worker).runPage(1, "notice", 1, 2);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @SuppressWarnings("unchecked")
    private Supplier<ThreadPoolTaskScheduler> factory() {
        return mock(Supplier.class);
    }

    private HostSourceBindingProperties configured() {
        var p = new HostSourceBindingProperties();
        p.setEnabled(true);
        p.getWorker().setEnabled(true);
        p.getWorker().setPollIntervalMs(1000L);
                p.getWorker().setMaxSourcesPerBinding(2);
        p.getWorker().setMaxBindings(2);
        return p;
    }

    private PlatformTransactionManager transactionManager(List<Integer> isolation) {
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(call -> {
            TransactionDefinition definition = call.getArgument(0);
            isolation.add(definition.getIsolationLevel());
            assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            TransactionSynchronizationManager.setActualTransactionActive(true);
            TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(definition.getIsolationLevel());
            return new SimpleTransactionStatus();
        });
        doAnswer(call -> { TransactionSynchronizationManager.clear(); return null; }).when(manager).commit(any());
        doAnswer(call -> { TransactionSynchronizationManager.clear(); return null; }).when(manager).rollback(any());
        return manager;
    }
}
