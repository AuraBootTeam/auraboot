package com.auraboot.framework.integration;

import com.auraboot.framework.inbox.model.InboxItem;
import com.auraboot.framework.inbox.service.InboxService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Inbox (unified action queue) integration test.
 * Tests CRUD, filtering, read/act/dismiss state, dedup, unread counts.
 * Uses real database, no mocking.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class InboxIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private InboxService inboxService;

    private final String testRunId = String.valueOf(System.currentTimeMillis());
    private Long approvalItemId;
    private Long mentionItemId;
    private Long taskItemId;

    // ========== Create Tests ==========

    @Test
    @Order(1)
    void createApprovalItem() {
        InboxItem item = new InboxItem();
        item.setTenantId(getTestTenant().getId());
        item.setUserId(getTestUser().getId());
        item.setItemType("approval");
        item.setTitle("Approve PO-" + testRunId);
        item.setSubtitle("Process: purchase_approval");
        item.setPriority("high");
        item.setSourceType("bpm");
        item.setSourceId("task_" + testRunId);
        item.setModelCode("purchase_order");
        item.setRecordId(100L);
        item.setDeepLink("auraboot://bpm/task/task_" + testRunId);
        item.setCardPayload("{\"cardType\":\"approval\",\"processKey\":\"purchase_approval\"}");
        item.setClientItemId("bpm_task_" + testRunId);

        InboxItem saved = inboxService.createItem(item);
        assertNotNull(saved.getId());
        assertEquals("pending", saved.getStatus());
        assertFalse(saved.getIsRead());
        assertEquals("approval", saved.getItemType());
        assertEquals("high", saved.getPriority());

        approvalItemId = saved.getId();
    }

    @Test
    @Order(2)
    void createMentionItem() {
        InboxItem item = new InboxItem();
        item.setTenantId(getTestTenant().getId());
        item.setUserId(getTestUser().getId());
        item.setItemType("mention");
        item.setTitle("You were mentioned in a chat");
        item.setSubtitle("Hey, check this out...");
        item.setPriority("normal");
        item.setSourceType("im");
        item.setSourceId("msg_" + testRunId);
        item.setDeepLink("auraboot://im/conversation/1?messageId=" + testRunId);
        item.setCardPayload("{\"cardType\":\"mention\",\"conversationId\":1}");
        item.setClientItemId("im_mention_" + testRunId);

        InboxItem saved = inboxService.createItem(item);
        assertNotNull(saved.getId());
        mentionItemId = saved.getId();
    }

    @Test
    @Order(3)
    void createTaskItem() {
        InboxItem item = new InboxItem();
        item.setTenantId(getTestTenant().getId());
        item.setUserId(getTestUser().getId());
        item.setItemType("task");
        item.setTitle("Follow up with client");
        item.setSubtitle("Due: tomorrow");
        item.setPriority("normal");
        item.setSourceType("command");
        item.setSourceId("cmd_" + testRunId);
        item.setModelCode("pm_task");
        item.setRecordId(200L);
        item.setClientItemId("task_" + testRunId);

        InboxItem saved = inboxService.createItem(item);
        assertNotNull(saved.getId());
        taskItemId = saved.getId();
    }

    // ========== Dedup Test ==========

    @Test
    @Order(4)
    void createDuplicateItem_returnsExisting() {
        InboxItem dup = new InboxItem();
        dup.setTenantId(getTestTenant().getId());
        dup.setUserId(getTestUser().getId());
        dup.setItemType("approval");
        dup.setTitle("Duplicate");
        dup.setClientItemId("bpm_task_" + testRunId); // same clientItemId as order 1

        InboxItem result = inboxService.createItem(dup);
        assertEquals(approvalItemId, result.getId(), "Should return existing item on dedup");
    }

    // ========== List / Filter Tests ==========

    @Test
    @Order(10)
    void listAll_returnsPaginated() {
        IPage<InboxItem> page = inboxService.listByUser(
                getTestUser().getId(), getTestTenant().getId(),
                (String) null, null, 1, 10);
        assertTrue(page.getRecords().size() >= 3, "Should have at least 3 items");
        // Ordered by createdAt DESC
        assertTrue(page.getRecords().get(0).getCreatedAt()
                .compareTo(page.getRecords().get(1).getCreatedAt()) >= 0);
    }

    @Test
    @Order(11)
    void listByType_filtersCorrectly() {
        IPage<InboxItem> approvals = inboxService.listByUser(
                getTestUser().getId(), getTestTenant().getId(),
                "approval", null, 1, 10);
        assertTrue(approvals.getRecords().size() >= 1);
        approvals.getRecords().forEach(item ->
                assertEquals("approval", item.getItemType()));

        IPage<InboxItem> mentions = inboxService.listByUser(
                getTestUser().getId(), getTestTenant().getId(),
                "mention", null, 1, 10);
        assertTrue(mentions.getRecords().size() >= 1);
    }

    @Test
    @Order(12)
    void listByStatus_filtersCorrectly() {
        IPage<InboxItem> pending = inboxService.listByUser(
                getTestUser().getId(), getTestTenant().getId(),
                (String) null, "pending", 1, 10);
        assertTrue(pending.getRecords().size() >= 3);
        pending.getRecords().forEach(item ->
                assertEquals("pending", item.getStatus()));
    }

    // ========== Unread Count Tests ==========

    @Test
    @Order(20)
    void unreadCount_returnsCorrectTotal() {
        int count = inboxService.getUnreadCount(getTestUser().getId(), getTestTenant().getId());
        assertTrue(count >= 3, "Should have at least 3 unread items");
    }

    @Test
    @Order(21)
    void unreadSummary_groupsByType() {
        Map<String, Integer> summary = inboxService.getUnreadSummary(
                getTestUser().getId(), getTestTenant().getId());
        assertTrue(summary.containsKey("total"));
        assertTrue(summary.get("total") >= 3);
        assertTrue(summary.containsKey("approval"));
        assertTrue(summary.containsKey("mention"));
    }

    // ========== Read State Tests ==========

    @Test
    @Order(30)
    void markRead_setsReadState() {
        inboxService.markRead(approvalItemId, getTestUser().getId(), getTestTenant().getId());

        InboxItem item = inboxService.getItem(approvalItemId, getTestUser().getId(), getTestTenant().getId());
        assertTrue(item.getIsRead());
        assertNotNull(item.getReadAt());
        assertEquals("pending", item.getStatus(), "Status should still be PENDING");
    }

    @Test
    @Order(31)
    void markRead_reducesUnreadCount() {
        // After marking approval as read, unread should be at least 2
        int count = inboxService.getUnreadCount(getTestUser().getId(), getTestTenant().getId());
        assertTrue(count >= 2);
    }

    @Test
    @Order(32)
    void markAllRead_setsAllAsRead() {
        int marked = inboxService.markAllRead(getTestUser().getId(), getTestTenant().getId());
        assertTrue(marked >= 2, "Should mark at least 2 items as read");

        int count = inboxService.getUnreadCount(getTestUser().getId(), getTestTenant().getId());
        assertEquals(0, count, "All should be read now");
    }

    // ========== Act / Dismiss Tests ==========

    @Test
    @Order(40)
    void markActed_setsActionAndStatus() {
        inboxService.markActed(approvalItemId, getTestUser().getId(), getTestTenant().getId(), "approve");

        InboxItem item = inboxService.getItem(approvalItemId, getTestUser().getId(), getTestTenant().getId());
        assertEquals("acted", item.getStatus());
        assertEquals("approve", item.getActionTaken());
        assertNotNull(item.getActedAt());
    }

    @Test
    @Order(41)
    void markActed_alreadyActed_noOp() {
        // Should not throw, just no-op
        inboxService.markActed(approvalItemId, getTestUser().getId(), getTestTenant().getId(), "reject");

        InboxItem item = inboxService.getItem(approvalItemId, getTestUser().getId(), getTestTenant().getId());
        assertEquals("approve", item.getActionTaken(), "Should keep original action");
    }

    @Test
    @Order(42)
    void dismiss_setsStatus() {
        inboxService.dismiss(mentionItemId, getTestUser().getId(), getTestTenant().getId());

        InboxItem item = inboxService.getItem(mentionItemId, getTestUser().getId(), getTestTenant().getId());
        assertEquals("dismissed", item.getStatus());
        assertNotNull(item.getActedAt());
    }

    @Test
    @Order(43)
    void dismiss_alreadyDismissed_noOp() {
        inboxService.dismiss(mentionItemId, getTestUser().getId(), getTestTenant().getId());

        InboxItem item = inboxService.getItem(mentionItemId, getTestUser().getId(), getTestTenant().getId());
        assertEquals("dismissed", item.getStatus(), "Should remain dismissed");
    }

    // ========== Get Item Tests ==========

    @Test
    @Order(50)
    void getItem_wrongUser_returnsNull() {
        InboxItem item = inboxService.getItem(taskItemId, 99999L, getTestTenant().getId());
        assertNull(item, "Should not return item for different user");
    }

    @Test
    @Order(51)
    void getItem_wrongTenant_returnsNull() {
        InboxItem item = inboxService.getItem(taskItemId, getTestUser().getId(), 99999L);
        assertNull(item, "Should not return item for different tenant");
    }

    // ========== Filter Status After Actions ==========

    @Test
    @Order(60)
    void listPending_excludesActedAndDismissed() {
        IPage<InboxItem> pending = inboxService.listByUser(
                getTestUser().getId(), getTestTenant().getId(),
                (String) null, "pending", 1, 10);
        // approvalItemId is ACTED, mentionItemId is DISMISSED, taskItemId still PENDING
        pending.getRecords().forEach(item ->
                assertEquals("pending", item.getStatus()));
        assertTrue(pending.getRecords().stream()
                .anyMatch(item -> item.getId().equals(taskItemId)));
        assertTrue(pending.getRecords().stream()
                .noneMatch(item -> item.getId().equals(approvalItemId)));
    }

    @Test
    @Order(61)
    void listActed_showsActedItems() {
        IPage<InboxItem> acted = inboxService.listByUser(
                getTestUser().getId(), getTestTenant().getId(),
                (String) null, "acted", 1, 10);
        assertTrue(acted.getRecords().size() >= 1);
        acted.getRecords().forEach(item ->
                assertEquals("acted", item.getStatus()));
    }

    // ========== Card Payload Tests ==========

    @Test
    @Order(70)
    void cardPayload_storedAndRetrieved() {
        InboxItem item = inboxService.getItem(taskItemId, getTestUser().getId(), getTestTenant().getId());
        assertNull(item.getCardPayload(), "Task item was created without cardPayload");

        // Verify approval item's card payload
        InboxItem approval = inboxService.getItem(approvalItemId, getTestUser().getId(), getTestTenant().getId());
        assertNotNull(approval.getCardPayload());
        assertTrue(approval.getCardPayload().contains("approval"));
    }
}
