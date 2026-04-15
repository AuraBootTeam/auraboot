package com.auraboot.framework.inbox.service;

import com.auraboot.framework.inbox.model.InboxItem;
import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.Map;

/**
 * Unified Inbox service — manages materialized inbox items
 * aggregated from BPM approvals, IM mentions, task assignments, etc.
 *
 * @since 6.3.0
 */
public interface InboxService {

    /**
     * Create a new inbox item. Supports dedup via clientItemId.
     * Returns existing item if clientItemId already exists.
     */
    InboxItem createItem(InboxItem item);

    /**
     * Get an inbox item by ID (with tenant/user ownership check).
     */
    InboxItem getItem(Long id, Long userId, Long tenantId);

    /**
     * List inbox items for a user with pagination and optional filters.
     *
     * @param userId   target user
     * @param tenantId tenant
     * @param itemType optional filter (APPROVAL, TASK, MENTION, etc.)
     * @param status   optional filter (PENDING, ACTED, DISMISSED)
     * @param pageNum  page number (1-based)
     * @param pageSize page size
     */
    IPage<InboxItem> listByUser(Long userId, Long tenantId, String itemType,
                                 String status, int pageNum, int pageSize);

    /**
     * List inbox items for a user with pagination and multi-type filter.
     * Used for segment-based queries (e.g. Pending = APPROVAL + TASK_DUE + ASSIGNMENT).
     *
     * @param userId    target user
     * @param tenantId  tenant
     * @param itemTypes optional filter — list of item types (null = all)
     * @param status    optional filter (PENDING, ACTED, DISMISSED)
     * @param pageNum   page number (1-based)
     * @param pageSize  page size
     */
    IPage<InboxItem> listByUser(Long userId, Long tenantId, java.util.List<String> itemTypes,
                                 String status, int pageNum, int pageSize);

    /**
     * Get unread counts grouped by item type.
     */
    Map<String, Integer> getUnreadSummary(Long userId, Long tenantId);

    /**
     * Total unread count.
     */
    int getUnreadCount(Long userId, Long tenantId);

    /**
     * Mark a single item as read.
     */
    void markRead(Long id, Long userId, Long tenantId);

    /**
     * Mark all pending items as read.
     */
    int markAllRead(Long userId, Long tenantId);

    /**
     * Mark an item as acted (approve, reject, mark_done, etc.).
     */
    void markActed(Long id, Long userId, Long tenantId, String action);

    /**
     * Dismiss an item (user chose to ignore it).
     */
    void dismiss(Long id, Long userId, Long tenantId);

    /**
     * Batch mark items as read.
     */
    int batchMarkRead(java.util.List<Long> ids, Long userId, Long tenantId);

    /**
     * Batch mark items as acted.
     */
    int batchMarkActed(java.util.List<Long> ids, Long userId, Long tenantId, String action);

    /**
     * Batch dismiss items.
     */
    int batchDismiss(java.util.List<Long> ids, Long userId, Long tenantId);

    /**
     * Close inbox items whose clientItemId starts with the given prefix.
     * Used to close all items for a completed/canceled task (covers old and new clientItemId formats).
     */
    void closeByClientItemIdPrefix(String prefix);

    /**
     * Close a specific inbox item by exact clientItemId.
     * Used for targeted close (e.g. transfer removes specific user's item).
     */
    void closeByClientItemId(String clientItemId);

    /**
     * Close inbox items matching prefix but excluding a specific clientItemId.
     * Used for task_claimed: close other candidates' items but keep claimer's.
     *
     * @param prefix             clientItemId prefix (e.g. "bpm_task_{taskId}_")
     * @param excludeClientItemId the clientItemId to keep (claimer's item)
     * @param reason             action_taken value (e.g. "claimed_by_other")
     */
    void closeByClientItemIdPrefixExcluding(String prefix, String excludeClientItemId, String reason);

    /**
     * Close a specific inbox item by clientItemId with a reason.
     * Used for task_revoked: close removed assignee's item with explanation.
     */
    void closeByClientItemIdWithReason(String clientItemId, String reason);
}
