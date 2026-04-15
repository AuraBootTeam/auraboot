package com.auraboot.framework.im.pubsub;

import com.auraboot.framework.im.dto.WsFrame;
import com.auraboot.framework.im.websocket.ImSessionRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.List;

/**
 * Single-node IM broadcaster. Directly pushes to locally-connected WebSocket sessions
 * without Redis. Default when {@code auraboot.im.broadcaster} is unset or set to
 * {@code local}.
 *
 * <p>Suitable for OSS / single-node deployments where no Redis server is required.
 * For multi-node deployments, configure {@code auraboot.im.broadcaster=redis} to use
 * {@link RedisBroadcaster} instead.
 *
 * @since 6.2.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "auraboot.im", name = "broadcaster",
        havingValue = "local", matchIfMissing = true)
public class LocalBroadcaster implements ImMessageBroadcaster {

    private final ImSessionRegistry sessionRegistry;
    private final ObjectMapper objectMapper;

    @Override
    public void publish(List<Long> targetUserIds, WsFrame frame) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(frame);
        } catch (JsonProcessingException e) {
            log.warn("LocalBroadcaster failed to serialize frame type={}: {}", frame.getType(), e.getMessage());
            return;
        }
        TextMessage message = new TextMessage(payload);
        for (Long userId : targetUserIds) {
            for (WebSocketSession session : sessionRegistry.getSessions(userId)) {
                if (session.isOpen()) {
                    try {
                        session.sendMessage(message);
                    } catch (IOException e) {
                        log.warn("LocalBroadcaster failed to push to session for user={}: {}", userId, e.getMessage());
                    }
                }
            }
        }
    }
}
