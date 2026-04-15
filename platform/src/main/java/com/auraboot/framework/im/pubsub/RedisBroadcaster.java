package com.auraboot.framework.im.pubsub;

import com.auraboot.framework.im.dto.WsFrame;
import com.auraboot.framework.im.websocket.ImSessionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Redis Pub/Sub implementation of {@link ImMessageBroadcaster} for multi-node deployments.
 *
 * When a message needs to be pushed to users, it is published to a Redis channel.
 * All application instances subscribe to this channel and push to locally-connected
 * WebSocket sessions. This ensures messages reach users regardless of which instance
 * they are connected to.
 *
 * <p>Only active when {@code auraboot.im.broadcaster=redis}. For single-node deployments
 * use the default {@link LocalBroadcaster} instead.
 *
 * @since 6.2.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "auraboot.im", name = "broadcaster", havingValue = "redis")
public class RedisBroadcaster implements MessageListener, ImMessageBroadcaster {

    public static final String CHANNEL = "im:broadcast";

    private final StringRedisTemplate redisTemplate;
    private final ImSessionRegistry sessionRegistry;
    private final ObjectMapper objectMapper;

    // Instance ID to avoid processing own messages (optional optimization)
    private final String instanceId = UUID.randomUUID().toString().substring(0, 8);

    /**
     * Publish a message to be delivered to specific users via all instances.
     */
    @Override
    public void publish(List<Long> targetUserIds, WsFrame frame) {
        try {
            BroadcastPayload payload = new BroadcastPayload();
            payload.setInstanceId(instanceId);
            payload.setTargetUserIds(targetUserIds);
            payload.setFrame(frame);

            String json = objectMapper.writeValueAsString(payload);
            redisTemplate.convertAndSend(CHANNEL, json);
        } catch (Exception e) {
            log.error("RedisBroadcaster failed to publish IM broadcast", e);
        }
    }

    /**
     * Redis subscription callback — called on every instance that subscribes.
     * Delivers the message to locally-connected WebSocket sessions only.
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String json = new String(message.getBody());
            BroadcastPayload payload = objectMapper.readValue(json, BroadcastPayload.class);

            for (Long userId : payload.getTargetUserIds()) {
                List<WebSocketSession> localSessions = sessionRegistry.getSessions(userId);
                if (localSessions.isEmpty()) continue;

                String frameJson = objectMapper.writeValueAsString(payload.getFrame());
                TextMessage textMessage = new TextMessage(frameJson);

                for (WebSocketSession session : localSessions) {
                    try {
                        if (session.isOpen()) {
                            session.sendMessage(textMessage);
                        }
                    } catch (IOException e) {
                        log.warn("RedisBroadcaster failed to push to session {}", session.getId(), e);
                    }
                }
            }
        } catch (Exception e) {
            log.error("RedisBroadcaster failed to process IM broadcast from Redis", e);
        }
    }

    @Data
    public static class BroadcastPayload {
        private String instanceId;
        private List<Long> targetUserIds;
        private WsFrame frame;
    }
}
