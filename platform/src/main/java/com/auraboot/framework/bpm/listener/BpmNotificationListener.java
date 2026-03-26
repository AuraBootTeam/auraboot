package com.auraboot.framework.bpm.listener;

import com.auraboot.framework.bpm.event.BpmEvent;
import com.auraboot.framework.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Listens to BPM events and sends in-app notifications via NotificationService.
 *
 * Resolves notification recipients from the event payload:
 * - assigneeUserId: the user assigned to a task
 * - initiatorUserId / startUserId: the process initiator
 *
 * @since 6.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BpmNotificationListener {

    private final NotificationService notificationService;

    @EventListener
    public void onBpmEvent(BpmEvent event) {
        try {
            Long userId = resolveRecipient(event);
            if (userId == null) {
                log.debug("No recipient for BPM event {}, skipping notification", event.getBpmEventType());
                return;
            }

            String title = buildTitle(event);
            String content = buildContent(event);

            notificationService.sendInApp(userId, title, content, "approval",
                    "bpm:" + event.getProcessKey(), event.getInstanceId());

            log.debug("BPM notification sent: type={}, recipient={}", event.getBpmEventType(), userId);
        } catch (Exception e) {
            log.error("BPM notification failed: type={}, error={}", event.getBpmEventType(), e.getMessage(), e);
        }
    }

    private Long resolveRecipient(BpmEvent event) {
        if (event.getPayload() == null) {
            return null;
        }
        // Prefer assignee (for task events), fall back to initiator (for process events)
        Object assignee = event.getPayload().get("assigneeUserId");
        if (assignee != null) {
            return parseLong(assignee);
        }
        Object initiator = event.getPayload().get("initiatorUserId");
        if (initiator != null) {
            return parseLong(initiator);
        }
        Object startUser = event.getPayload().get("startUserId");
        if (startUser != null) {
            return parseLong(startUser);
        }
        return null;
    }

    private Long parseLong(Object value) {
        try {
            return Long.parseLong(value.toString());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Build notification title using i18n keys.
     * Keys follow pattern: bpm.notification.{eventType}.title
     * Fallback to English if i18n is not configured.
     */
    private String buildTitle(BpmEvent event) {
        return switch (event.getBpmEventType()) {
            case "task_created" -> "$i18n:bpm.notification.task_created.title";
            case "task_completed" -> "$i18n:bpm.notification.task_completed.title";
            case "process_started" -> "$i18n:bpm.notification.process_started.title";
            case "process_ended" -> "$i18n:bpm.notification.process_ended.title";
            case "sla_warning" -> "$i18n:bpm.notification.sla_warning.title";
            case "sla_escalated" -> "$i18n:bpm.notification.sla_escalated.title";
            default -> "$i18n:bpm.notification.default.title";
        };
    }

    private String buildContent(BpmEvent event) {
        String processKey = event.getProcessKey() != null ? event.getProcessKey() : "unknown";
        String taskName = getPayloadString(event, "taskName");
        return taskName != null
                ? String.format("Process: %s, Task: %s", processKey, taskName)
                : String.format("Process: %s, Instance: %s", processKey, event.getInstanceId());
    }

    private String getPayloadString(BpmEvent event, String key) {
        if (event.getPayload() != null) {
            Object v = event.getPayload().get(key);
            return v != null ? v.toString() : null;
        }
        return null;
    }
}
