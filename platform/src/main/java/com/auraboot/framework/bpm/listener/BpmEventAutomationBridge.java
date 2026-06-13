package com.auraboot.framework.bpm.listener;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.automation.trigger.AutomationTriggerService;
import com.auraboot.framework.bpm.event.BpmEvent;
import com.auraboot.framework.bpm.event.EventBusService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Bridge between BPM events and Automation framework.
 * Subscribes to BpmEvent through EventBusService's internal subscriber channel
 * and forwards them to AutomationTriggerService for matching automation execution.
 *
 * Uses getBpmEventType() to pass the raw event type (e.g. "process_started")
 * to automation, since automation rules are keyed by raw BPM types.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BpmEventAutomationBridge {

    private static final List<String> SUPPORTED_BPM_EVENT_TYPES = List.of(
            "process_started",
            "process_ended",
            "task_created",
            "task_completed",
            "task_assigned"
    );

    private final AutomationTriggerService automationTriggerService;
    private final EventBusService eventBusService;

    @PostConstruct
    public void subscribeToBpmEvents() {
        for (String eventType : SUPPORTED_BPM_EVENT_TYPES) {
            eventBusService.subscribe(eventType, this::onBpmEvent);
        }
    }

    public void onBpmEvent(BpmEvent event) {
        if (event.getProcessKey() == null) {
            log.debug("Skipping BPM event without processKey: type={}", event.getBpmEventType());
            return;
        }

        boolean boundTenantContext = !MetaContext.exists() && event.getTenantId() != null;
        if (boundTenantContext) {
            MetaContext.setSystemTenantContext(event.getTenantId());
        }
        try {
            log.debug("Bridging BPM event to automation: type={}, processKey={}, instanceId={}",
                    event.getBpmEventType(), event.getProcessKey(), event.getInstanceId());

            automationTriggerService.onBpmEvent(
                    event.getBpmEventType(),
                    event.getProcessKey(),
                    event.getInstanceId(),
                    event.getPayload()
            );
        } catch (Exception e) {
            log.error("Error bridging BPM event to automation: type={}, error={}",
                    event.getBpmEventType(), e.getMessage(), e);
        } finally {
            if (boundTenantContext) {
                MetaContext.clear();
            }
        }
    }
}
