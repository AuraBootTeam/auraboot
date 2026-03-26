package com.auraboot.framework.datasync;

import com.auraboot.module.meta.event.CommandCompletedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Listens to CommandCompletedEvent and publishes DataSyncMessage
 * to Redis Pub/Sub for cross-instance data change notification.
 * Runs AFTER_COMMIT to guarantee data is persisted before push.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSyncEventListener {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public static final String CHANNEL = "data-sync";

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

            String json = objectMapper.writeValueAsString(message);
            redisTemplate.convertAndSend(CHANNEL, json);

            log.debug("DataSync: published change for model={} op={}", event.getModelCode(), event.getOperationType());
        } catch (Exception e) {
            log.warn("DataSync: failed to publish data sync message for model={}: {}",
                event.getModelCode(), e.getMessage());
        }
    }
}
