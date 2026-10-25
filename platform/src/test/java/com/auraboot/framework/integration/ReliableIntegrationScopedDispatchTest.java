package com.auraboot.framework.integration;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.entity.OutboxEvent;
import com.auraboot.framework.meta.mapper.OutboxEventMapper;
import com.auraboot.framework.meta.service.impl.OutboxWorkerImpl;
import com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope;
import com.auraboot.framework.plugin.extension.integration.ReliableEventConsumerExtension;
import com.auraboot.framework.webhook.service.WebhookDispatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Hermetic dispatch admission/outcome tests; PostgreSQL claims and effects have separate real-stack proof. */
class ReliableIntegrationScopedDispatchTest {
    private final OutboxEventMapper mapper = mock(OutboxEventMapper.class);
    private final ReliableEventConsumerRegistry registry = mock(ReliableEventConsumerRegistry.class);
    private final ReliableEventDeliveryExecutor executor = mock(ReliableEventDeliveryExecutor.class);
    private final ReliableIntegrationStateService state = mock(ReliableIntegrationStateService.class);
    private final ReliableIntegrationMetrics metrics = mock(ReliableIntegrationMetrics.class);
    private final ReliableIntegrationClaimService claims = mock(ReliableIntegrationClaimService.class);
    private final ReliableEventConsumerExtension consumer = mock(ReliableEventConsumerExtension.class);
    private final OutboxWorkerImpl worker = new OutboxWorkerImpl(mapper, mock(ApplicationEventPublisher.class),
            new ObjectMapper(), mock(WebhookDispatcher.class), registry, executor, state, metrics, claims);

    @BeforeEach
    void actor() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        MetaContext.setContext(31L, 7L, "actor", "actor");
        MetaContext.setMemberId(9L);
    }

    @AfterEach
    void clear() {
        MetaContext.clear();
        TransactionSynchronizationManager.clear();
    }

    @Test
    void missingOrUnverifiedFrameCannotClaimWork() {
        MetaContext.clear();
        assertThatThrownBy(() -> worker.dispatchCurrentTenant("commerce", Set.of("commerce.event"), 1))
                .isInstanceOf(SecurityException.class);
        MetaContext.setContext(31L, 7L, "actor", "actor");
        assertThatThrownBy(() -> worker.dispatchCurrentTenant("commerce", Set.of("commerce.event"), 1))
                .isInstanceOf(SecurityException.class);
        MetaContext.setMemberId(9L);
        MetaContext.setContext(31L, 7L, " ", "actor");
        assertThatThrownBy(() -> worker.dispatchCurrentTenant("commerce", Set.of("commerce.event"), 1))
                .isInstanceOf(SecurityException.class);
        verifyNoInteractions(claims);
    }

    @Test
    void callerBusinessTransactionCannotOwnAnUncommittedDeliveryClaim() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> worker.dispatchCurrentTenant("commerce", Set.of("commerce.event"), 1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("transaction");
        verifyNoInteractions(claims);
    }

    @Test
    void malformedOrUnboundedScopeNeverReachesTheClaimPort() {
        for (String source : List.of("", " ", " commerce", "commerce ", "x".repeat(129))) {
            assertThatThrownBy(() -> worker.dispatchCurrentTenant(source, Set.of("commerce.event"), 1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> worker.dispatchCurrentTenant(null, Set.of("commerce.event"), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> worker.dispatchCurrentTenant("commerce", null, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> worker.dispatchCurrentTenant("commerce", Set.of(), 1)).isInstanceOf(IllegalArgumentException.class);
        Set<String> many = IntStream.range(0, 33).mapToObj(i -> "type" + i).collect(Collectors.toSet());
        assertThatThrownBy(() -> worker.dispatchCurrentTenant("commerce", many, 1)).isInstanceOf(IllegalArgumentException.class);
        for (int limit : List.of(0, 101)) {
            assertThatThrownBy(() -> worker.dispatchCurrentTenant("commerce", Set.of("commerce.event"), limit))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(claims);
    }

    @Test
    void unknownOrNoncanonicalTypesFailBeforeAnyBatchIsClaimed() {
        when(registry.consumersFor("commerce.known")).thenReturn(List.of(consumer));
        assertThatThrownBy(() -> worker.dispatchCurrentTenant("commerce", Set.of("commerce.known", "unknown"), 1))
                .isInstanceOf(IllegalArgumentException.class);
        Set<String> nullType = new HashSet<>(); nullType.add(null);
        assertThatThrownBy(() -> worker.dispatchCurrentTenant("commerce", nullType, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> worker.dispatchCurrentTenant("commerce", Set.of(" bad"), 1)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(claims);
    }

    @Test
    void scopedDeliveryPreservesDecimalPayloadAndCountsOnlyFencedOutcomes() {
        when(registry.consumersFor(anyString())).thenReturn(List.of(consumer));
        var success = event(1L, "success"); var dead = event(2L, "dead");
        var lost = event(3L, "lost"); var failureLost = event(4L, "failure-lost");
        var exhausted = event(5L, "exhausted"); exhausted.setStatus("failed");
        when(claims.claimTenant(eq(31L), eq("commerce"), eq(List.of("commerce.a", "commerce.z")),
                eq(5), anyString(), anyString(), any())).thenReturn(List.of(success, dead, lost, failureLost, exhausted));
        doAnswer(call -> {
            IntegrationEventEnvelope envelope = call.getArgument(0);
            if (Set.of("dead", "failure-lost").contains(envelope.eventId())) throw new IllegalStateException("controlled consumer failure");
            return null;
        }).when(executor).deliver(any(), same(consumer), anyString());
        when(state.markDelivered(same(success), anyString())).thenReturn(true);
        when(state.recordFailure(same(dead), anyString(), any(), anyString())).thenReturn(true);
        var result = worker.dispatchCurrentTenant("commerce", Set.of("commerce.z", "commerce.a"), 5);
        assertThat(result.claimed()).isEqualTo(5); assertThat(result.delivered()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(2); assertThat(result.leaseLost()).isEqualTo(2);
        assertThat(result.deadLetterEventIds()).containsExactly("dead", "exhausted");
        var envelopes = ArgumentCaptor.forClass(IntegrationEventEnvelope.class);
        verify(executor, times(4)).deliver(envelopes.capture(), same(consumer), anyString());
        assertThat(envelopes.getAllValues()).allSatisfy(envelope -> {
            assertThat(envelope.tenantId()).isEqualTo(31L);
            assertThat(envelope.payload().get("amount")).isInstanceOf(BigDecimal.class)
                    .isEqualTo(new BigDecimal("123456789012345.67"));
        });
        verify(claims).claimTenant(eq(31L), eq("commerce"), eq(List.of("commerce.a", "commerce.z")),
                eq(5), anyString(), anyString(), any());
        verify(claims, never()).claimGlobal(anyInt(), anyString(), anyString(), any());
        verify(state, never()).markDelivered(same(exhausted), anyString());
        verify(state, never()).recordFailure(same(exhausted), anyString(), any(), anyString());
    }

    @Test
    void globalSchedulerUsesItsCommittedClaimPortWithoutAnActor() {
        MetaContext.clear();
        when(claims.claimGlobal(eq(50), anyString(), anyString(), any())).thenReturn(List.of());
        worker.pollAndDispatch();
        verify(claims).claimGlobal(eq(50), anyString(), anyString(), any());
        verify(claims, never()).claimTenant(anyLong(), anyString(), anyList(), anyInt(), anyString(), anyString(), any());
    }

    private OutboxEvent event(long id, String eventId) {
        var event = new OutboxEvent();
        event.setId(id); event.setTenantId(31L); event.setEventId(eventId); event.setEventType("commerce.a");
        event.setSchemaVersion("1.0"); event.setEventSource("commerce"); event.setSubject("order:" + eventId);
        event.setOccurredAt(Instant.EPOCH); event.setCorrelationId(eventId); event.setOrderingKey(eventId);
        event.setEventSequence(1L); event.setPayload("{\"amount\":123456789012345.67}");
        event.setHeaders("{}"); event.setRetryCount(0); event.setMaxRetries(1);
        return event;
    }
}
