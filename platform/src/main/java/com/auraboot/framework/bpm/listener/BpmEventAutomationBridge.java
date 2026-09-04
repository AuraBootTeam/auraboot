package com.auraboot.framework.bpm.listener;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.automation.trigger.AutomationTriggerService;
import com.auraboot.framework.bpm.event.BpmEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * Bridge between BPM events and Automation framework.
 * Listens to BpmEvent on the Spring application-event channel and forwards
 * them to AutomationTriggerService for matching automation execution.
 *
 * <p><b>Transaction boundary:</b> BPM events are published synchronously
 * inside the engine's task/process transaction (see AuraTaskEventPublisher).
 * This bridge MUST run only after that transaction commits — otherwise the
 * automation reads rows (e.g. the freshly created task behind a
 * task_created event) that are not visible yet, and actions like cc_task
 * fail with "Task not found". Hence {@code @TransactionalEventListener(phase
 * = AFTER_COMMIT)}, mirroring InboxEventListener; {@code fallbackExecution =
 * true} keeps events flowing for the transient publish paths that run
 * without an enclosing transaction. The {@code @Async} hop keeps automation
 * execution off the committing thread.</p>
 *
 * <p>Uses getBpmEventType() to pass the raw event type (e.g. "process_started")
 * to automation, since automation rules are keyed by raw BPM types.</p>
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

    @Async("eventTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onBpmEvent(BpmEvent event) {
        if (event.getBpmEventType() == null
                || !SUPPORTED_BPM_EVENT_TYPES.contains(event.getBpmEventType())) {
            return;
        }
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
