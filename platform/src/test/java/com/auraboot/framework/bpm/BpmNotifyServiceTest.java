package com.auraboot.framework.bpm;

import com.auraboot.framework.bpm.entity.BpmNotifyRecord;
import com.auraboot.framework.bpm.mapper.BpmNotifyRecordMapper;
import com.auraboot.framework.bpm.service.BpmNotifyService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for BpmNotifyService: carbon copy, urge, query, and mark-as-read.
 *
 * @author AuraBoot Team
 */
@Slf4j
@DisplayName("BPM Notify Service Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BpmNotifyServiceTest extends BaseIntegrationTest {

    @Autowired
    private BpmNotifyService bpmNotifyService;

    @Autowired
    private BpmNotifyRecordMapper notifyRecordMapper;

    // ==================== Test Cases ====================

    @Test
    @Order(1)
    @DisplayName("NOTIFY-01: Send CC to multiple recipients creates one record per recipient")
    void notify01_sendCarbonCopyToMultipleRecipients() {
        String taskId = "task-cc-multi-" + System.nanoTime();
        String processInstanceId = "proc-cc-multi-" + System.nanoTime();
        Long senderId = getTestUser().getId();
        List<Long> recipientIds = List.of(100L, 200L, 300L);

        bpmNotifyService.sendCarbonCopy(taskId, processInstanceId, senderId, recipientIds, "Please review this document");

        // Query each recipient's notifications
        for (Long recipientId : recipientIds) {
            List<BpmNotifyRecord> records = notifyRecordMapper.findByRecipient(
                    getTestTenant().getId(), recipientId, "CC");
            assertFalse(records.isEmpty(),
                    "Recipient " + recipientId + " should have at least one CC notification");
            BpmNotifyRecord found = records.stream()
                    .filter(r -> taskId.equals(r.getTaskId()))
                    .findFirst()
                    .orElse(null);
            assertNotNull(found, "CC record for recipient " + recipientId + " should exist");
            assertEquals("CC", found.getNotifyType());
        }

        log.info("NOTIFY-01 PASSED: 3 CC records created for 3 recipients");
    }

    @Test
    @Order(2)
    @DisplayName("NOTIFY-02: Send URGE using MetaContext creates record with correct tenantId")
    void notify02_sendUrgeWithMetaContext() {
        String taskId = "task-urge-ctx-" + System.nanoTime();
        String processInstanceId = "proc-urge-ctx-" + System.nanoTime();
        Long senderId = getTestUser().getId();
        Long assigneeId = 500L;

        bpmNotifyService.sendUrge(taskId, processInstanceId, senderId, assigneeId, "Please handle this task urgently");

        List<BpmNotifyRecord> records = notifyRecordMapper.findByRecipient(
                getTestTenant().getId(), assigneeId, "urge");
        BpmNotifyRecord found = records.stream()
                .filter(r -> taskId.equals(r.getTaskId()))
                .findFirst()
                .orElse(null);

        assertNotNull(found, "URGE record should be created");
        assertEquals("urge", found.getNotifyType());
        assertEquals(getTestTenant().getId(), found.getTenantId(),
                "TenantId should come from MetaContext");
        assertEquals(senderId, found.getSenderUserId());
        assertEquals(assigneeId, found.getRecipientUserId());

        log.info("NOTIFY-02 PASSED: URGE record created via MetaContext with correct tenantId={}", found.getTenantId());
    }

    @Test
    @Order(3)
    @DisplayName("NOTIFY-03: Send URGE with explicit tenantId stores the explicit value")
    void notify03_sendUrgeWithExplicitTenantId() {
        String taskId = "task-urge-explicit-" + System.nanoTime();
        String processInstanceId = "proc-urge-explicit-" + System.nanoTime();
        Long senderId = getTestUser().getId();
        Long assigneeId = 600L;
        Long explicitTenantId = getTestTenant().getId();

        bpmNotifyService.sendUrge(taskId, processInstanceId, senderId, assigneeId,
                "Scheduled urge notification", explicitTenantId);

        // Query using the explicit tenantId
        List<BpmNotifyRecord> records = notifyRecordMapper.findByRecipient(
                explicitTenantId, assigneeId, "urge");
        BpmNotifyRecord found = records.stream()
                .filter(r -> taskId.equals(r.getTaskId()))
                .findFirst()
                .orElse(null);

        assertNotNull(found, "URGE record with explicit tenantId should be created");
        assertEquals(explicitTenantId, found.getTenantId(),
                "Record tenantId should match the explicitly provided value");

        log.info("NOTIFY-03 PASSED: URGE record created with explicit tenantId={}", explicitTenantId);
    }

    @Test
    @Order(4)
    @DisplayName("NOTIFY-04: Query notifications by type returns only matching type")
    void notify04_queryNotificationsByType() {
        Long recipientId = 700L;
        String taskIdCc = "task-type-cc-" + System.nanoTime();
        String taskIdUrge = "task-type-urge-" + System.nanoTime();
        String procId = "proc-type-mix-" + System.nanoTime();
        Long senderId = getTestUser().getId();

        // Send one CC and one URGE to the same recipient
        bpmNotifyService.sendCarbonCopy(taskIdCc, procId, senderId, List.of(recipientId), "CC content");
        bpmNotifyService.sendUrge(taskIdUrge, procId, senderId, recipientId, "URGE content");

        // Query CC only
        List<BpmNotifyRecord> ccRecords = bpmNotifyService.getReceivedNotifications(recipientId, "CC");
        assertTrue(ccRecords.stream().allMatch(r -> "CC".equals(r.getNotifyType())),
                "CC query should only return CC records");
        assertTrue(ccRecords.stream().anyMatch(r -> taskIdCc.equals(r.getTaskId())),
                "CC query should contain the CC record we just created");

        // Query URGE only
        List<BpmNotifyRecord> urgeRecords = bpmNotifyService.getReceivedNotifications(recipientId, "urge");
        assertTrue(urgeRecords.stream().allMatch(r -> "urge".equals(r.getNotifyType())),
                "URGE query should only return URGE records");
        assertTrue(urgeRecords.stream().anyMatch(r -> taskIdUrge.equals(r.getTaskId())),
                "URGE query should contain the URGE record we just created");

        log.info("NOTIFY-04 PASSED: CC and URGE queries return isolated results (CC={}, URGE={})",
                ccRecords.size(), urgeRecords.size());
    }

    @Test
    @Order(5)
    @DisplayName("NOTIFY-05: markAsRead sets isRead=true and readAt timestamp")
    void notify05_markAsReadSetsFields() {
        String taskId = "task-read-" + System.nanoTime();
        String procId = "proc-read-" + System.nanoTime();
        Long recipientId = 800L;

        bpmNotifyService.sendUrge(taskId, procId, getTestUser().getId(), recipientId, "Read me");

        // Find the record to get its PID
        List<BpmNotifyRecord> records = notifyRecordMapper.findByRecipient(
                getTestTenant().getId(), recipientId, "urge");
        BpmNotifyRecord record = records.stream()
                .filter(r -> taskId.equals(r.getTaskId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Record not found"));

        assertFalse(record.getIsRead(), "isRead should be false initially");
        assertNull(record.getReadAt(), "readAt should be null initially");

        // Mark as read
        bpmNotifyService.markAsRead(record.getPid());

        // Verify
        BpmNotifyRecord updated = notifyRecordMapper.findByPid(record.getPid());
        assertTrue(updated.getIsRead(), "isRead should be true after markAsRead");
        assertNotNull(updated.getReadAt(), "readAt should be set after markAsRead");

        log.info("NOTIFY-05 PASSED: markAsRead updated isRead=true, readAt={}", updated.getReadAt());
    }

    @Test
    @Order(6)
    @DisplayName("NOTIFY-06: markAsRead is idempotent - double call does not throw")
    void notify06_markAsReadIdempotent() {
        String taskId = "task-idempotent-" + System.nanoTime();
        String procId = "proc-idempotent-" + System.nanoTime();
        Long recipientId = 900L;

        bpmNotifyService.sendUrge(taskId, procId, getTestUser().getId(), recipientId, "Double read test");

        List<BpmNotifyRecord> records = notifyRecordMapper.findByRecipient(
                getTestTenant().getId(), recipientId, "urge");
        BpmNotifyRecord record = records.stream()
                .filter(r -> taskId.equals(r.getTaskId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Record not found"));

        // Mark as read twice - should not throw
        bpmNotifyService.markAsRead(record.getPid());
        assertDoesNotThrow(() -> bpmNotifyService.markAsRead(record.getPid()),
                "Double markAsRead should not throw");

        // Verify still marked as read
        BpmNotifyRecord afterDoubleRead = notifyRecordMapper.findByPid(record.getPid());
        assertTrue(afterDoubleRead.getIsRead(), "isRead should remain true after double markAsRead");
        assertNotNull(afterDoubleRead.getReadAt(), "readAt should remain set after double markAsRead");

        log.info("NOTIFY-06 PASSED: Double markAsRead is idempotent, no errors thrown");
    }

    @Test
    @Order(7)
    @DisplayName("NOTIFY-07: CC record has all required fields populated")
    void notify07_ccRecordFieldsComplete() {
        String taskId = "task-cc-fields-" + System.nanoTime();
        String procId = "proc-cc-fields-" + System.nanoTime();
        Long senderId = getTestUser().getId();
        Long recipientId = 1000L;
        String content = "CC field completeness check";

        bpmNotifyService.sendCarbonCopy(taskId, procId, senderId, List.of(recipientId), content);

        List<BpmNotifyRecord> records = notifyRecordMapper.findByRecipient(
                getTestTenant().getId(), recipientId, "CC");
        BpmNotifyRecord record = records.stream()
                .filter(r -> taskId.equals(r.getTaskId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("CC record not found"));

        assertAll("CC record field completeness",
                () -> assertNotNull(record.getPid(), "pid should not be null"),
                () -> assertNotNull(record.getTenantId(), "tenantId should not be null"),
                () -> assertEquals(taskId, record.getTaskId(), "taskId should match"),
                () -> assertEquals(procId, record.getProcessInstanceId(), "processInstanceId should match"),
                () -> assertEquals("CC", record.getNotifyType(), "notifyType should be CC"),
                () -> assertEquals(senderId, record.getSenderUserId(), "senderUserId should match"),
                () -> assertEquals(recipientId, record.getRecipientUserId(), "recipientUserId should match"),
                () -> assertEquals(content, record.getContent(), "content should match"),
                () -> assertFalse(record.getIsRead(), "isRead should default to false"),
                () -> assertNull(record.getReadAt(), "readAt should default to null"),
                () -> assertNotNull(record.getCreatedAt(), "createdAt should not be null")
        );

        log.info("NOTIFY-07 PASSED: CC record has all required fields populated, pid={}", record.getPid());
    }

    @Test
    @Order(8)
    @DisplayName("NOTIFY-08: URGE record has all required fields populated")
    void notify08_urgeRecordFieldsComplete() {
        String taskId = "task-urge-fields-" + System.nanoTime();
        String procId = "proc-urge-fields-" + System.nanoTime();
        Long senderId = getTestUser().getId();
        Long assigneeId = 1100L;
        String content = "URGE field completeness check";

        bpmNotifyService.sendUrge(taskId, procId, senderId, assigneeId, content);

        List<BpmNotifyRecord> records = notifyRecordMapper.findByRecipient(
                getTestTenant().getId(), assigneeId, "urge");
        BpmNotifyRecord record = records.stream()
                .filter(r -> taskId.equals(r.getTaskId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("URGE record not found"));

        assertAll("URGE record field completeness",
                () -> assertNotNull(record.getPid(), "pid should not be null"),
                () -> assertNotNull(record.getTenantId(), "tenantId should not be null"),
                () -> assertEquals(taskId, record.getTaskId(), "taskId should match"),
                () -> assertEquals(procId, record.getProcessInstanceId(), "processInstanceId should match"),
                () -> assertEquals("urge", record.getNotifyType(), "notifyType should be URGE"),
                () -> assertEquals(senderId, record.getSenderUserId(), "senderUserId should match"),
                () -> assertEquals(assigneeId, record.getRecipientUserId(), "recipientUserId should match"),
                () -> assertEquals(content, record.getContent(), "content should match"),
                () -> assertFalse(record.getIsRead(), "isRead should default to false"),
                () -> assertNull(record.getReadAt(), "readAt should default to null"),
                () -> assertNotNull(record.getCreatedAt(), "createdAt should not be null")
        );

        log.info("NOTIFY-08 PASSED: URGE record has all required fields populated, pid={}", record.getPid());
    }

    @Test
    @Order(9)
    @DisplayName("NOTIFY-09: Unread count - send 3 URGEs, mark 1 read, verify 2 unread remain")
    void notify09_unreadCountByUser() {
        Long recipientId = 1200L;
        Long senderId = getTestUser().getId();
        String procId = "proc-unread-" + System.nanoTime();

        // Send 3 URGE notifications
        String taskId1 = "task-unread-1-" + System.nanoTime();
        String taskId2 = "task-unread-2-" + System.nanoTime();
        String taskId3 = "task-unread-3-" + System.nanoTime();

        bpmNotifyService.sendUrge(taskId1, procId, senderId, recipientId, "Urge 1");
        bpmNotifyService.sendUrge(taskId2, procId, senderId, recipientId, "Urge 2");
        bpmNotifyService.sendUrge(taskId3, procId, senderId, recipientId, "Urge 3");

        // Verify all 3 are unread
        List<BpmNotifyRecord> allRecords = bpmNotifyService.getReceivedNotifications(recipientId, "urge");
        List<BpmNotifyRecord> ourRecords = allRecords.stream()
                .filter(r -> procId.equals(r.getProcessInstanceId()))
                .toList();
        assertEquals(3, ourRecords.size(), "Should have 3 URGE records for this process");
        long unreadBefore = ourRecords.stream().filter(r -> !r.getIsRead()).count();
        assertEquals(3, unreadBefore, "All 3 should be unread initially");

        // Mark one as read
        bpmNotifyService.markAsRead(ourRecords.getFirst().getPid());

        // Re-query and count unread
        List<BpmNotifyRecord> afterMarkRead = bpmNotifyService.getReceivedNotifications(recipientId, "urge");
        long unreadAfter = afterMarkRead.stream()
                .filter(r -> procId.equals(r.getProcessInstanceId()))
                .filter(r -> !r.getIsRead())
                .count();
        assertEquals(2, unreadAfter, "Should have 2 unread after marking 1 as read");

        log.info("NOTIFY-09 PASSED: 3 URGEs sent, 1 marked read, unread count=2");
    }

    @Test
    @Order(10)
    @DisplayName("NOTIFY-10: CC and URGE isolation - querying one type does not return the other")
    void notify10_ccAndUrgeIsolation() {
        Long recipientId = 1300L;
        Long senderId = getTestUser().getId();
        String procId = "proc-isolation-" + System.nanoTime();
        String taskIdCc = "task-iso-cc-" + System.nanoTime();
        String taskIdUrge = "task-iso-urge-" + System.nanoTime();

        // Send 2 CCs and 1 URGE to the same recipient
        bpmNotifyService.sendCarbonCopy(taskIdCc, procId, senderId, List.of(recipientId), "CC for isolation test");
        bpmNotifyService.sendUrge(taskIdUrge, procId, senderId, recipientId, "URGE for isolation test");

        // Query CC - should not contain any URGE
        List<BpmNotifyRecord> ccResults = bpmNotifyService.getReceivedNotifications(recipientId, "CC");
        List<BpmNotifyRecord> ccForProcess = ccResults.stream()
                .filter(r -> procId.equals(r.getProcessInstanceId()))
                .toList();
        assertEquals(1, ccForProcess.size(), "Should have exactly 1 CC record for this process");
        assertTrue(ccForProcess.stream().allMatch(r -> "CC".equals(r.getNotifyType())),
                "CC query must only contain CC records");
        assertTrue(ccForProcess.stream().noneMatch(r -> "urge".equals(r.getNotifyType())),
                "CC query must not contain URGE records");

        // Query URGE - should not contain any CC
        List<BpmNotifyRecord> urgeResults = bpmNotifyService.getReceivedNotifications(recipientId, "urge");
        List<BpmNotifyRecord> urgeForProcess = urgeResults.stream()
                .filter(r -> procId.equals(r.getProcessInstanceId()))
                .toList();
        assertEquals(1, urgeForProcess.size(), "Should have exactly 1 URGE record for this process");
        assertTrue(urgeForProcess.stream().allMatch(r -> "urge".equals(r.getNotifyType())),
                "URGE query must only contain URGE records");
        assertTrue(urgeForProcess.stream().noneMatch(r -> "CC".equals(r.getNotifyType())),
                "URGE query must not contain CC records");

        log.info("NOTIFY-10 PASSED: CC and URGE queries are fully isolated (CC={}, URGE={})",
                ccForProcess.size(), urgeForProcess.size());
    }
}
