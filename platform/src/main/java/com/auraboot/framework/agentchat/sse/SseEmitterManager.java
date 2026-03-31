package com.auraboot.framework.agentchat.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Manages per-user SSE connections for real-time IM push.
 * Supports multiple tabs/sessions per user.
 */
@Slf4j
@Component
public class SseEmitterManager {

    private static final long EMITTER_TIMEOUT_MS = 30 * 60 * 1000L; // 30 minutes

    private final ConcurrentHashMap<Long, Set<SseEmitter>> userEmitters = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public SseEmitterManager(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Create a new SSE emitter for the given user.
     * Sends a "connected" heartbeat on creation.
     */
    public SseEmitter createEmitter(Long userId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);

        Set<SseEmitter> emitters = userEmitters.computeIfAbsent(userId, k -> new CopyOnWriteArraySet<>());
        emitters.add(emitter);

        Runnable cleanup = () -> {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                userEmitters.remove(userId);
            }
        };

        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> {
            log.debug("SSE error for user {}: {}", userId, e.getMessage());
            cleanup.run();
        });

        // Send initial heartbeat
        // CATCH: non-transactional, SSE IO failure is expected when client disconnects
        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data(Map.of("status", "ok")));
        } catch (IOException e) {
            log.debug("Failed to send SSE heartbeat to user {}: {}", userId, e.getMessage());
            cleanup.run();
        }

        log.debug("SSE emitter created for user {}, total connections: {}", userId, emitters.size());
        return emitter;
    }

    /**
     * Send an event to all connections of a specific user.
     */
    public void sendToUser(Long userId, SseEventType eventType, Object data) {
        Set<SseEmitter> emitters = userEmitters.get(userId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        String jsonData;
        // CATCH: non-transactional, serialization failure should not crash the caller
        try {
            jsonData = objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            log.warn("Failed to serialize SSE data for event {}: {}", eventType.value(), e.getMessage());
            return;
        }

        for (SseEmitter emitter : emitters) {
            // CATCH: non-transactional, SSE IO failure is expected when client disconnects
            try {
                emitter.send(SseEmitter.event()
                        .name(eventType.value())
                        .data(jsonData));
            } catch (IOException e) {
                log.debug("Failed to send SSE event {} to user {}, removing emitter", eventType.value(), userId);
                emitters.remove(emitter);
            }
        }
    }

    /**
     * Broadcast an event to multiple users.
     */
    public void sendToUsers(Set<Long> userIds, SseEventType eventType, Object data) {
        for (Long userId : userIds) {
            sendToUser(userId, eventType, data);
        }
    }

    /**
     * Check if a user has at least one active SSE connection.
     */
    public boolean isUserOnline(Long userId) {
        Set<SseEmitter> emitters = userEmitters.get(userId);
        return emitters != null && !emitters.isEmpty();
    }
}
