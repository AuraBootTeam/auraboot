package com.auraboot.framework.bpm.listener;

import com.auraboot.framework.bpm.event.ApprovalEvent;
import com.auraboot.framework.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Listens to ApprovalEvent and sends in-app notifications.
 * - APPROVAL_TASK_CREATED → notify each assignee
 * - APPROVAL_COMPLETED → notify chain initiator (via event payload)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApprovalNotificationListener {

    private final NotificationService notificationService;

    @EventListener
    public void onApprovalEvent(ApprovalEvent event) {
        try {
            switch (event.getBpmEventType()) {
                case "approval_task_created" -> notifyAssignees(event);
                case "approval_completed" -> notifyCompletion(event);
                case "approval_task_reassigned" -> notifyReassignment(event);
                default -> log.debug("Unhandled approval event type: {}", event.getBpmEventType());
            }
        } catch (Exception e) {
            log.error("Approval notification failed: type={}, error={}",
                    event.getBpmEventType(), e.getMessage(), e);
        }
    }

    private void notifyAssignees(ApprovalEvent event) {
        if (event.getAssigneeUserIds() == null) return;

        String title = "New Approval Task";
        Object taskTitle = event.getPayload() != null ? event.getPayload().get("taskTitle") : null;
        String content = taskTitle != null ? taskTitle.toString() : "You have a new approval task.";

        for (Long userId : event.getAssigneeUserIds()) {
            notificationService.sendInApp(userId, title, content, "approval",
                    "approval:" + event.getProcessKey(), event.getTaskPid());
        }

        log.debug("Approval task created notification sent to {} users", event.getAssigneeUserIds().size());
    }

    private void notifyCompletion(ApprovalEvent event) {
        // Notify is handled by the existing BpmNotificationListener for chain-level events.
        // This listener focuses on task-level notifications.
        log.debug("Approval {} for task {}", event.getOutcome(), event.getTaskPid());
    }

    private void notifyReassignment(ApprovalEvent event) {
        if (event.getAssigneeUserIds() == null) return;

        for (Long userId : event.getAssigneeUserIds()) {
            notificationService.sendInApp(userId, "Approval Task Reassigned",
                    "An approval task has been reassigned to you.", "approval",
                    "approval:" + event.getProcessKey(), event.getTaskPid());
        }
    }
}
