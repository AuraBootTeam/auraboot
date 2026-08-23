package com.auraboot.framework.integration;

import com.auraboot.framework.meta.entity.OutboxEvent;
import com.auraboot.framework.meta.mapper.OutboxEventMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** Fenced state transitions for delivery, failure, DLQ, replay, and reconciliation. */
@Service
@RequiredArgsConstructor
public class ReliableIntegrationStateService {

    private final OutboxEventMapper outboxEventMapper;
    private final ReliableIntegrationMetrics metrics;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markDelivered(OutboxEvent event, String leaseToken) {
        boolean changed = outboxEventMapper.markDeliveredFenced(event.getId(), leaseToken) == 1;
        if (changed) {
            metrics.record("delivered");
        }
        return changed;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordFailure(OutboxEvent event, String leaseToken, Instant nextRetryAt, String error) {
        boolean changed = outboxEventMapper.recordFailureFenced(
                event.getId(), leaseToken, nextRetryAt, error) == 1;
        if (!changed) {
            metrics.record("lease_fence_lost");
            return false;
        }
        boolean terminal = event.getRetryCount() + 1 >= event.getMaxRetries();
        if (terminal) {
            outboxEventMapper.upsertDeadLetter(event.getId());
            metrics.record("dead_lettered");
        } else {
            metrics.record("retry_scheduled");
        }
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean replay(long outboxId, String replayedBy) {
        boolean changed = outboxEventMapper.replayDeadLetter(outboxId, replayedBy) == 1;
        if (changed) {
            metrics.record("replayed");
        }
        return changed;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReconcileResult reconcile(Instant expiredBefore) {
        int recovered = outboxEventMapper.recoverExpiredLeases(expiredBefore);
        int deadLetters = outboxEventMapper.reconcileMissingDeadLetters();
        for (int i = 0; i < recovered; i++) {
            metrics.record("lease_recovered");
        }
        return new ReconcileResult(recovered, deadLetters);
    }

    public record ReconcileResult(int recoveredLeases, int createdDeadLetters) {}
}
