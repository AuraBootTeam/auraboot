package com.auraboot.framework.datasync;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/**
 * Subscribes to Redis Pub/Sub 'data-sync' channel and pushes
 * events to locally connected SSE clients via DataSyncSseRegistry.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSyncRedisSubscriber implements MessageListener {

    private final DataSyncSseRegistry sseRegistry;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            DataSyncMessage msg = objectMapper.readValue(message.getBody(), DataSyncMessage.class);
            sseRegistry.pushToSubscribers(msg);
        } catch (Exception e) {
            log.warn("DataSync: failed to process Redis message: {}", e.getMessage());
        }
    }
}
