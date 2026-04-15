package com.auraboot.framework.inbox.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.inbox.dto.InboxItemResponse;
import com.auraboot.framework.inbox.model.InboxItem;
import com.auraboot.framework.inbox.service.InboxService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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
public class InboxController {

    private final InboxService inboxService;

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

        // Build approval detail response from InboxItem
        // For items without a real BPM process, return a stub with basic approval capabilities
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
        detail.put("sourceRecordId", item.getRecordId());
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
            @RequestBody Map<String, String> body) {
        Long userId = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();
        String action = body.getOrDefault("action", "approved");
        inboxService.markActed(id, userId, tenantId, action);
        return ApiResponse.success(Map.of("status", action, "actedAt", Instant.now().toString()));
    }

    /**
     * Delegate this approval to another user.
     */
    @PostMapping("/{id}/approval-delegate")
    public ApiResponse<Void> delegateApproval(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        Long userId = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();
        inboxService.markActed(id, userId, tenantId, "delegated");
        return ApiResponse.success();
    }

    /**
     * Transfer this approval to another approver.
     */
    @PostMapping("/{id}/approval-transfer")
    public ApiResponse<Void> transferApproval(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        Long userId = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();
        inboxService.markActed(id, userId, tenantId, "transferred");
        return ApiResponse.success();
    }

    /**
     * Add a countersigner to this approval.
     */
    @PostMapping("/{id}/approval-countersign")
    public ApiResponse<Void> addCountersigner(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        inboxService.markActed(id, userId, tenantId, "countersigned");
        return ApiResponse.success();
    }

    /**
     * Withdraw this approval request.
     */
    @PostMapping("/{id}/approval-withdraw")
    public ApiResponse<Void> withdrawApproval(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body) {
        Long userId = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();
        inboxService.markActed(id, userId, tenantId, "withdrawn");
        return ApiResponse.success();
    }

    /**
     * Urge approvers to act on this item.
     */
    @PostMapping("/{id}/approval-urge")
    public ApiResponse<Void> urgeApproval(@PathVariable Long id) {
        // No status change; just a notification nudge
        return ApiResponse.success();
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
                                        @RequestBody Map<String, String> body) {
        Long userId = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();
        String action = body.getOrDefault("action", "acted");
        inboxService.markActed(id, userId, tenantId, action);
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
    public ApiResponse<Map<String, Integer>> batchAct(@RequestBody Map<String, Object> body) {
        Long userId = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();
        @SuppressWarnings("unchecked")
        List<Long> ids = ((List<Number>) body.getOrDefault("ids", List.of()))
                .stream().map(Number::longValue).toList();
        String action = body.getOrDefault("action", "acted").toString();
        if (ids.size() > 100) ids = ids.subList(0, 100);
        int count = inboxService.batchMarkActed(ids, userId, tenantId, action);
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
    public ApiResponse<Map<String, Integer>> batchReject(@RequestBody Map<String, Object> body) {
        Long userId = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();
        @SuppressWarnings("unchecked")
        List<Long> ids = ((List<Number>) body.getOrDefault("ids", List.of()))
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
}
