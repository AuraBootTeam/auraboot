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
            assertThat(d.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            return new SimpleTransactionStatus();
        });
        when(mapper.claimReceipt(anyLong(), anyString(), anyString(), anyString())).thenReturn(1);
        when(mapper.markReceiptApplied(anyLong(), anyString(), anyString(), anyString())).thenReturn(1);
    }
    @AfterEach
    void cleanup() {
        MetaContext.clear();
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
        order.verify(mapper).claimReceipt(1L, "id", "test", "lease");
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
}
