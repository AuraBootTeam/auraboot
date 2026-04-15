package com.auraboot.framework.inbox.listener;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.event.BpmEvent;
import com.auraboot.framework.inbox.model.InboxItem;
import com.auraboot.framework.inbox.service.InboxService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import com.auraboot.module.meta.event.CommandCompletedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Listens to system events and creates materialized inbox items.
 *
 * Event sources:
 * - BpmEvent(TASK_ASSIGNED/TASK_CREATED) → APPROVAL inbox item for each assignee
 * - BpmEvent(TASK_COMPLETED/TASK_CANCELED) → Close all inbox items for the task
 * - BpmEvent(TASK_TRANSFERRED) → Close old assignee's item, create new assignee's item
 * - BpmEvent(SLA_WARNING/SLA_ESCALATED) → ALERT inbox item
 * - CommandCompletedEvent(STATE_TRANSITION) → ASSIGNMENT inbox item for record owner
 *
 * @since 6.3.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InboxEventListener {

    private final InboxService inboxService;
    private final ObjectMapper objectMapper;
    private final UserService userService;

    // ───────── BPM Events ─────────

    @Async("eventTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBpmEvent(BpmEvent event) {
        try {
            String bpmType = event.getBpmEventType();
            switch (bpmType) {
                case "task_assigned" -> handleTaskAssigned(event);
                case "task_created" -> handleTaskAssigned(event); // backward compat
                case "task_completed" -> handleTaskCompleted(event);
                case "task_canceled" -> handleTaskCanceled(event);
                case "task_claimed" -> handleTaskClaimed(event);
                case "task_transferred" -> handleTaskTransferred(event);
                case "task_delegated" -> handleTaskDelegated(event);
                case "task_revoked" -> handleTaskRevoked(event);
                case "sla_warning", "sla_escalated" -> handleSlaAlert(event);
                default -> log.debug("Skipping BPM event type {} for inbox", bpmType);
            }
        } catch (Exception e) {
            log.error("Failed to create inbox item for BPM event: type={}, error={}",
                    event.getBpmEventType(), e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private void handleTaskAssigned(BpmEvent event) {
        Long tenantId = event.getTenantId();
        MetaContext.setContext(tenantId, null, null, null);
        try {
            // New format: assigneeIds list from TaskEventPublisher
            Object assigneeIdsObj = event.getPayload() != null
                    ? event.getPayload().get("assigneeIds") : null;
            java.util.List<String> assigneeIds;

            if (assigneeIdsObj instanceof java.util.List<?> list) {
                assigneeIds = list.stream().map(Object::toString).toList();
            } else {
                // Fallback: single assigneeUserId (old format from task_created)
                Long single = resolveAssignee(event);
                if (single == null) {
                    log.debug("No assignee for task_assigned event, skipping");
                    return;
                }
                assigneeIds = java.util.List.of(single.toString());
            }

            for (String assigneeIdStr : assigneeIds) {
                Long assigneeUserId = resolveUserIdFromString(assigneeIdStr, tenantId);
                if (assigneeUserId == null) continue;

                MetaContext.setContext(tenantId, assigneeUserId, null, null);
                createApprovalInboxItem(event, assigneeUserId, tenantId);
            }
        } finally {
            MetaContext.clear();
        }
    }

    private void createApprovalInboxItem(BpmEvent event, Long assigneeUserId, Long tenantId) {
        String taskName = getPayloadString(event.getPayload(), "taskName");
        String processKey = event.getProcessKey();

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("cardType", "approval");
        card.put("processKey", processKey);
        card.put("taskInstanceId", getPayloadString(event.getPayload(), "taskInstanceId"));
        card.put("taskName", taskName);
        card.put("processInstanceId", event.getInstanceId());

        String businessKey = getPayloadString(event.getPayload(), "businessKey");
        if (businessKey != null && businessKey.contains(":")) {
            String[] parts = businessKey.split(":", 2);
            card.put("modelCode", parts[0]);
            card.put("recordId", parts[1]);
        }

        // Build human-readable title from enriched payload
        String processName = getPayloadString(event.getPayload(), "processName");
        String readableTitle = buildApprovalTitle(taskName, processName, processKey);

        // Build subtitle with initiator info
        String initiatorName = getPayloadString(event.getPayload(), "initiatorName");
        String subtitle = buildApprovalSubtitle(initiatorName, processName, processKey);

        InboxItem item = new InboxItem();
        item.setTenantId(tenantId);
        item.setUserId(assigneeUserId);
        item.setItemType("approval");
        item.setTitle(readableTitle);
        item.setSubtitle(subtitle);
        item.setPriority(resolvePriority(event.getPayload()));
        item.setSourceType("bpm");
        item.setSourceId(getPayloadString(event.getPayload(), "taskInstanceId"));
        item.setDeepLink("auraboot://bpm/task/" + item.getSourceId());
        item.setCardPayload(toJson(card));
        // New format: clientItemId includes userId to support per-assignee close
        item.setClientItemId("bpm_task_" + item.getSourceId() + "_" + assigneeUserId);

        if (businessKey != null && businessKey.contains(":")) {
            String[] parts = businessKey.split(":", 2);
            item.setModelCode(parts[0]);
            try { item.setRecordId(Long.parseLong(parts[1])); } catch (NumberFormatException ignored) {}
        }

        inboxService.createItem(item);
        log.debug("APPROVAL inbox item created for userId={}, task={}", assigneeUserId, taskName);
    }

    private void handleTaskCompleted(BpmEvent event) {
        String taskInstanceId = getPayloadString(event.getPayload(), "taskInstanceId");
        if (taskInstanceId == null) return;

        // Close all inbox items for this task (prefix match covers old and new clientItemId formats)
        inboxService.closeByClientItemIdPrefix("bpm_task_" + taskInstanceId);
        log.debug("Inbox items closed for completed task: {}", taskInstanceId);
    }

    private void handleTaskCanceled(BpmEvent event) {
        String taskInstanceId = getPayloadString(event.getPayload(), "taskInstanceId");
        if (taskInstanceId == null) return;

        inboxService.closeByClientItemIdPrefix("bpm_task_" + taskInstanceId);
        log.debug("Inbox items closed for canceled task: {}", taskInstanceId);
    }

    private void handleTaskTransferred(BpmEvent event) {
        Long tenantId = event.getTenantId();
        String taskInstanceId = getPayloadString(event.getPayload(), "taskInstanceId");
        String fromUserId = getPayloadString(event.getPayload(), "fromUserId");
        String toUserId = getPayloadString(event.getPayload(), "toUserId");
        if (taskInstanceId == null || toUserId == null) return;

        // Close old assignee's inbox item
        if (fromUserId != null) {
            Long fromId = resolveUserIdFromString(fromUserId, tenantId);
            if (fromId != null) {
                inboxService.closeByClientItemId("bpm_task_" + taskInstanceId + "_" + fromId);
            }
        }

        // Create new assignee's inbox item
        MetaContext.setContext(tenantId, null, null, null);
        try {
            Long toId = resolveUserIdFromString(toUserId, tenantId);
            if (toId != null) {
                MetaContext.setContext(tenantId, toId, null, null);
                createApprovalInboxItem(event, toId, tenantId);
            }
        } finally {
            MetaContext.clear();
        }

        log.debug("Inbox transferred: task={}, from={}, to={}", taskInstanceId, fromUserId, toUserId);
    }

    /**
     * Handle task_claimed: a specific user claimed a task from a candidate pool.
     * Close other candidates' inbox items; claimer's item stays PENDING.
     *
     * Payload: {taskInstanceId, claimUserId, taskName, activityId}
     */
    private void handleTaskClaimed(BpmEvent event) {
        String taskInstanceId = getPayloadString(event.getPayload(), "taskInstanceId");
        String claimUserId = getPayloadString(event.getPayload(), "claimUserId");
        if (taskInstanceId == null || claimUserId == null) return;

        Long tenantId = event.getTenantId();
        Long claimerId = resolveUserIdFromString(claimUserId, tenantId);
        if (claimerId == null) {
            log.warn("Cannot resolve claimUserId={} for task_claimed event", claimUserId);
            return;
        }

        // Close all other candidates' inbox items, keep claimer's
        String prefix = "bpm_task_" + taskInstanceId + "_";
        String claimerClientItemId = "bpm_task_" + taskInstanceId + "_" + claimerId;
        inboxService.closeByClientItemIdPrefixExcluding(prefix, claimerClientItemId, "claimed_by_other");

        log.debug("Inbox items closed for task_claimed: task={}, claimer={}", taskInstanceId, claimerId);
    }

    /**
     * Handle task_delegated: a new assignee was added to a task (add-sign).
     * Create a new APPROVAL inbox item for the new assignee.
     *
     * Payload: {taskInstanceId, newAssigneeId, reason, taskName, activityId}
     */
    private void handleTaskDelegated(BpmEvent event) {
        Long tenantId = event.getTenantId();
        String taskInstanceId = getPayloadString(event.getPayload(), "taskInstanceId");
        String newAssigneeId = getPayloadString(event.getPayload(), "newAssigneeId");
        if (taskInstanceId == null || newAssigneeId == null) return;

        MetaContext.setContext(tenantId, null, null, null);
        try {
            Long assigneeUserId = resolveUserIdFromString(newAssigneeId, tenantId);
            if (assigneeUserId == null) {
                log.warn("Cannot resolve newAssigneeId={} for task_delegated event", newAssigneeId);
                return;
            }

            MetaContext.setContext(tenantId, assigneeUserId, null, null);
            createApprovalInboxItem(event, assigneeUserId, tenantId);
            log.debug("Inbox item created for task_delegated: task={}, newAssignee={}", taskInstanceId, assigneeUserId);
        } finally {
            MetaContext.clear();
        }
    }

    /**
     * Handle task_revoked: an assignee was removed from a task (remove-sign).
     * Close the removed assignee's inbox item.
     *
     * Payload: {taskInstanceId, removedAssigneeId, reason, taskName, activityId}
     */
    private void handleTaskRevoked(BpmEvent event) {
        Long tenantId = event.getTenantId();
        String taskInstanceId = getPayloadString(event.getPayload(), "taskInstanceId");
        String removedAssigneeId = getPayloadString(event.getPayload(), "removedAssigneeId");
        if (taskInstanceId == null || removedAssigneeId == null) return;

        Long removedUserId = resolveUserIdFromString(removedAssigneeId, tenantId);
        if (removedUserId == null) {
            log.warn("Cannot resolve removedAssigneeId={} for task_revoked event", removedAssigneeId);
            return;
        }

        String clientItemId = "bpm_task_" + taskInstanceId + "_" + removedUserId;
        inboxService.closeByClientItemIdWithReason(clientItemId, "sign_revoked");

        log.debug("Inbox item closed for task_revoked: task={}, removedUser={}", taskInstanceId, removedUserId);
    }

    private void handleSlaAlert(BpmEvent event) {
        Long tenantId = event.getTenantId();
        MetaContext.setContext(tenantId, null, null, null);
        try {
            Long assigneeUserId = resolveAssignee(event);
            if (assigneeUserId == null) return;
            MetaContext.setContext(tenantId, assigneeUserId, null, null);

            boolean isEscalated = "sla_escalated".equals(event.getBpmEventType());

            Map<String, Object> card = new LinkedHashMap<>();
            card.put("cardType", isEscalated ? "sla_escalated" : "sla_warning");
            card.put("processKey", event.getProcessKey());
            card.put("processInstanceId", event.getInstanceId());

            InboxItem item = new InboxItem();
            item.setTenantId(tenantId);
            item.setUserId(assigneeUserId);
            item.setItemType("alert");
            item.setTitle(isEscalated ? "SLA Escalated" : "SLA Warning");
            item.setSubtitle("Process: " + event.getProcessKey());
            item.setPriority(isEscalated ? "urgent" : "high");
            item.setSourceType("bpm");
            item.setSourceId(event.getInstanceId());
            item.setCardPayload(toJson(card));
            item.setClientItemId("bpm_sla_" + event.getInstanceId() + "_" + event.getBpmEventType().toLowerCase());

            inboxService.createItem(item);
        } finally {
            MetaContext.clear();
        }
    }

    // ───────── Command Events ─────────

    @Async("eventTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommandCompleted(CommandCompletedEvent event) {
        try {
            // Only create inbox items for state transitions (meaningful business events)
            if (!"state_transition".equals(event.getOperationType())) {
                return;
            }

            Long tenantId = event.getTenantId();
            Long actorId = getActorId(event);
            if (actorId == null || tenantId == null) return;

            MetaContext.setContext(tenantId, actorId, null, null);
            try {
                createStateTransitionItem(event, actorId, tenantId);
            } finally {
                MetaContext.clear();
            }
        } catch (Exception e) {
            log.error("Failed to create inbox item for command event: command={}, error={}",
                    event.getCommandCode(), e.getMessage(), e);
        }
    }

    private void createStateTransitionItem(CommandCompletedEvent event, Long actorId, Long tenantId) {
        String actorName = "System";
        Map<String, Object> metadata = event.getMetadata();
        if (metadata != null && metadata.containsKey("actorName")) {
            actorName = String.valueOf(metadata.get("actorName"));
        }

        String toState = getPayloadString(event.getPayload(), "toState");
        String fromState = getPayloadString(event.getPayload(), "fromState");

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("cardType", "state_transition");
        card.put("modelCode", event.getModelCode());
        card.put("recordId", event.getRecordId());
        card.put("commandCode", event.getCommandCode());
        card.put("fromState", fromState);
        card.put("toState", toState);
        card.put("actorName", actorName);

        // Build human-readable title: "Approve Scorecard: draft → approved"
        String humanCommand = humanize(event.getCommandCode());
        String fromLabel = fromState != null ? humanize(fromState) : "?";
        String toLabel = toState != null ? humanize(toState) : "?";
        String title = humanCommand + ": " + fromLabel + " → " + toLabel;

        // Build subtitle: "Model Name #recordId"
        String modelLabel = humanize(event.getModelCode());
        String subtitle = modelLabel + " #" + event.getRecordId();

        InboxItem item = new InboxItem();
        item.setTenantId(tenantId);
        item.setUserId(actorId);
        item.setItemType("assignment");
        item.setTitle(title);
        item.setSubtitle(subtitle);
        item.setPriority("normal");
        item.setSourceType("command");
        item.setSourceId(event.getEventId());
        item.setModelCode(event.getModelCode());
        try { item.setRecordId(Long.parseLong(event.getRecordId())); } catch (Exception ignored) {}
        item.setDeepLink("auraboot://object/" + event.getModelCode() + "/" + event.getRecordId());
        item.setCardPayload(toJson(card));
        item.setClientItemId("cmd_" + event.getEventId());

        inboxService.createItem(item);
        log.debug("ASSIGNMENT inbox item created for userId={}, command={}", actorId, event.getCommandCode());
    }

    // ───────── Title Formatting ─────────

    /**
     * Build a human-readable approval title.
     * Priority: processName > humanized processKey > fallback.
     */
    private String buildApprovalTitle(String taskName, String processName, String processKey) {
        if (processName != null && !processName.isBlank()) {
            return "Approval: " + processName;
        }
        if (taskName != null && !taskName.isBlank() && isHumanReadable(taskName)) {
            return "Approval: " + taskName;
        }
        if (processKey != null) {
            return "Approval: " + humanize(processKey);
        }
        return "New Approval Task";
    }

    /**
     * Build a subtitle for approval items.
     */
    private String buildApprovalSubtitle(String initiatorName, String processName, String processKey) {
        if (initiatorName != null && !initiatorName.isBlank()) {
            return initiatorName;
        }
        if (processName != null && !processName.isBlank()) {
            return processName;
        }
        if (processKey != null) {
            return humanize(processKey);
        }
        return null;
    }

    /**
     * Check if a string looks like a human-readable name (contains spaces or
     * is not pure camelCase/snake_case identifier like "userTask1").
     */
    private boolean isHumanReadable(String text) {
        // Contains spaces → likely human-readable
        if (text.contains(" ")) return true;
        // Pure lowercase with no separators → likely an activity ID (e.g. "userTask1")
        if (text.matches("^[a-z][a-zA-Z0-9]*$")) return false;
        // Contains version-like suffix (e.g. ":1.0.0") → process key, not human-readable
        if (text.contains(":")) return false;
        return true;
    }

    /**
     * Convert a code string to a human-readable label.
     * Examples: "approve_scorecard" → "Approve Scorecard",
     *           "pr_supplier_scorecard:1.0.0" → "Supplier Scorecard"
     */
    private String humanize(String code) {
        if (code == null || code.isBlank()) return "";
        // Strip version suffix (e.g. ":1.0.0")
        int colonIdx = code.indexOf(':');
        String raw = colonIdx >= 0 ? code.substring(0, colonIdx) : code;
        // Remove common namespace prefixes (e.g. "pr_", "cc_", "thr_")
        if (raw.length() > 3 && raw.charAt(2) == '_' || (raw.length() > 4 && raw.charAt(3) == '_')) {
            int firstUnderscore = raw.indexOf('_');
            if (firstUnderscore > 0 && firstUnderscore <= 3) {
                raw = raw.substring(firstUnderscore + 1);
            }
        }
        return Arrays.stream(raw.split("_"))
                .filter(w -> !w.isEmpty())
                .map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1).toLowerCase())
                .collect(Collectors.joining(" "));
    }

    // ───────── Helpers ─────────

    private Long resolveAssignee(BpmEvent event) {
        if (event.getPayload() == null) return null;
        Object assignee = event.getPayload().get("assigneeUserId");
        if (assignee != null) {
            Long id = parseLong(assignee);
            if (id != null) return id;
            // SmartEngine stores assignees as ULID (pid); look up numeric id
            id = resolveUserIdByPid(assignee.toString(), event.getTenantId());
            if (id != null) return id;
        }
        Object initiator = event.getPayload().get("initiatorUserId");
        if (initiator != null) {
            Long id = parseLong(initiator);
            if (id != null) return id;
            return resolveUserIdByPid(initiator.toString(), event.getTenantId());
        }
        return null;
    }

    private Long resolveUserIdByPid(String pid, Long tenantId) {
        if (pid == null || pid.isBlank()) return null;
        try {
            User user = userService.findByPid(pid);
            return user != null ? user.getId() : null;
        } catch (Exception e) {
            log.debug("Could not resolve user id for pid={}: {}", pid, e.getMessage());
            return null;
        }
    }

    private Long getActorId(CommandCompletedEvent event) {
        Map<String, Object> metadata = event.getMetadata();
        if (metadata == null) return null;
        Object actorId = metadata.get("actorId");
        return parseLong(actorId);
    }

    private String resolvePriority(Map<String, Object> payload) {
        if (payload == null) return "normal";
        Object priority = payload.get("priority");
        if (priority instanceof String p) {
            return switch (p.toLowerCase()) {
                case "urgent", "high", "low" -> p.toLowerCase();
                default -> "normal";
            };
        }
        return "normal";
    }

    private Long resolveUserIdFromString(String userIdStr, Long tenantId) {
        if (userIdStr == null || userIdStr.isBlank()) return null;
        Long numericId = parseLong(userIdStr);
        if (numericId != null) return numericId;
        return resolveUserIdByPid(userIdStr, tenantId);
    }

    private Long parseLong(Object value) {
        if (value == null) return null;
        if (value instanceof Long l) return l;
        if (value instanceof Number n) return n.longValue();
        try { return Long.parseLong(value.toString()); } catch (Exception e) { return null; }
    }

    private String getPayloadString(Map<String, Object> payload, String key) {
        if (payload == null) return null;
        Object v = payload.get(key);
        return v != null ? v.toString() : null;
    }

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            log.warn("Failed to serialize card payload", e);
            return "{}";
        }
    }
}
