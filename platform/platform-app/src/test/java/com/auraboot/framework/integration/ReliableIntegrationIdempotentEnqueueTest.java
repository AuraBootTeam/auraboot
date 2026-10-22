package com.auraboot.framework.integration;

import com.auraboot.framework.meta.mapper.OutboxEventMapper;
import com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReliableIntegrationIdempotentEnqueueTest {
    private final OutboxEventMapper mapper = mock(OutboxEventMapper.class);
    private final ReliableIntegrationAccessorImpl accessor = new ReliableIntegrationAccessorImpl(
            mapper, new ObjectMapper().findAndRegisterModules(), mock(ReliableIntegrationMetrics.class));
    private final IntegrationEventEnvelope event = new IntegrationEventEnvelope("1.0", "event", "type", "source",
            "subject", Instant.parse("2026-10-13T00:00:00Z"), 42L, "correlation", null, "order", 0,
            Map.of("content", "original"), Map.of());
    @BeforeEach void transaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }
    @AfterEach void cleanup() { TransactionSynchronizationManager.clear(); }
    @Test void insertionAndExactReplayNeverUseExceptionDrivenUniqueness() {
        when(mapper.insertEnvelopeIfAbsent(any())).thenReturn(1, 0);
        when(mapper.countMatchingEnvelope(any())).thenReturn(1);
        assertThat(accessor.enqueueIdempotent(event)).isTrue();
        assertThat(accessor.enqueueIdempotent(event)).isFalse();
        verify(mapper, never()).insertEnvelope(any());
        verify(mapper).countMatchingEnvelope(any());
    }
    @Test void changedPayloadOrMissingConflictRowIsRejected() {
        when(mapper.insertEnvelopeIfAbsent(any())).thenReturn(0);
        when(mapper.countMatchingEnvelope(any())).thenReturn(0);
        assertThatThrownBy(() -> accessor.enqueueIdempotent(event))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ENVELOPE_CONFLICT");
    }
    @Test void noSourceTransactionCannotWrite() {
        TransactionSynchronizationManager.clear();
        assertThatThrownBy(() -> accessor.enqueueIdempotent(event)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(mapper);
    }
}
