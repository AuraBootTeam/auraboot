package com.auraboot.framework.inbox.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.extension.WorkflowCapability;
import com.auraboot.framework.plugin.pf4j.WorkflowCapabilityRegistry;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.inbox.dto.InboxItemResponse;
import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
import com.auraboot.framework.inbox.model.InboxItem;
import com.auraboot.framework.inbox.service.InboxService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Unified Inbox API — shared by web and mobile clients.
 *
 * Provides a single endpoint to fetch all actionable items
 * (approvals, tasks, mentions, alerts, assignments) in a unified, paginated feed.
 *
 * @since 6.3.0
 */
@RestController
@RequestMapping({"/api/inbox", "/api/mobile/inbox"})
@RequiredArgsConstructor
@AuthenticatedAccess("inbox actions are scoped to the caller's own items (userId + tenantId)")
public class InboxController {

    private final InboxService inboxService;
    private final WorkflowCapabilityRegistry workflowCapabilities;
    private final com.auraboot.framework.user.service.UserService users;
    private static final Set<String> REJECTION_ACTIONS = Set.of("reject", "rejected");

    /**
     * List inbox items for the current user.
     *
     * @param itemType optional filter: single type or comma-separated list
     *                 (e.g. "APPROVAL" or "APPROVAL,TASK_DUE,ASSIGNMENT")
     * @param status   optional filter: PENDING, ACTED, DISMISSED, EXPIRED (default: all)
     * @param pageNum  page number (1-based, default 1)
     * @param pageSize page size (default 20, max 100)
     */
    @GetMapping
    public ApiResponse<IPage<InboxItemResponse>> list(
            @RequestParam(required = false) String itemType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {

        Long userId = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();

        pageSize = Math.min(pageSize, 100);

        // Support comma-separated itemType for segment-based queries
        List<String> itemTypes = null;
        if (itemType != null && !itemType.isBlank()) {
            itemTypes = java.util.Arrays.stream(itemType.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(String::toLowerCase)
                    .toList();
            if (itemTypes.size() == 1) {
                // Single type — use the original single-value path
                IPage<InboxItem> page = inboxService.listByUser(userId, tenantId, itemTypes.get(0), status, pageNum, pageSize);
                return ApiResponse.success(toResponsePage(page));
            }
        }

        IPage<InboxItem> page = inboxService.listByUser(userId, tenantId, itemTypes, status, pageNum, pageSize);
        return ApiResponse.success(toResponsePage(page));
    }

    /**
     * Get unread counts grouped by item type.
     */
    @GetMapping("/unread-summary")
    public ApiResponse<Map<String, Integer>> unreadSummary() {
        Long userId = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(inboxService.getUnreadSummary(userId, tenantId));
    }

    /**
     * Get total unread count (for badge display).
     */
    @GetMapping("/unread-count")
    public ApiResponse<Integer> unreadCount() {
        Long userId = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(inboxService.getUnreadCount(userId, tenantId));
    }

    /**
     * Get a single inbox item.
     */
    @GetMapping("/{id}")
    public ApiResponse<InboxItemResponse> getItem(@PathVariable Long id) {
        Long userId = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();
        InboxItem item = inboxService.getItem(id, userId, tenantId);
        if (item == null) {
            return ApiResponse.success(null);
        }
        return ApiResponse.success(InboxItemResponse.from(item));
    }

    private IPage<InboxItemResponse> toResponsePage(IPage<InboxItem> page) {
        Page<InboxItemResponse> responsePage = new Page<>(page.getCurrent(), page.getSize());
        responsePage.setTotal(page.getTotal());
        responsePage.setRecords(page.getRecords().stream()
                .map(InboxItemResponse::from)
                .toList());
        return responsePage;
    }

    /**
     * Get full approval detail for a single inbox item.
     * Returns process trail, source record info, and available actions.
     */
    @GetMapping("/{id}/approval-detail")
    public ApiResponse<Map<String, Object>> getApprovalDetail(@PathVariable Long id) {
        Long userId = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();
        InboxItem item = inboxService.getItem(id, userId, tenantId);
        if (item == null) {
            return ApiResponse.error("Inbox item not found");
        }

        if ("workflow".equals(item.getSourceType()) || "bpm".equals(item.getSourceType())) {
            if (item.getSourceId() == null || item.getSourceId().isBlank()) {
                throw new IllegalStateException("Workflow inbox item has no task identity");
            }
            Map<String, Object> detail = new java.util.LinkedHashMap<>(workflowCapabilities.execute(
                    "task.approval-detail", workflowRequest(Map.of("taskId", item.getSourceId(),
                            "locale", org.springframework.context.i18n.LocaleContextHolder.getLocale().toLanguageTag())))
                    .payload());
            detail.put("id", item.getId());
            detail.put("processName", item.getTitle());
            return ApiResponse.success(detail);
        }

        // Build approval detail response from InboxItem
        // For items without a real workflow, return a stub with basic approval capabilities
        Map<String, Object> currentStep = Map.of(
            "id", "step-1",
            "approverName", "You",
            "action", "pending",
            "isCurrent", true,
            "stepType", "sequential",
            "isOverdue", false,
            "attachments", List.of()
        );

        Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("id", item.getId());
        detail.put("processName", item.getTitle() != null ? item.getTitle() : "Approval Request");
        detail.put("status", item.getStatus() != null ? item.getStatus().toLowerCase() : "pending");
        detail.put("submittedBy", item.getSubtitle() != null ? item.getSubtitle() : "Requester");
        detail.put("submittedAt", item.getCreatedAt() != null ? item.getCreatedAt().toString() : Instant.now().toString());
        detail.put("sourceModel", item.getModelCode());
        detail.put("sourceRecordPid", item.getSourceRecordPid());
        detail.put("sourceRecordTitle", item.getTitle());
        detail.put("trail", List.of(currentStep));
        detail.put("comment", null);
        detail.put("canWithdraw", false);
        detail.put("canUrge", false);
        detail.put("currentStepType", "sequential");
        detail.put("attachments", List.of());
        detail.put("sourceRecordFields", null);

        return ApiResponse.success(detail);
    }

    /**
     * Submit an approval action (approve, reject, forward).
     * Marks the inbox item as acted and returns updated status.
     */
    @PostMapping("/{id}/approval-action")
    public ApiResponse<Map<String, Object>> submitApprovalAction(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String comment) {
        Long userId = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();
        Map<String, String> request = body != null ? body : Map.of();
        String resolvedAction = normalizeAction(request.getOrDefault("action", action != null ? action : "approved"));
        String resolvedComment = request.getOrDefault("comment", comment);
        ApiResponse<Map<String, Object>> rejectionValidation =
                validateRejectionComment(resolvedAction, resolvedComment);
        if (rejectionValidation != null) {
            return rejectionValidation;
        }
        String completedTaskId = completeSourceBpmTaskIfNeeded(id, userId, tenantId, resolvedAction, resolvedComment);
        if (completedTaskId != null) {
            inboxService.recordCompletedWorkflowAction(id, userId, tenantId, completedTaskId, resolvedAction);
        } else {
            inboxService.markActed(id, userId, tenantId, resolvedAction);
        }
        return ApiResponse.success(Map.of("status", resolvedAction, "actedAt", Instant.now().toString()));
    }

    /**
     * Drive the approval into the process engine before the inbox item is marked acted.
     *
     * Mobile approve/reject used to only flip the inbox item ("acted") while the SmartEngine
     * task stayed pending — a silent no-op approval. BPM-sourced items carry the task
     * instance id in sourceId; complete it so the process actually advances. Completion
     * happens first: if it fails the item stays pending and the user can retry, instead of
     * an acted item hiding a dead approval.
     */
    private String completeSourceBpmTaskIfNeeded(
            Long itemId, Long userId, Long tenantId, String resolvedAction, String resolvedComment) {
        boolean approval = "approved".equals(resolvedAction) || "approve".equals(resolvedAction);
        boolean rejection = REJECTION_ACTIONS.contains(resolvedAction);
        if (!approval && !rejection) {
            return null;
        }
        InboxItem item = inboxService.getItem(itemId, userId, tenantId);
        // InboxEventListener creates BPM task items with sourceType "workflow"; older
        // paths used "bpm". Both carry the engine task id in sourceId — gating on the
        // literal "bpm" alone turned every listener-created approval into a silent
        // no-op (item acted, engine task left pending).
        boolean bpmSourced = item != null
                && ("bpm".equals(item.getSourceType()) || "workflow".equals(item.getSourceType()));
        if (!bpmSourced || item.getSourceId() == null) {
            return null;
        }
        if (approval) {
            workflowCapabilities.execute("task.approve", workflowRequest(Map.of(
                    "taskId", item.getSourceId(), "comment", resolvedComment == null ? "" : resolvedComment, "variables", Map.of())));
        } else {
            workflowCapabilities.execute("task.reject", workflowRequest(Map.of(
                    "taskId", item.getSourceId(), "comment", resolvedComment == null ? "" : resolvedComment, "variables", Map.of())));
        }
        return item.getSourceId();
    }

    /**
     * Delegate this approval to another tenant member. Drives the real engine
     * delegate (the task stays active with the delegation recorded) before the
     * inbox item is marked acted; an engine failure leaves the item pending.
     */
    @PostMapping("/{id}/approval-delegate")
    public ApiResponse<Map<String, Object>> delegateApproval(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        Long userId = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();
        String targetUserPid = requireTenantMemberPid(body);
        InboxItem item = requireWorkflowItem(id, userId, tenantId);
        workflowCapabilities.execute("task.delegate", workflowRequest(Map.of(
                "taskId", item.getSourceId(), "targetUserPid", targetUserPid,
                "comment", textOrEmpty(body, "comment"))));
        inboxService.recordCompletedWorkflowAction(id, userId, tenantId, item.getSourceId(), "delegated");
        return ApiResponse.success(Map.of("status", "delegated", "targetUserPid", targetUserPid));
    }

    /**
     * Forward this approval to another tenant member — the inbox-level alias of
     * delegate used by mobile clients (approval-forward).
     */
    @PostMapping("/{id}/approval-forward")
    public ApiResponse<Map<String, Object>> forwardApproval(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        return delegateApproval(id, body);
    }

    /**
     * Transfer this approval to another tenant member. Drives the real engine
     * transfer before the inbox item is marked acted.
     */
    @PostMapping("/{id}/approval-transfer")
    public ApiResponse<Map<String, Object>> transferApproval(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        Long userId = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();
        String targetUserPid = requireTenantMemberPid(body);
        InboxItem item = requireWorkflowItem(id, userId, tenantId);
        workflowCapabilities.execute("task.transfer", workflowRequest(Map.of(
                "taskId", item.getSourceId(), "targetUserPid", targetUserPid,
                "comment", textOrEmpty(body, "comment"))));
        inboxService.recordCompletedWorkflowAction(id, userId, tenantId, item.getSourceId(), "transferred");
        return ApiResponse.success(Map.of("status", "transferred", "targetUserPid", targetUserPid));
    }

    /**
     * Add a countersigner (additional assignee) to this approval. The engine
     * models unordered additional assignees, so a directional request is
     * rejected instead of being silently treated as a plain candidate add.
     */
    @PostMapping("/{id}/approval-countersign")
    public ApiResponse<Map<String, Object>> addCountersigner(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        Long userId = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();
        if (body != null && body.get("direction") != null && !String.valueOf(body.get("direction")).isBlank()) {
            throw new IllegalArgumentException(
                    "Ordered countersign direction is not supported; additional assignees are unordered");
        }
        String targetUserPid = requireTenantMemberPid(body);
        InboxItem item = requireWorkflowItem(id, userId, tenantId);
        workflowCapabilities.execute("task.add-sign", workflowRequest(Map.of(
                "taskId", item.getSourceId(), "targetUserPid", targetUserPid,
                "reason", textOrEmpty(body, "comment"))));
        inboxService.recordCompletedWorkflowAction(id, userId, tenantId, item.getSourceId(), "countersigned");
        return ApiResponse.success(Map.of("status", "countersigned", "targetUserPid", targetUserPid));
    }

    /**
     * Withdraw this approval request through the engine (subject to the
     * process-level withdrawPolicy) before marking the inbox item acted.
     */
    @PostMapping("/{id}/approval-withdraw")
    public ApiResponse<Map<String, Object>> withdrawApproval(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body) {
        Long userId = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();
        InboxItem item = requireWorkflowItem(id, userId, tenantId);
        workflowCapabilities.execute("task.withdraw", workflowRequest(Map.of(
                "taskId", item.getSourceId(), "reason", body == null
                        ? "" : firstNonBlank(body.get("reason"), body.get("comment")))));
        inboxService.recordCompletedWorkflowAction(id, userId, tenantId, item.getSourceId(), "withdrawn");
        return ApiResponse.success(Map.of("status", "withdrawn"));
    }

    /**
     * Urge the assignees of this approval. Writes real URGE notify records and a
     * task_urge audit row through the engine capability; the inbox item stays
     * pending because urging is not an approval decision.
     */
    @PostMapping("/{id}/approval-urge")
    public ApiResponse<Map<String, Object>> urgeApproval(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body) {
        Long userId = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();
        InboxItem item = requireWorkflowItem(id, userId, tenantId);
        WorkflowCapability.WorkflowResult result = workflowCapabilities.execute("task.urge",
                workflowRequest(Map.of("taskId", item.getSourceId(),
                        "comment", textOrEmpty(body, "comment"))));
        long urged = result.payload() == null || !(result.payload().get("urged") instanceof Number number)
                ? 0 : number.longValue();
        return ApiResponse.success(Map.of("status", "urged", "urged", urged));
    }

    /** Workflow-sourced items only: approval actions drive the engine task in sourceId. */
    private InboxItem requireWorkflowItem(Long itemId, Long userId, Long tenantId) {
        InboxItem item = inboxService.getItem(itemId, userId, tenantId);
        boolean workflowSourced = item != null
                && ("bpm".equals(item.getSourceType()) || "workflow".equals(item.getSourceType()));
        if (!workflowSourced || item.getSourceId() == null) {
            throw new IllegalArgumentException(
                    "Approval action requires a workflow-sourced inbox item: " + itemId);
        }
        return item;
    }

    /** Targets must be members of the current tenant; a global pid lookup is not membership evidence. */
    private String requireTenantMemberPid(Map<String, Object> body) {
        String targetUserPid = body == null ? null : textOrEmpty(body, "targetUserPid");
        if (targetUserPid.isBlank()) {
            throw new IllegalArgumentException("targetUserPid is required");
        }
        if (users.findInTenantByPid(MetaContext.getCurrentTenantId(), targetUserPid) == null) {
            throw new IllegalArgumentException("Target user is not a member of the current tenant");
        }
        return targetUserPid;
    }

    private static String textOrEmpty(Map<String, ?> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static String firstNonBlank(Object first, Object second) {
        for (Object value : new Object[] {first, second}) {
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        }
        return "";
    }

    /**
     * Mark a single item as read.
     */
    @PutMapping("/{id}/read")
    public ApiResponse<Void> markRead(@PathVariable Long id) {
        Long userId = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();
        inboxService.markRead(id, userId, tenantId);
        return ApiResponse.success();
    }

    /**
     * Mark all pending items as read.
     */
    @PutMapping("/read-all")
    public ApiResponse<Map<String, Integer>> markAllRead() {
        Long userId = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();
        int count = inboxService.markAllRead(userId, tenantId);
        return ApiResponse.success(Map.of("markedCount", count));
    }

    /**
     * Mark an item as acted (e.g., approved, rejected, done).
     */
    @PutMapping("/{id}/act")
    public ApiResponse<Void> markActed(@PathVariable Long id,
                                        @RequestBody(required = false) Map<String, String> body,
                                        @RequestParam(required = false) String action,
                                        @RequestParam(required = false) String comment) {
        Long userId = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();
        Map<String, String> request = body != null ? body : Map.of();
        String resolvedAction = normalizeAction(request.getOrDefault("action", action != null ? action : "acted"));
        String resolvedComment = request.getOrDefault("comment", comment);
        ApiResponse<Void> rejectionValidation = validateRejectionComment(resolvedAction, resolvedComment);
        if (rejectionValidation != null) {
            return rejectionValidation;
        }
        inboxService.markActed(id, userId, tenantId, resolvedAction);
        return ApiResponse.success();
    }

    /**
     * Dismiss an item (user chose to ignore it).
     */
    @PutMapping("/{id}/dismiss")
    public ApiResponse<Void> dismiss(@PathVariable Long id) {
        Long userId = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();
        inboxService.dismiss(id, userId, tenantId);
        return ApiResponse.success();
    }

    // ───────── Batch Operations ─────────

    /**
     * Batch mark items as read.
     *
     * @param body JSON with "ids" array (max 100 items)
     */
    @PutMapping("/batch/read")
    public ApiResponse<Map<String, Integer>> batchRead(@RequestBody Map<String, List<Long>> body) {
        Long userId = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();
        List<Long> ids = body.getOrDefault("ids", List.of());
        if (ids.size() > 100) ids = ids.subList(0, 100);
        int count = inboxService.batchMarkRead(ids, userId, tenantId);
        return ApiResponse.success(Map.of("markedCount", count));
    }

    /**
     * Batch mark items as acted (e.g., bulk approve).
     *
     * @param body JSON with "ids" array and "action" string (max 100 items)
     */
    @PutMapping("/batch/act")
    public ApiResponse<Map<String, Integer>> batchAct(
            @RequestBody(required = false) Map<String, Object> body,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String comment) {
        Long userId = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();
        Map<String, Object> request = body != null ? body : Map.of();
        @SuppressWarnings("unchecked")
        List<Long> ids = ((List<Number>) request.getOrDefault("ids", List.of()))
                .stream().map(Number::longValue).toList();
        String resolvedAction = normalizeAction(request.getOrDefault("action", action != null ? action : "acted").toString());
        Object resolvedComment = request.getOrDefault("comment", comment);
        ApiResponse<Map<String, Integer>> rejectionValidation =
                validateRejectionComment(resolvedAction, resolvedComment);
        if (rejectionValidation != null) {
            return rejectionValidation;
        }
        if (ids.size() > 100) ids = ids.subList(0, 100);
        int count = inboxService.batchMarkActed(ids, userId, tenantId, resolvedAction);
        return ApiResponse.success(Map.of("actedCount", count));
    }

    /**
     * Batch approve items — convenience alias for batchAct with action=approved.
     *
     * @param body JSON with "ids" array and optional "comment" string (max 100 items)
     */
    @PostMapping("/batch/approve")
    public ApiResponse<Map<String, Integer>> batchApprove(@RequestBody Map<String, Object> body) {
        Long userId = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();
        @SuppressWarnings("unchecked")
        List<Long> ids = ((List<Number>) body.getOrDefault("ids", List.of()))
                .stream().map(Number::longValue).toList();
        if (ids.size() > 100) ids = ids.subList(0, 100);
        int count = inboxService.batchMarkActed(ids, userId, tenantId, "approved");
        return ApiResponse.success(Map.of("actedCount", count));
    }

    /**
     * Batch reject items — convenience alias for batchAct with action=rejected.
     *
     * @param body JSON with "ids" array and optional "comment" string (max 100 items)
     */
    @PostMapping("/batch/reject")
    public ApiResponse<Map<String, Integer>> batchReject(
            @RequestBody(required = false) Map<String, Object> body,
            @RequestParam(required = false) String comment) {
        Long userId = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();
        Map<String, Object> request = body != null ? body : Map.of();
        Object resolvedComment = request.getOrDefault("comment", comment);
        ApiResponse<Map<String, Integer>> rejectionValidation =
                validateRejectionComment("rejected", resolvedComment);
        if (rejectionValidation != null) {
            return rejectionValidation;
        }
        @SuppressWarnings("unchecked")
        List<Long> ids = ((List<Number>) request.getOrDefault("ids", List.of()))
                .stream().map(Number::longValue).toList();
        if (ids.size() > 100) ids = ids.subList(0, 100);
        int count = inboxService.batchMarkActed(ids, userId, tenantId, "rejected");
        return ApiResponse.success(Map.of("actedCount", count));
    }

    /**
     * Batch dismiss items.
     *
     * @param body JSON with "ids" array (max 100 items)
     */
    @PutMapping("/batch/dismiss")
    public ApiResponse<Map<String, Integer>> batchDismiss(@RequestBody Map<String, List<Long>> body) {
        Long userId = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();
        List<Long> ids = body.getOrDefault("ids", List.of());
        if (ids.size() > 100) ids = ids.subList(0, 100);
        int count = inboxService.batchDismiss(ids, userId, tenantId);
        return ApiResponse.success(Map.of("dismissedCount", count));
    }

    private String normalizeAction(String action) {
        if (action == null || action.isBlank()) {
            return "acted";
        }
        return action.trim().toLowerCase();
    }

    private WorkflowCapability.WorkflowRequest workflowRequest(Map<String, Object> payload) {
        return new WorkflowCapability.WorkflowRequest(
                MetaContext.getCurrentTenantId(), MetaContext.getCurrentUserId(), payload);
    }

    private <T> ApiResponse<T> validateRejectionComment(String action, Object comment) {
        if (!REJECTION_ACTIONS.contains(action)) {
            return null;
        }
        if (comment == null || comment.toString().trim().isEmpty()) {
            return ApiResponse.error("Rejection comment is required");
        }
        return null;
    }
}
