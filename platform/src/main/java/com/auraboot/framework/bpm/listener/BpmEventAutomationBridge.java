package com.auraboot.framework.bpm.listener;

import com.auraboot.framework.automation.trigger.AutomationTriggerService;
import com.auraboot.framework.bpm.event.BpmEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Bridge between BPM events and Automation framework.
 * Listens to BpmEvent (published via Spring ApplicationEvent by EventBusService)
 * and forwards them to AutomationTriggerService for matching automation execution.
 *
 * Uses getBpmEventType() to pass the raw event type (e.g. "process_started")
 * to automation, since automation rules are keyed by raw BPM types.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BpmEventAutomationBridge {

    private final AutomationTriggerService automationTriggerService;

    @EventListener
    public void onBpmEvent(BpmEvent event) {
        if (event.getProcessKey() == null) {
            log.debug("Skipping BPM event without processKey: type={}", event.getBpmEventType());
            return;
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
        }
    }
}
