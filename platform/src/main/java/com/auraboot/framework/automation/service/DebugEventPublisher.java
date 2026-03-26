package com.auraboot.framework.automation.service;

import com.auraboot.framework.automation.dto.DebugEventDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages SSE connections for debug session real-time events.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Slf4j
@Service
public class DebugEventPublisher {

    /** Map of session PID to list of active SSE emitters */
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> sessionEmitters = new ConcurrentHashMap<>();

    private static final long SSE_TIMEOUT = 300_000L; // 5 minutes

    /**
     * Subscribe to debug events for a session.
     */
    public SseEmitter subscribe(String sessionId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);

        sessionEmitters.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        log.debug("Debug SSE connection established for session {}", sessionId);

        emitter.onCompletion(() -> removeEmitter(sessionId, emitter));
        emitter.onTimeout(() -> removeEmitter(sessionId, emitter));
        emitter.onError(e -> {
            log.debug("Debug SSE error for session {}: {}", sessionId, e.getMessage());
            removeEmitter(sessionId, emitter);
        });

        // Send initial connection event
        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data(Map.of("sessionId", sessionId, "status", "connected")));
        } catch (IOException e) {
            log.warn("Failed to send initial debug SSE event: {}", e.getMessage());
            removeEmitter(sessionId, emitter);
        }

        return emitter;
    }

    /**
     * Publish a debug event to all subscribers of a session.
     */
    public void publish(String sessionId, DebugEventDTO event) {
        CopyOnWriteArrayList<SseEmitter> emitters = sessionEmitters.get(sessionId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name(event.getEventType())
                        .data(event));
            } catch (IOException e) {
                log.debug("Failed to push debug event to session {}: {}", sessionId, e.getMessage());
                removeEmitter(sessionId, emitter);
            }
        }
    }

    /**
     * Clean up all emitters for a session (e.g., when session completes).
     */
    public void closeSession(String sessionId) {
        CopyOnWriteArrayList<SseEmitter> emitters = sessionEmitters.remove(sessionId);
        if (emitters != null) {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.complete();
                } catch (Exception e) {
                    // Ignore
                }
            }
        }
    }

    @Scheduled(fixedRate = 30000)
    public void sendHeartbeat() {
        if (sessionEmitters.isEmpty()) {
            return;
        }

        for (Map.Entry<String, CopyOnWriteArrayList<SseEmitter>> entry : sessionEmitters.entrySet()) {
            for (SseEmitter emitter : entry.getValue()) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("heartbeat")
                            .data(Map.of("timestamp", System.currentTimeMillis())));
                } catch (IOException e) {
                    removeEmitter(entry.getKey(), emitter);
                }
            }
        }
    }

    private void removeEmitter(String sessionId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitters = sessionEmitters.get(sessionId);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                sessionEmitters.remove(sessionId);
            }
        }
    }
}
