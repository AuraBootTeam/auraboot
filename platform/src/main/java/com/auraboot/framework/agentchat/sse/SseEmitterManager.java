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
 * Manages per-user SSE connections for real-time IM push. Supports multiple
 * tabs / sessions per user.
 *
 * <h2>Phase D.4 transport architecture (2026-04-30)</h2>
 *
 * <p>This is one of TWO independent push transports the IM stack uses; they
 * coexist deliberately and are NOT redundant:
 *
 * <table>
 *   <caption>Two push channels</caption>
 *   <tr><th>Channel</th><th>Endpoint</th><th>Used by</th><th>Frame</th></tr>
 *   <tr>
 *     <td>{@code SseEmitterManager} (this class)</td>
 *     <td>{@code GET /api/im/stream} (HTTP SSE)</td>
 *     <td>Enterprise {@code ent-im-chat} plugin (live consumer at
 *         {@code auraboot-enterprise/web-admin-ext/plugins/ent-im-chat/overlay/app/chat/services/imSseClient.ts}).
 *         {@code AgentReplyTask} streams {@code TYPING / STREAM_CHUNK / STREAM_END}
 *         events through here.</td>
 *     <td>{@link SseEventType} text events serialized as JSON-string {@code data}.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code ImMessageBroadcaster} + {@code ImWebSocketHandler}</td>
 *     <td>{@code WS /api/im/ws}</td>
 *     <td>OSS web-admin IM panel + chokepoint sink path. {@code ImAiService}
 *         (Phase D.2) pushes through {@code BroadcastResponseSink} which wraps
 *         this transport.</td>
 *     <td>{@link com.auraboot.framework.im.dto.WsFrame} typed enum
 *         ({@code TYPING_INDICATOR / MESSAGE / ERROR / SYNC_RESULT / ...}).</td>
 *   </tr>
 * </table>
 *
 * <p><b>Why both:</b> the original Phase D.4 design (2026-04-30 v2) proposed
 * extracting a {@code WsResponseSink} and migrating {@code AgentReplyTask}'s
 * SSE-based TYPING / STREAM_CHUNK / STREAM_END to WebSocket frames so that
 * one transport could serve every push need. Implementation discovery:
 * {@code ent-im-chat}'s active SSE subscription (fixed connection URL
 * {@code /api/im/stream}) makes a unilateral OSS-side migration a breaking
 * change to enterprise. The decision recorded here: <b>SSE remains the
 * canonical transport for {@code AgentReplyTask}-style streaming until
 * enterprise frontend is migrated</b>; the chokepoint flow ({@code ImAiService}
 * etc.) uses {@code BroadcastResponseSink} → WS for consistency with the
 * other chokepoint adapters but the two channels coexist.
 *
 * <p>Do not delete this class without a coordinated frontend migration.
 *
 * @see com.auraboot.framework.conversation.BroadcastResponseSink
 *      The chokepoint sink that wraps {@code ImMessageBroadcaster} (the
 *      sister WS transport). Both SHOULD eventually consolidate; D.4 chose
 *      to defer rather than break enterprise.
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
