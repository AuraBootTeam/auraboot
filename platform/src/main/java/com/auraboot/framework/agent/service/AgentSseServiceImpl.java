package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.event.AgentEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Implementation of AgentSseService.
 * Manages SSE connections keyed by tenantId and broadcasts agent events
 * to all connected clients within the same tenant.
 */
@Slf4j
@Service
public class AgentSseServiceImpl implements AgentSseService {

    /**
     * Map of tenant ID to list of active SSE emitters.
     * Supports multiple connections per tenant (e.g., multiple users/tabs).
     */
    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> tenantEmitters = new ConcurrentHashMap<>();

    /**
     * SSE connection timeout: 30 minutes. Clients should auto-reconnect.
     */
    private static final long SSE_TIMEOUT = 30 * 60 * 1000L;

    @Override
    public SseEmitter subscribe(Long tenantId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);

        tenantEmitters.computeIfAbsent(tenantId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        log.debug("Agent SSE connection established for tenant {}, total connections: {}",
                tenantId, getActiveConnectionCount(tenantId));

        // Register cleanup callbacks
        emitter.onCompletion(() -> removeEmitter(tenantId, emitter));
        emitter.onTimeout(() -> removeEmitter(tenantId, emitter));
        emitter.onError(e -> {
            log.debug("Agent SSE connection error for tenant {}: {}", tenantId, e.getMessage());
            removeEmitter(tenantId, emitter);
        });

        // Send initial connection confirmation
        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data(Map.of("status", "connected")));
        } catch (IOException e) {
            log.warn("Failed to send initial agent SSE event to tenant {}: {}", tenantId, e.getMessage());
            removeEmitter(tenantId, emitter);
        }

        return emitter;
    }

    @Override
    public void broadcast(Long tenantId, String eventType, Object data) {
        CopyOnWriteArrayList<SseEmitter> emitters = tenantEmitters.get(tenantId);
        if (emitters == null || emitters.isEmpty()) {
            log.debug("No active agent SSE connections for tenant {}, skipping broadcast", tenantId);
            return;
        }

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name(eventType)
                        .data(data));
            } catch (IOException e) {
                log.debug("Failed to broadcast to tenant {}, removing connection: {}", tenantId, e.getMessage());
                removeEmitter(tenantId, emitter);
            }
        }
    }

    @Override
    public int getActiveConnectionCount(Long tenantId) {
        CopyOnWriteArrayList<SseEmitter> emitters = tenantEmitters.get(tenantId);
        return emitters != null ? emitters.size() : 0;
    }

    /**
     * Listen to AgentEvent and broadcast to the event's tenant.
     * Runs async to avoid blocking the event publisher.
     */
    @Async
    @EventListener
    public void onAgentEvent(AgentEvent event) {
        Map<String, Object> eventData = Map.of(
                "type", event.getAgentEventType(),
                "agentId", event.getAgentId() != null ? event.getAgentId() : "",
                "recordId", event.getRecordId() != null ? event.getRecordId() : "",
                "payload", event.getPayload() != null ? event.getPayload() : Map.of()
        );
        broadcast(event.getTenantId(), "agent-event", eventData);
    }

    /**
     * Send heartbeat to all connections every 30 seconds to prevent
     * proxies/load balancers from closing idle connections.
     */
    @Scheduled(fixedRate = 30000)
    public void sendHeartbeat() {
        if (tenantEmitters.isEmpty()) {
            return;
        }

        log.debug("Sending agent SSE heartbeat to {} tenants", tenantEmitters.size());
        for (Map.Entry<Long, CopyOnWriteArrayList<SseEmitter>> entry : tenantEmitters.entrySet()) {
            Long tenantId = entry.getKey();
            for (SseEmitter emitter : entry.getValue()) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("heartbeat")
                            .data(Map.of("timestamp", System.currentTimeMillis())));
                } catch (IOException e) {
                    log.debug("Agent SSE heartbeat failed for tenant {}, removing connection", tenantId);
                    removeEmitter(tenantId, emitter);
                }
            }
        }
    }

    /**
     * Remove an emitter from the tenant's connection list.
     */
    private void removeEmitter(Long tenantId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitters = tenantEmitters.get(tenantId);
        if (emitters != null) {
            emitters.remove(emitter);
            log.debug("Agent SSE connection removed for tenant {}, remaining connections: {}",
                    tenantId, emitters.size());

            // Clean up empty lists
            if (emitters.isEmpty()) {
                tenantEmitters.remove(tenantId);
            }
        }
    }
}
