package com.auraboot.framework.integration;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.mapper.OutboxEventMapper;
import com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope;
import com.auraboot.framework.plugin.extension.integration.ReliableEventConsumerExtension;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Hermetic transaction selection/rollback proof; actual PG isolation is a separate native claim. */
class ReliableEventDeliveryIsolationTest {
    PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    OutboxEventMapper mapper = mock(OutboxEventMapper.class);
    ReliableIntegrationMetrics metrics = mock(ReliableIntegrationMetrics.class);
    int isolation;
    IntegrationEventEnvelope event = new IntegrationEventEnvelope("1.0", "id", "test", "test", "subject", Instant.EPOCH, 1, "id", null, "id", 0, Map.of(), Map.of());
    @BeforeEach
    void setup() {
        when(manager.getTransaction(any())).thenAnswer(a -> {
            TransactionDefinition d = a.getArgument(0);
            isolation = d.getIsolationLevel();
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
            org.springframework.transaction.support.TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
            assertThat(d.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            return new SimpleTransactionStatus();
        });
        org.mockito.Mockito.doAnswer(call -> { org.springframework.transaction.support.TransactionSynchronizationManager.clear(); return null; }).when(manager).commit(any());
        org.mockito.Mockito.doAnswer(call -> { org.springframework.transaction.support.TransactionSynchronizationManager.clear(); return null; }).when(manager).rollback(any());
        when(mapper.lockActiveDeliveryLease(anyLong(), anyString(), anyString())).thenReturn(1L);
        when(mapper.countActiveDeliveryLease(anyLong(), anyString(), anyString())).thenReturn(1);
        when(mapper.claimReceipt(anyLong(), anyString(), anyString(), anyString())).thenReturn(1);
        when(mapper.markReceiptApplied(anyLong(), anyString(), anyString(), anyString())).thenReturn(1);
    }
    @AfterEach
    void cleanup() {
        MetaContext.clear();
        org.springframework.transaction.support.TransactionSynchronizationManager.clear();
    }
    static class Ordinary implements ReliableEventConsumerExtension {
        public String consumerCode() {
            return "test";
        }
        public Set<String> subscribedEventTypes() {
            return Set.of("test");
        }
        public void consume(IntegrationEventEnvelope e) {
        }
    }
    static class SerializableConsumer extends Ordinary implements HostSerializableEventConsumer {
        @Override
        public boolean requiresSerializableTransaction(IntegrationEventEnvelope envelope) {
            return true;
        }
    }
    @Test
    void ordinaryConsumerKeepsExistingDefaultIsolation() {
        new ReliableEventDeliveryExecutor(mapper, metrics, manager).deliver(event, new Ordinary(), "lease");
        assertThat(isolation).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT);
        verify(manager).commit(any());
    }
    @Test
    void hostOptInUsesSerializableReceiptAndEffectInSameUnit() {
        new ReliableEventDeliveryExecutor(mapper, metrics, manager).deliver(event, new SerializableConsumer(), "lease");
        assertThat(isolation).isEqualTo(TransactionDefinition.ISOLATION_SERIALIZABLE);
        var order = inOrder(manager, mapper);
        order.verify(manager).getTransaction(any());
        order.verify(mapper).lockActiveDeliveryLease(1L, "id", "lease");
        order.verify(mapper).claimReceipt(1L, "id", "test", "lease");
        order.verify(mapper).countActiveDeliveryLease(1L, "id", "lease");
        order.verify(mapper).markReceiptApplied(1L, "id", "test", "lease");
        order.verify(manager).commit(any());
    }
    @Test
    void failingAuthorityRollsBackReceiptAndRestoresContext() {
        MetaContext.setContext(5L, 6L, "previous", "previous");
        var fail = new SerializableConsumer() {
            public void consume(IntegrationEventEnvelope e) {
                throw new IllegalArgumentException("invalid source");
            }
        };
        assertThatThrownBy(() -> new ReliableEventDeliveryExecutor(mapper, metrics, manager).deliver(event, fail, "lease")).isInstanceOf(IllegalArgumentException.class);
        assertThat(isolation).isEqualTo(TransactionDefinition.ISOLATION_SERIALIZABLE);
        verify(manager).rollback(any());
        verify(mapper, never()).markReceiptApplied(anyLong(), anyString(), anyString(), anyString());
        assertThat(MetaContext.getCurrentTenantId()).isEqualTo(5);
    }
    @Test
    void foreignClassloaderCannotOptIn() {
        ClassLoader foreign = new ClassLoader(getClass().getClassLoader()) {
        };
        Object consumer = Proxy.newProxyInstance(foreign, new Class<?>[] {
            ReliableEventConsumerExtension.class, HostSerializableEventConsumer.class
        },(p, m, a) -> switch (m.getName()) {
            case "consumerCode" -> "test";
            case "subscribedEventTypes" -> Set.of("test");
            default -> null;
        });
        new ReliableEventDeliveryExecutor(mapper, metrics, manager).deliver(event,(ReliableEventConsumerExtension) consumer, "lease");
        assertThat(isolation).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT);
    }

    @Test
    void inactiveLeaseRejectsBeforeReceiptOrEffect() {
        when(mapper.lockActiveDeliveryLease(1L, "id", "lease")).thenReturn(null);
        var consumer = mock(ReliableEventConsumerExtension.class);
        assertThatThrownBy(() -> new ReliableEventDeliveryExecutor(mapper, metrics, manager)
                .deliver(event, consumer, "lease")).isInstanceOf(IllegalStateException.class).hasMessageContaining("lease");
        verify(mapper, never()).claimReceipt(anyLong(), anyString(), anyString(), anyString());
        verify(consumer, never()).consume(any());
        verify(manager).rollback(any());
    }

    @Test
    void expiryAfterEffectRollsBackBeforeAppliedReceipt() {
        when(mapper.countActiveDeliveryLease(1L, "id", "lease")).thenReturn(0);
        var consumer = mock(ReliableEventConsumerExtension.class);
        when(consumer.consumerCode()).thenReturn("test");
        assertThatThrownBy(() -> new ReliableEventDeliveryExecutor(mapper, metrics, manager)
                .deliver(event, consumer, "lease")).isInstanceOf(IllegalStateException.class).hasMessageContaining("lease");
        verify(consumer).consume(event);
        verify(mapper, never()).markReceiptApplied(anyLong(), anyString(), anyString(), anyString());
        verify(manager).rollback(any());
    }

    @Test
    void activeDuplicateSkipsEffectButStillRequiresCurrentLease() {
        when(mapper.claimReceipt(1L, "id", "test", "lease")).thenReturn(0);
        var consumer = mock(ReliableEventConsumerExtension.class);
        when(consumer.consumerCode()).thenReturn("test");
        new ReliableEventDeliveryExecutor(mapper, metrics, manager).deliver(event, consumer, "lease");
        verify(mapper).lockActiveDeliveryLease(1L, "id", "lease");
        verify(consumer, never()).consume(any());
        verify(mapper, never()).markReceiptApplied(anyLong(), anyString(), anyString(), anyString());
        verify(manager).commit(any());
    }

    @Test
    void missingTransactionMarkerCannotLockOrApplyEvenWithAValidLease() {
        org.mockito.Mockito.doAnswer(call -> {
            org.springframework.transaction.support.TransactionSynchronizationManager.clear();
            return new SimpleTransactionStatus();
        }).when(manager).getTransaction(any());
        assertThatThrownBy(() -> new ReliableEventDeliveryExecutor(mapper, metrics, manager)
                .deliver(event, new Ordinary(), "lease")).isInstanceOf(SecurityException.class);
        verify(mapper, never()).lockActiveDeliveryLease(anyLong(), anyString(), anyString());
        verify(manager).rollback(any());
    }

    @Test
    void readOnlyTransactionCannotLockOrApply() {
        org.mockito.Mockito.doAnswer(call -> {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
            org.springframework.transaction.support.TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
            return new SimpleTransactionStatus();
        }).when(manager).getTransaction(any());
        assertThatThrownBy(() -> new ReliableEventDeliveryExecutor(mapper, metrics, manager)
                .deliver(event, new Ordinary(), "lease")).isInstanceOf(SecurityException.class);
        verify(mapper, never()).lockActiveDeliveryLease(anyLong(), anyString(), anyString());
        verify(manager).rollback(any());
    }
}
