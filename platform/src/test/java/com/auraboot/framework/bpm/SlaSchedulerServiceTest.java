package com.auraboot.framework.bpm;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.entity.BpmNotifyRecord;
import com.auraboot.framework.bpm.entity.SlaConfigEntity;
import com.auraboot.framework.bpm.entity.SlaRecordEntity;
import com.auraboot.framework.bpm.mapper.BpmNotifyRecordMapper;
import com.auraboot.framework.bpm.mapper.SlaConfigMapper;
import com.auraboot.framework.bpm.mapper.SlaRecordMapper;
import com.auraboot.framework.bpm.service.BpmNotifyService;
import com.auraboot.framework.bpm.service.SlaRecordService;
import com.auraboot.framework.bpm.service.SlaSchedulerService;
import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for SlaSchedulerService.
 * Covers the scheduled scan loop, warning threshold triggers,
 * overdue detection, action execution (NOTIFY, ESCALATE, AUTO_TRANSFER, AUTO_TERMINATE),
 * threshold parsing, recipient resolution, and warning history accumulation.
 *
 * @author AuraBoot Team
 */
@Slf4j
@DisplayName("BPM SLA Scheduler Service Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SlaSchedulerServiceTest extends BaseIntegrationTest {

    @Autowired
    private SlaSchedulerService slaSchedulerService;

    @Autowired
    private SlaRecordService slaRecordService;

    @Autowired
    private SlaConfigMapper slaConfigMapper;

    @Autowired
    private SlaRecordMapper slaRecordMapper;

    @Autowired
    private BpmNotifyRecordMapper notifyRecordMapper;

    @Autowired
    private BpmNotifyService bpmNotifyService;

    // ==================== Helper Methods ====================

    /**
     * Insert an SLA record directly via mapper with controlled start/deadline times.
     */
    private SlaRecordEntity insertRecord(String slaConfigId, String processInstanceId,
                                          String taskId, Instant startTime, Instant deadlineTime,
                                          String status, int warningLevel, long totalPausedMs) {
        SlaRecordEntity record = SlaRecordEntity.builder()
                .pid(UlidGenerator.generate())
                .tenantId(getTestTenant().getId())
                .slaConfigId(slaConfigId)
                .processInstanceId(processInstanceId)
                .taskId(taskId)
                .startTime(startTime)
                .deadlineTime(deadlineTime)
                .status(status)
                .currentWarningLevel(warningLevel)
                .totalPausedMs(totalPausedMs)
                .warningHistory(new ArrayList<>())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        slaRecordMapper.insert(record);
        return record;
    }

    private SlaRecordEntity reloadRecord(String pid) {
        return slaRecordMapper.findByPid(pid, getTestTenant().getId());
    }

    /**
     * scanSlaRecords() sets/clears MetaContext per-record internally.
     * After it returns, MetaContext is cleared, so we must re-establish
     * the test tenant context for subsequent assertions.
     */
    private void scanAndRestoreContext() {
        slaSchedulerService.scanSlaRecords();
        MetaContext.setContext(
                getTestTenant().getId(),
                getTestUser().getId(),
                getTestUser().getPid(),
                getTestUser().getUserName()
        );
    }

    // ==================== Test Cases ====================

    @Test
    @Order(1)
    @DisplayName("SLA-SCHED-01: Scan empty records - no active records does not throw")
    void slaSched01_scanEmptyRecordsDoesNotThrow() {
        // No records inserted — scanSlaRecords should simply return without error
        assertDoesNotThrow(() -> scanAndRestoreContext());

        log.info("SLA-SCHED-01 PASSED: scanSlaRecords with no active records completes without error");
    }

    @Test
    @Order(2)
    @DisplayName("SLA-SCHED-02: Warning threshold triggers NOTIFY action and creates URGE notification")
    void slaSched02_warningThresholdTriggersNotify() {
        // Config: 75% threshold → NOTIFY → userId:999
        List<Map<String, Object>> rules = List.of(
                Map.of("threshold", "75%", "action", "notify", "recipients", "userId:999")
        );
        SlaConfigEntity config = BpmTestHelper.createSlaConfig(
                slaConfigMapper, getTestTenant().getId(), "proc-sched-02", "pt100m", rules);

        // Record: started 80 min ago, deadline in 20 min (total=100min, elapsed=80min → progress=80%)
        SlaRecordEntity record = insertRecord(
                config.getPid(), "proc-sla-02", "task-sla-02",
                Instant.now().minus(Duration.ofMinutes(80)),
                Instant.now().plus(Duration.ofMinutes(20)),
                "running", 0, 0L);

        scanAndRestoreContext();

        // Verify record updated
        SlaRecordEntity reloaded = reloadRecord(record.getPid());
        assertNotNull(reloaded, "Record should exist after scan");
        assertEquals("warning", reloaded.getStatus(), "Status should be WARNING after threshold breach");
        assertEquals(1, reloaded.getCurrentWarningLevel(), "Warning level should be 1");

        // Verify URGE notification created for userId 999
        List<BpmNotifyRecord> notifications = notifyRecordMapper.findByRecipient(
                getTestTenant().getId(), 999L, "urge");
        assertFalse(notifications.isEmpty(), "URGE notification should be created for userId 999");

        log.info("SLA-SCHED-02 PASSED: Warning threshold triggered NOTIFY, notification created");
    }

    @Test
    @Order(3)
    @DisplayName("SLA-SCHED-03: Overdue detection - progress >= 100% sets status OVERDUE")
    void slaSched03_overdueDetection() {
        List<Map<String, Object>> rules = List.of(
                Map.of("threshold", "75%", "action", "notify", "recipients", "userId:998")
        );
        SlaConfigEntity config = BpmTestHelper.createSlaConfig(
                slaConfigMapper, getTestTenant().getId(), "proc-sched-03", "pt60m", rules);

        // Record: started 120 min ago, deadline was 60 min ago (well past deadline)
        SlaRecordEntity record = insertRecord(
                config.getPid(), "proc-sla-03", "task-sla-03",
                Instant.now().minus(Duration.ofMinutes(120)),
                Instant.now().minus(Duration.ofMinutes(60)),
                "running", 0, 0L);

        scanAndRestoreContext();

        SlaRecordEntity reloaded = reloadRecord(record.getPid());
        assertNotNull(reloaded, "Record should exist after scan");
        assertEquals("overdue", reloaded.getStatus(), "Status should be OVERDUE when past deadline");

        log.info("SLA-SCHED-03 PASSED: Overdue detection sets status to OVERDUE");
    }

    @Test
    @Order(4)
    @DisplayName("SLA-SCHED-04: Multi-level warnings - triggers levels 1 and 2 but not level 3")
    void slaSched04_multiLevelWarnings() {
        // 3 rules at 50%, 75%, 100%
        List<Map<String, Object>> rules = List.of(
                Map.of("threshold", "50%", "action", "notify", "recipients", "userId:997"),
                Map.of("threshold", "75%", "action", "notify", "recipients", "userId:996"),
                Map.of("threshold", "100%", "action", "notify", "recipients", "userId:995")
        );
        SlaConfigEntity config = BpmTestHelper.createSlaConfig(
                slaConfigMapper, getTestTenant().getId(), "proc-sched-04", "pt100m", rules);

        // Record: progress ~80% (started 80 min ago, deadline in 20 min)
        SlaRecordEntity record = insertRecord(
                config.getPid(), "proc-sla-04", "task-sla-04",
                Instant.now().minus(Duration.ofMinutes(80)),
                Instant.now().plus(Duration.ofMinutes(20)),
                "running", 0, 0L);

        scanAndRestoreContext();

        SlaRecordEntity reloaded = reloadRecord(record.getPid());
        assertNotNull(reloaded, "Record should exist after scan");
        // Level 1 (50%) and level 2 (75%) should be triggered, but not level 3 (100%)
        assertEquals(2, reloaded.getCurrentWarningLevel(),
                "Warning level should be 2 (50% and 75% triggered, not 100%)");
        assertEquals("warning", reloaded.getStatus(), "Status should be WARNING (not OVERDUE, since < 100%)");

        log.info("SLA-SCHED-04 PASSED: Multi-level warnings triggered correctly, level={}", reloaded.getCurrentWarningLevel());
    }

    @Test
    @Order(5)
    @DisplayName("SLA-SCHED-05: Skip already triggered level - does not re-trigger level 1")
    void slaSched05_skipAlreadyTriggeredLevel() {
        List<Map<String, Object>> rules = List.of(
                Map.of("threshold", "50%", "action", "notify", "recipients", "userId:994")
        );
        SlaConfigEntity config = BpmTestHelper.createSlaConfig(
                slaConfigMapper, getTestTenant().getId(), "proc-sched-05", "pt100m", rules);

        // Record: already at warning level 1, progress ~80%
        SlaRecordEntity record = insertRecord(
                config.getPid(), "proc-sla-05", "task-sla-05",
                Instant.now().minus(Duration.ofMinutes(80)),
                Instant.now().plus(Duration.ofMinutes(20)),
                "warning", 1, 0L);

        // Count notifications before scan
        List<BpmNotifyRecord> before = notifyRecordMapper.findByRecipient(
                getTestTenant().getId(), 994L, "urge");
        int countBefore = before.size();

        scanAndRestoreContext();

        // Verify level not re-triggered — no new notification
        List<BpmNotifyRecord> after = notifyRecordMapper.findByRecipient(
                getTestTenant().getId(), 994L, "urge");
        assertEquals(countBefore, after.size(),
                "No new notification should be created for already triggered level");

        SlaRecordEntity reloaded = reloadRecord(record.getPid());
        assertEquals(1, reloaded.getCurrentWarningLevel(), "Warning level should remain 1");

        log.info("SLA-SCHED-05 PASSED: Already triggered level not re-triggered");
    }

    @Test
    @Order(6)
    @DisplayName("SLA-SCHED-06: Skip paused records - PAUSED record status unchanged")
    void slaSched06_skipPausedRecords() {
        List<Map<String, Object>> rules = List.of(
                Map.of("threshold", "50%", "action", "notify", "recipients", "userId:993")
        );
        SlaConfigEntity config = BpmTestHelper.createSlaConfig(
                slaConfigMapper, getTestTenant().getId(), "proc-sched-06", "pt100m", rules);

        // PAUSED record with progress that would trigger warning if not paused
        SlaRecordEntity record = SlaRecordEntity.builder()
                .pid(UlidGenerator.generate())
                .tenantId(getTestTenant().getId())
                .slaConfigId(config.getPid())
                .processInstanceId("proc-sla-06")
                .taskId("task-sla-06")
                .startTime(Instant.now().minus(Duration.ofMinutes(80)))
                .deadlineTime(Instant.now().plus(Duration.ofMinutes(20)))
                .status("paused")
                .currentWarningLevel(0)
                .pausedAt(Instant.now().minus(Duration.ofMinutes(10)))
                .totalPausedMs(0L)
                .warningHistory(new ArrayList<>())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        slaRecordMapper.insert(record);

        scanAndRestoreContext();

        SlaRecordEntity reloaded = reloadRecord(record.getPid());
        assertNotNull(reloaded, "Paused record should still exist");
        assertEquals("paused", reloaded.getStatus(), "PAUSED status should not change during scan");
        assertEquals(0, reloaded.getCurrentWarningLevel(), "Warning level should remain 0 for paused record");

        log.info("SLA-SCHED-06 PASSED: Paused record skipped by scheduler");
    }

    @Test
    @Order(7)
    @DisplayName("SLA-SCHED-07: NOTIFY action creates URGE notification record")
    void slaSched07_notifyCreatesUrgeRecord() {
        Long recipientId = 992L;
        List<Map<String, Object>> rules = List.of(
                Map.of("threshold", "50%", "action", "notify", "recipients", "userId:" + recipientId)
        );
        SlaConfigEntity config = BpmTestHelper.createSlaConfig(
                slaConfigMapper, getTestTenant().getId(), "proc-sched-07", "pt100m", rules);

        // Progress ~80% → triggers 50% rule
        SlaRecordEntity record = insertRecord(
                config.getPid(), "proc-sla-07", "task-sla-07",
                Instant.now().minus(Duration.ofMinutes(80)),
                Instant.now().plus(Duration.ofMinutes(20)),
                "running", 0, 0L);

        scanAndRestoreContext();

        List<BpmNotifyRecord> notifications = notifyRecordMapper.findByRecipient(
                getTestTenant().getId(), recipientId, "urge");
        assertFalse(notifications.isEmpty(), "URGE notification should exist");

        BpmNotifyRecord notification = notifications.get(0);
        assertEquals("urge", notification.getNotifyType(), "Notify type should be URGE");
        assertEquals(recipientId, notification.getRecipientUserId(), "Recipient should match");
        assertNotNull(notification.getContent(), "Content should not be null");
        assertTrue(notification.getContent().contains("SLA warning"),
                "Content should contain SLA warning message");

        log.info("SLA-SCHED-07 PASSED: NOTIFY action created URGE notification");
    }

    @Test
    @Order(8)
    @DisplayName("SLA-SCHED-08: ESCALATE action creates URGE with ESCALATION content")
    void slaSched08_escalateCreatesEscalationNotification() {
        Long recipientId = 991L;
        List<Map<String, Object>> rules = List.of(
                Map.of("threshold", "50%", "action", "escalate", "recipients", "userId:" + recipientId)
        );
        SlaConfigEntity config = BpmTestHelper.createSlaConfig(
                slaConfigMapper, getTestTenant().getId(), "proc-sched-08", "pt100m", rules);

        SlaRecordEntity record = insertRecord(
                config.getPid(), "proc-sla-08", "task-sla-08",
                Instant.now().minus(Duration.ofMinutes(80)),
                Instant.now().plus(Duration.ofMinutes(20)),
                "running", 0, 0L);

        scanAndRestoreContext();

        List<BpmNotifyRecord> notifications = notifyRecordMapper.findByRecipient(
                getTestTenant().getId(), recipientId, "urge");
        assertFalse(notifications.isEmpty(), "URGE notification should exist for ESCALATE action");

        BpmNotifyRecord notification = notifications.get(0);
        assertTrue(notification.getContent().toLowerCase().contains("escalation"),
                "ESCALATE content should contain 'escalation', got: " + notification.getContent());

        log.info("SLA-SCHED-08 PASSED: ESCALATE action created notification with ESCALATION content");
    }

    @Test
    @Order(9)
    @DisplayName("SLA-SCHED-09: AUTO_TRANSFER action does not crash (engine may not be available)")
    void slaSched09_autoTransferDoesNotCrash() {
        List<Map<String, Object>> rules = List.of(
                Map.of("threshold", "50%", "action", "auto_transfer", "recipients", "userId:990")
        );
        SlaConfigEntity config = BpmTestHelper.createSlaConfig(
                slaConfigMapper, getTestTenant().getId(), "proc-sched-09", "pt100m", rules);

        // Use a non-existent task ID — AUTO_TRANSFER catches exceptions internally
        SlaRecordEntity record = insertRecord(
                config.getPid(), "proc-sla-09", "task-nonexistent-09",
                Instant.now().minus(Duration.ofMinutes(80)),
                Instant.now().plus(Duration.ofMinutes(20)),
                "running", 0, 0L);

        // Should not throw — AUTO_TRANSFER has try/catch internally
        assertDoesNotThrow(() -> scanAndRestoreContext(),
                "AUTO_TRANSFER should not crash even if task does not exist in engine");

        // Verify the warning was still recorded even though transfer failed
        SlaRecordEntity reloaded = reloadRecord(record.getPid());
        assertEquals(1, reloaded.getCurrentWarningLevel(), "Warning level should be updated despite transfer failure");

        log.info("SLA-SCHED-09 PASSED: AUTO_TRANSFER action did not crash");
    }

    @Test
    @Order(10)
    @DisplayName("SLA-SCHED-10: AUTO_TERMINATE action does not crash (process may not exist)")
    void slaSched10_autoTerminateDoesNotCrash() {
        List<Map<String, Object>> rules = List.of(
                Map.of("threshold", "50%", "action", "auto_terminate", "recipients", "assignee")
        );
        SlaConfigEntity config = BpmTestHelper.createSlaConfig(
                slaConfigMapper, getTestTenant().getId(), "proc-sched-10", "pt100m", rules);

        // Use a non-existent process instance — AUTO_TERMINATE catches exceptions internally
        SlaRecordEntity record = insertRecord(
                config.getPid(), "proc-nonexistent-10", "task-sla-10",
                Instant.now().minus(Duration.ofMinutes(80)),
                Instant.now().plus(Duration.ofMinutes(20)),
                "running", 0, 0L);

        // Should not throw — AUTO_TERMINATE has try/catch internally
        assertDoesNotThrow(() -> scanAndRestoreContext(),
                "AUTO_TERMINATE should not crash even if process does not exist");

        SlaRecordEntity reloaded = reloadRecord(record.getPid());
        assertEquals(1, reloaded.getCurrentWarningLevel(), "Warning level should be updated despite terminate failure");

        log.info("SLA-SCHED-10 PASSED: AUTO_TERMINATE action did not crash");
    }

    @Test
    @Order(11)
    @DisplayName("SLA-SCHED-11: Threshold parsing - decimal format '0.75' triggers at 80% progress")
    void slaSched11_thresholdParsingDecimalFormat() {
        // Use decimal threshold "0.75" instead of "75%"
        List<Map<String, Object>> rules = List.of(
                Map.of("threshold", "0.75", "action", "notify", "recipients", "userId:989")
        );
        SlaConfigEntity config = BpmTestHelper.createSlaConfig(
                slaConfigMapper, getTestTenant().getId(), "proc-sched-11", "pt100m", rules);

        // Progress ~80%
        SlaRecordEntity record = insertRecord(
                config.getPid(), "proc-sla-11", "task-sla-11",
                Instant.now().minus(Duration.ofMinutes(80)),
                Instant.now().plus(Duration.ofMinutes(20)),
                "running", 0, 0L);

        scanAndRestoreContext();

        SlaRecordEntity reloaded = reloadRecord(record.getPid());
        assertEquals(1, reloaded.getCurrentWarningLevel(),
                "Decimal threshold '0.75' should trigger at 80% progress");

        List<BpmNotifyRecord> notifications = notifyRecordMapper.findByRecipient(
                getTestTenant().getId(), 989L, "urge");
        assertFalse(notifications.isEmpty(), "Notification should be created for decimal threshold trigger");

        log.info("SLA-SCHED-11 PASSED: Decimal threshold '0.75' parsed correctly and triggered");
    }

    @Test
    @Order(12)
    @DisplayName("SLA-SCHED-12: recipients='userId:123' sends URGE to user 123")
    void slaSched12_recipientUserIdResolution() {
        Long targetUserId = 123L;
        List<Map<String, Object>> rules = List.of(
                Map.of("threshold", "50%", "action", "notify", "recipients", "userId:" + targetUserId)
        );
        SlaConfigEntity config = BpmTestHelper.createSlaConfig(
                slaConfigMapper, getTestTenant().getId(), "proc-sched-12", "pt100m", rules);

        SlaRecordEntity record = insertRecord(
                config.getPid(), "proc-sla-12", "task-sla-12",
                Instant.now().minus(Duration.ofMinutes(80)),
                Instant.now().plus(Duration.ofMinutes(20)),
                "running", 0, 0L);

        scanAndRestoreContext();

        List<BpmNotifyRecord> notifications = notifyRecordMapper.findByRecipient(
                getTestTenant().getId(), targetUserId, "urge");
        assertFalse(notifications.isEmpty(), "URGE should be sent to userId:123");
        assertEquals(targetUserId, notifications.get(0).getRecipientUserId(),
                "Recipient user ID should be 123");

        log.info("SLA-SCHED-12 PASSED: URGE sent to userId:123 via explicit recipient");
    }

    @Test
    @Order(13)
    @DisplayName("SLA-SCHED-13: NOTIFY with 'starter' recipient - empty list, no notification, no crash")
    void slaSched13_starterRecipientEmptyList() {
        List<Map<String, Object>> rules = List.of(
                Map.of("threshold", "50%", "action", "notify", "recipients", "starter")
        );
        SlaConfigEntity config = BpmTestHelper.createSlaConfig(
                slaConfigMapper, getTestTenant().getId(), "proc-sched-13", "pt100m", rules);

        SlaRecordEntity record = insertRecord(
                config.getPid(), "proc-sla-13", "task-sla-13",
                Instant.now().minus(Duration.ofMinutes(80)),
                Instant.now().plus(Duration.ofMinutes(20)),
                "running", 0, 0L);

        // Should not throw — starter resolves to empty list, no notification sent
        assertDoesNotThrow(() -> scanAndRestoreContext(),
                "NOTIFY with 'starter' recipient should not crash");

        // Warning level should still be updated (the action executed, just no recipients)
        SlaRecordEntity reloaded = reloadRecord(record.getPid());
        assertEquals(1, reloaded.getCurrentWarningLevel(),
                "Warning level should still be updated even with empty recipient list");

        log.info("SLA-SCHED-13 PASSED: 'starter' recipient resolves to empty, no crash");
    }

    @Test
    @Order(14)
    @DisplayName("SLA-SCHED-14: Warning history JSONB accumulates entries for multiple rules")
    void slaSched14_warningHistoryAccumulates() {
        // 2 rules: 50% and 75% — both should trigger at ~80% progress
        List<Map<String, Object>> rules = List.of(
                Map.of("threshold", "50%", "action", "notify", "recipients", "userId:988"),
                Map.of("threshold", "75%", "action", "notify", "recipients", "userId:987")
        );
        SlaConfigEntity config = BpmTestHelper.createSlaConfig(
                slaConfigMapper, getTestTenant().getId(), "proc-sched-14", "pt100m", rules);

        SlaRecordEntity record = insertRecord(
                config.getPid(), "proc-sla-14", "task-sla-14",
                Instant.now().minus(Duration.ofMinutes(80)),
                Instant.now().plus(Duration.ofMinutes(20)),
                "running", 0, 0L);

        scanAndRestoreContext();

        SlaRecordEntity reloaded = reloadRecord(record.getPid());
        assertNotNull(reloaded, "Record should exist after scan");
        assertNotNull(reloaded.getWarningHistory(), "Warning history should not be null");
        assertEquals(2, reloaded.getWarningHistory().size(),
                "Warning history should have 2 entries (one per triggered rule)");

        // Verify first entry is level 1
        Map<String, Object> entry1 = reloaded.getWarningHistory().get(0);
        assertEquals(1, ((Number) entry1.get("level")).intValue(), "First entry should be level 1");
        assertEquals("50%", entry1.get("threshold").toString(), "First entry threshold should be 50%");

        // Verify second entry is level 2
        Map<String, Object> entry2 = reloaded.getWarningHistory().get(1);
        assertEquals(2, ((Number) entry2.get("level")).intValue(), "Second entry should be level 2");
        assertEquals("75%", entry2.get("threshold").toString(), "Second entry threshold should be 75%");

        assertEquals(2, reloaded.getCurrentWarningLevel(), "Final warning level should be 2");

        log.info("SLA-SCHED-14 PASSED: Warning history accumulated {} entries", reloaded.getWarningHistory().size());
    }
}
