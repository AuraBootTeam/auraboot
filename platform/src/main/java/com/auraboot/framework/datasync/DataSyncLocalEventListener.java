package com.auraboot.framework.datasync;

import com.auraboot.module.meta.event.CommandCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Local (in-process) fallback for data sync when Redis is not available.
 * Pushes events directly to SSE registry — suitable for single-instance deployments.
 */
@Slf4j
@Component
@ConditionalOnMissingBean(DataSyncEventListener.class)
@RequiredArgsConstructor
public class DataSyncLocalEventListener {

    private final DataSyncSseRegistry sseRegistry;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommandCompleted(CommandCompletedEvent event) {
        try {
            Long actorId = null;
            if (event.getMetadata() != null && event.getMetadata().containsKey("actorId")) {
                Object raw = event.getMetadata().get("actorId");
                if (raw instanceof Long l) actorId = l;
                else if (raw instanceof Number n) actorId = n.longValue();
                else if (raw instanceof String s) actorId = Long.parseLong(s);
            }

            DataSyncMessage message = new DataSyncMessage(
                event.getTenantId(),
                event.getModelCode(),
                event.getOperationType(),
                event.getRecordId(),
                actorId
            );

            sseRegistry.pushToSubscribers(message);

            log.debug("DataSync(local): pushed change for model={} op={}", event.getModelCode(), event.getOperationType());
        } catch (Exception e) {
            log.warn("DataSync(local): failed to push data sync for model={}: {}",
                event.getModelCode(), e.getMessage());
        }
    }
}
