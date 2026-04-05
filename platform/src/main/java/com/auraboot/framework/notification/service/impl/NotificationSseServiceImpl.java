package com.auraboot.framework.notification.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.notification.service.NotificationSseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Implementation of NotificationSseService.
 * Manages SSE connections for real-time notification updates.
 *
 * @since 5.2.0
 */
@Slf4j
@Service
public class NotificationSseServiceImpl implements NotificationSseService {

    /**
     * Map of user ID to list of active SSE emitters.
     * Supports multiple connections per user (e.g., multiple browser tabs).
     */
    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> userEmitters = new ConcurrentHashMap<>();

    /**
     * SSE connection timeout: 5 minutes. Aligned with BFF connectionTimeout.
     * Clients auto-reconnect via EventSource. Short timeout prevents zombie connections
     * when BFF/client disconnects without clean close.
     */
    private static final long SSE_TIMEOUT = 5 * 60 * 1000L;

    /**
     * Maximum SSE connections per user. When exceeded, the oldest connection is completed
     * to make room for the new one. Prevents resource exhaustion from stale tabs.
     */
    private static final int MAX_CONNECTIONS_PER_USER = 3;

    @Override
    public SseEmitter subscribe(Long userId) {
        // Defensive check: ensure caller can only subscribe to their own notifications
        try {
            Long currentUserId = MetaContext.getCurrentUserId();
            if (currentUserId != null && !currentUserId.equals(userId)) {
                throw new SecurityException("Cannot subscribe to another user's notifications");
            }
        } catch (SecurityException e) {
            throw e;
        } catch (Exception ignored) {
            // No MetaContext available (e.g., internal calls) — allow
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);

        CopyOnWriteArrayList<SseEmitter> emitters =
                userEmitters.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());

        // Enforce per-user connection limit: complete oldest connections first
        while (emitters.size() >= MAX_CONNECTIONS_PER_USER) {
            SseEmitter oldest = emitters.remove(0);
            log.info("SSE connection limit reached for user {}, completing oldest connection", userId);
            oldest.complete();
        }

        emitters.add(emitter);
        log.debug("SSE connection established for user {}, total connections: {}",
                userId, getActiveConnectionCount(userId));

        // NOTE: lifecycle callbacks (onCompletion/onTimeout/onError) are NOT registered here.
        // The controller sets unified callbacks that clean up both NotificationSseService
        // and DataSyncSseRegistry, avoiding the dual-callback overwrite problem.

        // Send initial connection confirmation
        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data(Map.of("status", "connected")));
        } catch (IOException e) {
            log.warn("Failed to send initial SSE event to user {}: {}", userId, e.getMessage());
            removeEmitter(userId, emitter);
        }

        return emitter;
    }

    @Override
    public void pushUnreadCount(Long userId, int count) {
        CopyOnWriteArrayList<SseEmitter> emitters = userEmitters.get(userId);
        if (emitters == null || emitters.isEmpty()) {
            log.debug("No active SSE connections for user {}, skipping push", userId);
            return;
        }

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("unread-count")
                        .data(Map.of("count", count)));
                log.debug("Pushed unread count {} to user {}", count, userId);
            } catch (IOException e) {
                log.debug("Failed to push to user {}, removing connection: {}", userId, e.getMessage());
                removeEmitter(userId, emitter);
            }
        }
    }

    @Override
    public int getActiveConnectionCount(Long userId) {
        CopyOnWriteArrayList<SseEmitter> emitters = userEmitters.get(userId);
        return emitters != null ? emitters.size() : 0;
    }

    @Override
    public int getTotalActiveConnections() {
        return userEmitters.values().stream()
                .mapToInt(CopyOnWriteArrayList::size)
                .sum();
    }

    /**
     * Send heartbeat to all connections every 30 seconds to prevent
     * proxies/load balancers from closing idle connections.
     */
    @Scheduled(fixedRate = 30000)
    public void sendHeartbeat() {
        if (userEmitters.isEmpty()) {
            return;
        }

        log.debug("Sending heartbeat to {} users", userEmitters.size());
        for (Map.Entry<Long, CopyOnWriteArrayList<SseEmitter>> entry : userEmitters.entrySet()) {
            Long userId = entry.getKey();
            for (SseEmitter emitter : entry.getValue()) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("heartbeat")
                            .data(Map.of("timestamp", System.currentTimeMillis())));
                } catch (IOException e) {
                    log.debug("Heartbeat failed for user {}, removing connection", userId);
                    removeEmitter(userId, emitter);
                }
            }
        }
    }

    /**
     * Remove an emitter from the user's connection list.
     * Called by the controller's unified lifecycle callbacks.
     */
    public void removeEmitter(Long userId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitters = userEmitters.get(userId);
        if (emitters != null) {
            emitters.remove(emitter);
            log.debug("SSE connection removed for user {}, remaining connections: {}",
                    userId, emitters.size());

            // Clean up empty lists
            if (emitters.isEmpty()) {
                userEmitters.remove(userId);
            }
        }
    }
}
