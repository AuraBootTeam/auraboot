package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.event.AgentEvent;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE streaming service for agent events.
 * Broadcasts agent run status changes to all connected clients within a tenant.
 */
public interface AgentSseService {

    /**
     * Subscribe to agent events for the given tenant.
     *
     * @param tenantId the tenant to subscribe to
     * @return SSE emitter for the connection
     */
    SseEmitter subscribe(Long tenantId);

    /**
     * Broadcast an event to all connections for a tenant.
     *
     * @param tenantId  target tenant
     * @param eventType SSE event name
     * @param data      event payload
     */
    void broadcast(Long tenantId, String eventType, Object data);

    /**
     * Get the number of active SSE connections for a tenant.
     *
     * @param tenantId the tenant
     * @return active connection count
     */
    int getActiveConnectionCount(Long tenantId);

    /**
     * Send heartbeat to all active SSE connections to keep them alive.
     */
    void sendHeartbeat();

    /**
     * Handle agent events for SSE broadcasting.
     */
    void onAgentEvent(AgentEvent event);
}
