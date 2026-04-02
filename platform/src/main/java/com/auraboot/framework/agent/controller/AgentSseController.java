package com.auraboot.framework.agent.controller;

import com.auraboot.framework.agent.service.AgentSseService;
import com.auraboot.framework.application.tenant.MetaContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * REST controller for agent event SSE streaming.
 * Provides real-time agent run status updates to connected clients.
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentSseController {

    private final AgentSseService agentSseService;

    /**
     * Establish SSE connection for agent event updates.
     * GET /api/agent/events/stream
     *
     * Events sent:
     * - "connected": Connection established confirmation
     * - "agent-event": Agent run status change (type, agentId, recordId, payload)
     * - "heartbeat": Keep-alive ping (every 30 seconds)
     */
    @GetMapping(value = "/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        Long tenantId = MetaContext.getCurrentTenantId();
        log.debug("Agent SSE stream requested for tenant {}", tenantId);
        return agentSseService.subscribe(tenantId);
    }
}
