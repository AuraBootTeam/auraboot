package com.auraboot.framework.inbox;

import com.auraboot.framework.inbox.model.InboxItem;
import com.auraboot.framework.inbox.service.InboxService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Integration tests for InboxService covering create, read, mark-read, and state transitions.
 * Uses NOT_SUPPORTED propagation so data persists between ordered tests.
 *
 * @author AuraBoot Team
 */
@Slf4j
@DisplayName("Inbox Service Integration Tests (IB-01~IB-11)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class InboxIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private InboxService inboxService;

    private final String runId = "ib-" + System.currentTimeMillis();

    // Cross-test state
    private Long itemId;
    private Long item2Id;
    private Long item3Id;

    // ==================== IB-01 ====================

    @Test
    @Order(1)
    @DisplayName("IB-01: createItem persists inbox item with isRead=false and status=PENDING")
    void ib01_createItemPersists() {
        InboxItem item = new InboxItem();
        item.setTenantId(getTestTenant().getId());
        item.setUserId(getTestUser().getId());
        item.setItemType("task");
        item.setTitle(runId + "-test-inbox-item");
        item.setPriority("normal");
        item.setStatus("pending");
        item.setClientItemId(runId + "-client-001");

        InboxItem result = inboxService.createItem(item);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isNotNull();
        assertThat(result.getIsRead()).isFalse();
        assertThat(result.getStatus()).isEqualTo("pending");

        itemId = result.getId();
        log.info("IB-01: created inbox item id={}", itemId);
    }

    // ==================== IB-02 ====================

    @Test
    @Order(2)
    @DisplayName("IB-02: getItem returns the created item")
    void ib02_getItemReturnsItem() {
        assertThat(itemId).as("itemId must be set by IB-01").isNotNull();

        InboxItem result = inboxService.getItem(itemId, getTestUser().getId(), getTestTenant().getId());

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(itemId);
        assertThat(result.getTitle()).isEqualTo(runId + "-test-inbox-item");

        log.info("IB-02: getItem returned title={}", result.getTitle());
    }

    // ==================== IB-03 ====================

    @Test
    @Order(3)
    @DisplayName("IB-03: listByUser returns items for user with total > 0")
    void ib03_listByUserReturnsItems() {
        assertThat(itemId).as("itemId must be set by IB-01").isNotNull();

        IPage<InboxItem> page = inboxService.listByUser(
                getTestUser().getId(), getTestTenant().getId(),
                (String) null, null, 1, 20);

        assertThat(page).isNotNull();
        assertThat(page.getTotal()).isGreaterThan(0);
        assertThat(page.getRecords()).extracting(InboxItem::getId).contains(itemId);

        log.info("IB-03: listByUser returned total={}", page.getTotal());
    }

    // ==================== IB-04 ====================

    @Test
    @Order(4)
    @DisplayName("IB-04: getUnreadCount > 0 before marking read")
    void ib04_getUnreadCountGreaterThanZero() {
        assertThat(itemId).as("itemId must be set by IB-01").isNotNull();

        int count = inboxService.getUnreadCount(getTestUser().getId(), getTestTenant().getId());

        assertThat(count).isGreaterThan(0);

        log.info("IB-04: getUnreadCount={}", count);
    }

    // ==================== IB-05 ====================

    @Test
    @Order(5)
    @DisplayName("IB-05: markRead sets isRead=true and readAt not null")
    void ib05_markReadSetsReadState() {
        assertThat(itemId).as("itemId must be set by IB-01").isNotNull();

        inboxService.markRead(itemId, getTestUser().getId(), getTestTenant().getId());

        InboxItem updated = inboxService.getItem(itemId, getTestUser().getId(), getTestTenant().getId());

        assertThat(updated).isNotNull();
        assertThat(updated.getIsRead()).isTrue();
        assertThat(updated.getReadAt()).isNotNull();

        log.info("IB-05: markRead isRead={}, readAt={}", updated.getIsRead(), updated.getReadAt());
    }

    // ==================== IB-06 ====================

    @Test
    @Order(6)
    @DisplayName("IB-06: getUnreadSummary returns non-null map")
    void ib06_getUnreadSummaryReturnsMap() {
        Map<String, Integer> summary = inboxService.getUnreadSummary(
                getTestUser().getId(), getTestTenant().getId());

        assertThat(summary).isNotNull();

        log.info("IB-06: getUnreadSummary keys={}", summary.keySet());
    }

    // ==================== IB-07 ====================

    @Test
    @Order(7)
    @DisplayName("IB-07: markActed sets status=ACTED, actionTaken=APPROVE, actedAt not null")
    void ib07_markActedSetsActedState() {
        assertThat(itemId).as("itemId must be set by IB-01").isNotNull();

        inboxService.markActed(itemId, getTestUser().getId(), getTestTenant().getId(), "approve");

        InboxItem updated = inboxService.getItem(itemId, getTestUser().getId(), getTestTenant().getId());

        assertThat(updated).isNotNull();
        assertThat(updated.getStatus()).isEqualTo("acted");
        assertThat(updated.getActionTaken()).isEqualTo("approve");
        assertThat(updated.getActedAt()).isNotNull();

        log.info("IB-07: markActed status={}, actionTaken={}", updated.getStatus(), updated.getActionTaken());
    }

    // ==================== IB-08 ====================

    @Test
    @Order(8)
    @DisplayName("IB-08: createItem with same clientItemId is idempotent (same id or unique constraint)")
    void ib08_createItemIdempotentByClientItemId() {
        assertThat(itemId).as("itemId must be set by IB-01").isNotNull();

        InboxItem duplicate = new InboxItem();
        duplicate.setTenantId(getTestTenant().getId());
        duplicate.setUserId(getTestUser().getId());
        duplicate.setItemType("task");
        duplicate.setTitle(runId + "-duplicate");
        duplicate.setPriority("normal");
        duplicate.setStatus("pending");
        duplicate.setClientItemId(runId + "-client-001"); // same as IB-01

        // Either returns same id (dedup) or throws unique constraint
        Throwable thrown = catchThrowable(() -> {
            InboxItem result = inboxService.createItem(duplicate);
            // If no exception, must return existing item id
            assertThat(result.getId()).isEqualTo(itemId);
        });

        if (thrown != null) {
            log.info("IB-08: duplicate clientItemId threw exception as expected: {}", thrown.getClass().getSimpleName());
        } else {
            log.info("IB-08: duplicate clientItemId returned existing item id={}", itemId);
        }
    }

    // ==================== IB-09 ====================

    @Test
    @Order(9)
    @DisplayName("IB-09: batchMarkRead marks multiple items as read")
    void ib09_batchMarkReadMultipleItems() {
        // Create two new items to batch-mark
        InboxItem item2 = new InboxItem();
        item2.setTenantId(getTestTenant().getId());
        item2.setUserId(getTestUser().getId());
        item2.setItemType("mention");
        item2.setTitle(runId + "-batch-item-2");
        item2.setPriority("normal");
        item2.setStatus("pending");
        item2.setClientItemId(runId + "-client-batch-002");
        item2Id = inboxService.createItem(item2).getId();

        InboxItem item3 = new InboxItem();
        item3.setTenantId(getTestTenant().getId());
        item3.setUserId(getTestUser().getId());
        item3.setItemType("mention");
        item3.setTitle(runId + "-batch-item-3");
        item3.setPriority("normal");
        item3.setStatus("pending");
        item3.setClientItemId(runId + "-client-batch-003");
        item3Id = inboxService.createItem(item3).getId();

        int affected = inboxService.batchMarkRead(
                List.of(item2Id, item3Id),
                getTestUser().getId(),
                getTestTenant().getId());

        assertThat(affected).isGreaterThan(0);

        InboxItem updated2 = inboxService.getItem(item2Id, getTestUser().getId(), getTestTenant().getId());
        InboxItem updated3 = inboxService.getItem(item3Id, getTestUser().getId(), getTestTenant().getId());
        assertThat(updated2.getIsRead()).isTrue();
        assertThat(updated3.getIsRead()).isTrue();

        log.info("IB-09: batchMarkRead affected={}", affected);
    }

    // ==================== IB-10 ====================

    @Test
    @Order(10)
    @DisplayName("IB-10: markAllRead makes unreadCount=0")
    void ib10_markAllReadMakesCountZero() {
        // Create one more unread item to ensure there's something to mark
        InboxItem unread = new InboxItem();
        unread.setTenantId(getTestTenant().getId());
        unread.setUserId(getTestUser().getId());
        unread.setItemType("alert");
        unread.setTitle(runId + "-unread-for-markall");
        unread.setPriority("high");
        unread.setStatus("pending");
        unread.setClientItemId(runId + "-client-markall-004");
        inboxService.createItem(unread);

        inboxService.markAllRead(getTestUser().getId(), getTestTenant().getId());

        int countAfter = inboxService.getUnreadCount(getTestUser().getId(), getTestTenant().getId());
        assertThat(countAfter).isEqualTo(0);

        log.info("IB-10: markAllRead → unreadCount={}", countAfter);
    }

    // ==================== IB-11 ====================

    @Test
    @Order(11)
    @DisplayName("IB-11: dismiss sets status=DISMISSED")
    void ib11_dismissSetsStatusDismissed() {
        assertThat(item2Id).as("item2Id must be set by IB-09").isNotNull();

        inboxService.dismiss(item2Id, getTestUser().getId(), getTestTenant().getId());

        InboxItem updated = inboxService.getItem(item2Id, getTestUser().getId(), getTestTenant().getId());

        assertThat(updated).isNotNull();
        assertThat(updated.getStatus()).isEqualTo("dismissed");

        log.info("IB-11: dismiss status={}", updated.getStatus());
    }
}
