package com.auraboot.framework.notification.listener;

import com.auraboot.framework.bpm.event.BpmEvent;
import com.auraboot.framework.notification.channel.NotificationMessage;
import com.auraboot.framework.notification.channel.NotificationResult;
import com.auraboot.framework.notification.channel.PushNotificationChannel;
import com.auraboot.framework.notification.model.PushDeviceToken;
import com.auraboot.framework.notification.service.DeviceTokenService;
import com.auraboot.framework.notification.service.NotificationPreferenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.*;

/**
 * Listens to BPM task events and sends push notifications to assignees.
 * Handles task_assigned and task_transferred events.
 *
 * @since 6.4.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskPushNotificationListener {

    private final DeviceTokenService deviceTokenService;
    private final PushNotificationChannel pushNotificationChannel;

    @Autowired(required = false)
    private NotificationPreferenceService preferenceService;

    private static final String CHANNEL_CODE = "push";
    private static final String CATEGORY = "approval";

    @Async("eventTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBpmEvent(BpmEvent event) {
        String bpmType = event.getBpmEventType();
        if (!"task_assigned".equals(bpmType) && !"task_transferred".equals(bpmType)) {
            return;
        }

        try {
            Map<String, Object> payload = event.getPayload();
            if (payload == null) {
                log.debug("BpmEvent payload is null, skipping push notification");
                return;
            }

            List<Long> assigneeUserIds = resolveAssigneeUserIds(payload);
            if (assigneeUserIds.isEmpty()) {
                log.debug("No assignee user IDs resolved for BPM event type={}, skipping push", bpmType);
                return;
            }

            // Filter by notification preferences
            List<Long> recipients = assigneeUserIds;
            if (preferenceService != null) {
                recipients = preferenceService.filterRecipients(assigneeUserIds, CHANNEL_CODE, CATEGORY);
                if (recipients.isEmpty()) {
                    log.debug("All assignees opted out of push+approval notifications, skipping");
                    return;
                }
            }

            // Check that recipients have valid device tokens
            List<Long> recipientsWithTokens = new ArrayList<>();
            for (Long userId : recipients) {
                List<PushDeviceToken> tokens = deviceTokenService.getValidTokens(event.getTenantId(), userId);
                if (!tokens.isEmpty()) {
                    recipientsWithTokens.add(userId);
                }
            }

            if (recipientsWithTokens.isEmpty()) {
                log.debug("No recipients with valid push tokens for event type={}", bpmType);
                return;
            }

            // Build push notification
            String taskId = payload.getOrDefault("taskId", "").toString();
            String processName = payload.getOrDefault("processName", "").toString();
            String title = buildTitle(bpmType, processName);
            String body = buildBody(bpmType, payload);
            String deepLink = "auraboot://bpm/task/" + taskId;

            NotificationMessage message = NotificationMessage.builder()
                    .tenantId(event.getTenantId())
                    .recipientUserIds(recipientsWithTokens)
                    .subject(title)
                    .body(body)
                    .category(CATEGORY)
                    .sourceType("bpm_task")
                    .sourceId(taskId)
                    .extras(Map.of(
                            "deep_link", deepLink,
                            "badge", 1,
                            "bpm_event_type", bpmType
                    ))
                    .build();

            NotificationResult result = pushNotificationChannel.send(message);
            if (!result.isSuccess()) {
                log.warn("Push notification failed for BPM event type={}: {}", bpmType, result.getErrorMessage());
            } else {
                log.info("Push notification sent for BPM event type={} to {} recipients", bpmType, recipientsWithTokens.size());
            }
        } catch (Exception e) {
            log.error("Failed to process push notification for BPM event type={}: {}", bpmType, e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Long> resolveAssigneeUserIds(Map<String, Object> payload) {
        List<Long> userIds = new ArrayList<>();

        // Try assigneeUserIds (list)
        Object assigneeList = payload.get("assigneeUserIds");
        if (assigneeList instanceof List<?> list) {
            for (Object item : list) {
                Long id = toLong(item);
                if (id != null) {
                    userIds.add(id);
                }
            }
        }

        // Try single assigneeUserId
        if (userIds.isEmpty()) {
            Long singleId = toLong(payload.get("assigneeUserId"));
            if (singleId != null) {
                userIds.add(singleId);
            }
        }

        return userIds;
    }

    private Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Long l) return l;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String buildTitle(String bpmType, String processName) {
        String prefix = "task_transferred".equals(bpmType) ? "Task Transferred" : "New Task";
        if (processName != null && !processName.isBlank()) {
            return prefix + ": " + processName;
        }
        return prefix;
    }

    private String buildBody(String bpmType, Map<String, Object> payload) {
        String taskName = payload.getOrDefault("taskName", "").toString();
        if ("task_transferred".equals(bpmType)) {
            return "A task has been transferred to you" + (taskName.isBlank() ? "" : ": " + taskName);
        }
        return "You have a new task to review" + (taskName.isBlank() ? "" : ": " + taskName);
    }
}
