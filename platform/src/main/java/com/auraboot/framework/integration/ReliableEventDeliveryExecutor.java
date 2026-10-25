package com.auraboot.framework.integration;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.mapper.OutboxEventMapper;
import com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope;
import com.auraboot.framework.plugin.extension.integration.ReliableEventConsumerExtension;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.auraboot.framework.plugin.pf4j.AuraPluginManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Executes one consumer effect and its receipt in the same independent transaction. */
@Component
public class ReliableEventDeliveryExecutor {

    private final OutboxEventMapper outboxEventMapper;
    private final AuraPluginManager pluginManager;
    private final InventoryOwnerModelAuthority modelAuthority;
    private final InventoryReceiptEnqueueProvenance provenance;
    private final ReliableIntegrationMetrics metrics;
    private final TransactionTemplate transaction;
    private final TransactionTemplate serializableTransaction;

    public ReliableEventDeliveryExecutor(OutboxEventMapper outboxEventMapper,
                                         ReliableIntegrationMetrics metrics,
                                         PlatformTransactionManager transactionManager) {
        this(outboxEventMapper, metrics, transactionManager, null, null);
    }

    public ReliableEventDeliveryExecutor(OutboxEventMapper outboxEventMapper,
                                         ReliableIntegrationMetrics metrics,
                                         PlatformTransactionManager transactionManager,
                                         AuraPluginManager pluginManager) {
        this(outboxEventMapper, metrics, transactionManager, pluginManager, null);
    }

    public ReliableEventDeliveryExecutor(OutboxEventMapper outboxEventMapper,
                                         ReliableIntegrationMetrics metrics,
                                         PlatformTransactionManager transactionManager,
                                         AuraPluginManager pluginManager,
                                         InventoryOwnerModelAuthority modelAuthority) {
        this(outboxEventMapper, metrics, transactionManager, pluginManager, modelAuthority, null);
    }

    @Autowired
    public ReliableEventDeliveryExecutor(OutboxEventMapper outboxEventMapper, ReliableIntegrationMetrics metrics,
            PlatformTransactionManager transactionManager, AuraPluginManager pluginManager,
            InventoryOwnerModelAuthority modelAuthority, InventoryReceiptEnqueueProvenance provenance) {
        this.provenance = provenance;
        this.modelAuthority = modelAuthority;
        this.pluginManager = pluginManager;
        this.outboxEventMapper = outboxEventMapper;
        this.metrics = metrics;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.serializableTransaction = new TransactionTemplate(transactionManager);
        this.serializableTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.serializableTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    }

    public void deliver(IntegrationEventEnvelope envelope,
                        ReliableEventConsumerExtension consumer,
                        String leaseToken) {
        boolean hostSerializable = consumer instanceof HostSerializableEventConsumer hostConsumer
                && consumer.getClass().getClassLoader() == getClass().getClassLoader()
                && hostConsumer.requiresSerializableTransaction(envelope);
        (hostSerializable ? serializableTransaction : transaction).executeWithoutResult(status -> {
            if (!TransactionSynchronizationManager.isActualTransactionActive()
                    || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
                throw new SecurityException("Reliable delivery requires an active writable transaction before taking its lease");
            }
            if (outboxEventMapper.lockActiveDeliveryLease(envelope.tenantId(), envelope.eventId(), leaseToken) == null) {
                metrics.record("lease_fence_lost");
                throw new IllegalStateException("Reliable integration delivery lease is not active");
            }
            int claimed = outboxEventMapper.claimReceipt(
                    envelope.tenantId(), envelope.eventId(), consumer.consumerCode(), leaseToken);
            if (claimed == 0) {
                metrics.record("duplicate_suppressed");
                return;
            }
            MetaContext.runWithSnapshot(null, () -> {
                MetaContext.setContext(envelope.tenantId(), 0L,
                        "integration-runtime", "integration-runtime");
                InventoryOwnerCommandScope.consume(envelope, consumer, pluginManager, modelAuthority,
                        () -> ProcurementReceiptCommandScope.consume(envelope, consumer, pluginManager, modelAuthority,
                                provenance, leaseToken, () -> consumer.consume(envelope)));
                if (outboxEventMapper.countActiveDeliveryLease(envelope.tenantId(), envelope.eventId(), leaseToken) != 1) {
                    metrics.record("lease_fence_lost");
                    throw new IllegalStateException("Reliable integration delivery lease expired before effect commit");
                }
                int applied = outboxEventMapper.markReceiptApplied(
                        envelope.tenantId(), envelope.eventId(), consumer.consumerCode(), leaseToken);
                if (applied != 1) {
                    throw new IllegalStateException("Reliable integration receipt fence was lost");
                }
                metrics.record("receipt_applied");
            });
        });
    }
}
