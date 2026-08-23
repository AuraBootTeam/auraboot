package com.auraboot.framework.integration;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.mapper.OutboxEventMapper;
import com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope;
import com.auraboot.framework.plugin.extension.integration.ReliableEventConsumerExtension;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Executes one consumer effect and its receipt in the same independent transaction. */
@Component
public class ReliableEventDeliveryExecutor {

    private final OutboxEventMapper outboxEventMapper;
    private final ReliableIntegrationMetrics metrics;
    private final TransactionTemplate transaction;

    public ReliableEventDeliveryExecutor(OutboxEventMapper outboxEventMapper,
                                         ReliableIntegrationMetrics metrics,
                                         PlatformTransactionManager transactionManager) {
        this.outboxEventMapper = outboxEventMapper;
        this.metrics = metrics;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void deliver(IntegrationEventEnvelope envelope,
                        ReliableEventConsumerExtension consumer,
                        String leaseToken) {
        transaction.executeWithoutResult(status -> {
            int claimed = outboxEventMapper.claimReceipt(
                    envelope.tenantId(), envelope.eventId(), consumer.consumerCode(), leaseToken);
            if (claimed == 0) {
                metrics.record("duplicate_suppressed");
                return;
            }
            MetaContext.Snapshot previous = MetaContext.snapshot();
            try {
                MetaContext.setContext(envelope.tenantId(), 0L,
                        "integration-runtime", "integration-runtime");
                consumer.consume(envelope);
                int applied = outboxEventMapper.markReceiptApplied(
                        envelope.tenantId(), envelope.eventId(), consumer.consumerCode(), leaseToken);
                if (applied != 1) {
                    throw new IllegalStateException("Reliable integration receipt fence was lost");
                }
                metrics.record("receipt_applied");
            } finally {
                MetaContext.clear();
                MetaContext.restore(previous);
            }
        });
    }
}
