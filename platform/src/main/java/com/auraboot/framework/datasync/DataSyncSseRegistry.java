package com.auraboot.framework.datasync;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-emitter subscription registry for real-time data sync.
 * Each SSE connection gets a unique connectionId with its own set of subscribed modelCodes.
 * Thread-safe via ConcurrentHashMap.
 */
@Slf4j
@Component
public class DataSyncSseRegistry {

    private final AtomicLong connectionIdSeq = new AtomicLong(0);

    record ConnectionInfo(
        Long connectionId,
        Long userId,
        Long tenantId,
        SseEmitter emitter,
        Set<String> modelCodes
    ) {}

    // connectionId → ConnectionInfo
    private final ConcurrentHashMap<Long, ConnectionInfo> connections = new ConcurrentHashMap<>();

    // modelCode → Set<connectionId> (reverse index)
    private final ConcurrentHashMap<String, Set<Long>> modelIndex = new ConcurrentHashMap<>();

    /**
     * Register a new SSE connection. Returns connectionId for subscription binding.
     *
     * NOTE: lifecycle callbacks (onCompletion/onTimeout/onError) are NOT registered here.
     * The controller sets unified callbacks that clean up both NotificationSseService
     * and DataSyncSseRegistry, avoiding the dual-callback overwrite problem where
     * the second registration silently replaces the first.
     */
    public Long registerEmitter(Long userId, Long tenantId, SseEmitter emitter) {
        Long connId = connectionIdSeq.incrementAndGet();
        connections.put(connId, new ConnectionInfo(connId, userId, tenantId, emitter, ConcurrentHashMap.newKeySet()));

        log.debug("DataSync: registered connection {} for user {}", connId, userId);
        return connId;
    }

    /**
     * Subscribe a connection to modelCodes. Replaces previous subscription.
     */
    public void subscribe(Long connectionId, Set<String> modelCodes) {
        ConnectionInfo conn = connections.get(connectionId);
        if (conn == null) {
            log.debug("DataSync: subscribe called for unknown connection {}", connectionId);
            return;
        }

        // Remove old subscriptions
        for (String old : conn.modelCodes()) {
            modelIndex.computeIfPresent(old, (k, set) -> {
                set.remove(connectionId);
                return set.isEmpty() ? null : set;
            });
        }
        conn.modelCodes().clear();

        // Add new subscriptions
        conn.modelCodes().addAll(modelCodes);
        for (String mc : modelCodes) {
            modelIndex.computeIfAbsent(mc, k -> ConcurrentHashMap.newKeySet()).add(connectionId);
        }

        log.debug("DataSync: connection {} subscribed to {}", connectionId, modelCodes);
    }

    /**
     * Push data change event to all connections subscribed to the given modelCode.
     * Filters by tenantId for isolation.
     */
    public void pushToSubscribers(DataSyncMessage message) {
        Set<Long> connIds = modelIndex.get(message.modelCode());
        if (connIds == null || connIds.isEmpty()) return;

        for (Long connId : connIds) {
            ConnectionInfo conn = connections.get(connId);
            if (conn == null) continue;

            // Tenant isolation
            if (!conn.tenantId().equals(message.tenantId())) continue;

            try {
                conn.emitter().send(SseEmitter.event()
                    .name("data:changed")
                    .data(Map.of(
                        "modelCode", message.modelCode(),
                        "operationType", message.operationType(),
                        "userId", message.userId() != null ? message.userId() : 0
                    )));
            } catch (IOException e) {
                log.debug("DataSync: failed to push to connection {}, removing", connId);
                removeConnection(connId);
            }
        }
    }

    /**
     * Remove a connection and clean up all subscriptions.
     * Called by the controller's unified lifecycle callbacks.
     */
    public void removeConnection(Long connId) {
        ConnectionInfo conn = connections.remove(connId);
        if (conn == null) return;
        for (String mc : conn.modelCodes()) {
            modelIndex.computeIfPresent(mc, (k, set) -> {
                set.remove(connId);
                return set.isEmpty() ? null : set;
            });
        }
        log.debug("DataSync: removed connection {}", connId);
    }

    public int getActiveConnectionCount() {
        return connections.size();
    }
}
