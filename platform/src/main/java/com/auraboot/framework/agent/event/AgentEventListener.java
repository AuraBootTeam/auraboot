package com.auraboot.framework.agent.event;

import com.auraboot.framework.agent.service.AgentEventDispatchService;
import com.auraboot.framework.event.AuraEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Listens to all {@link AuraEvent} business events and dispatches matching agents
 * that have event_triggers configured.
 *
 * <p>The listener runs asynchronously so event processing never blocks the
 * original business transaction.
 *
 * <p>Cross-tenant safety: tenantId is always extracted from the event itself,
 * never from MetaContext (which may be unavailable in async threads).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentEventListener {

    private final AgentEventDispatchService eventDispatchService;

    /**
     * Handle any {@link AuraEvent} subclass and attempt event-driven agent dispatch.
     * Skips events without a tenantId (system-level events have no tenant scope).
     *
     * <p>Runs asynchronously to avoid blocking the publishing thread.
     */
    @Async
    @EventListener
    public void onAuraEvent(AuraEvent event) {
        // Skip agent events to avoid self-triggering loops
        if (event instanceof AgentEvent) {
            return;
        }

        Long tenantId = event.getTenantId();
        if (tenantId == null) {
            log.trace("Skipping event {} — no tenantId (system event)", event.getEventType());
            return;
        }

        String eventType = event.getEventType();
        String modelCode = event.getModelCode();
        Map<String, Object> payload = event.getPayload();

        try {
            List<String> matchedAgents = eventDispatchService.findMatchingAgents(
                    tenantId, eventType, modelCode, payload);

            if (matchedAgents.isEmpty()) {
                log.trace("No agent triggers matched for event={}, modelCode={}, tenant={}",
                        eventType, modelCode, tenantId);
                return;
            }

            List<String> taskPids = eventDispatchService.dispatchMatchedAgents(
                    tenantId, matchedAgents, eventType, payload);

            log.info("Event-driven dispatch complete: event={}, modelCode={}, tenant={}, " +
                     "agents={}, tasks={}",
                    eventType, modelCode, tenantId, matchedAgents, taskPids);

        } catch (Exception e) {
            // Never let event dispatch errors propagate — would break the original business flow
            log.error("Event-driven dispatch failed for event={}, tenant={}: {}",
                    eventType, tenantId, e.getMessage(), e);
        }
    }
}
