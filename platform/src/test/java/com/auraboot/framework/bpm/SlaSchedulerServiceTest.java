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
import com.auraboot.framework.eventpolicy.executor.EventPolicyActionRetryService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.webhook.entity.WebhookSubscription;
import com.auraboot.framework.webhook.mapper.WebhookSubscriptionMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

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

    @Autowired
    private WebhookSubscriptionMapper webhookSubscriptionMapper;

    @Autowired
    private EventPolicyActionRetryService eventPolicyActionRetryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    @Test
    @Order(15)
    @DisplayName("SLA-SCHED-15: SLA_TIMEOUT actionPolicy executes through unified PolicyExecutor")
    void slaSched15_timeoutActionPolicyExecutesThroughUnifiedPolicyExecutor() {
        Long recipientId = getTestUser().getId();
        SlaConfigEntity config = BpmTestHelper.createSlaConfig(
                slaConfigMapper, getTestTenant().getId(), "proc-sched-15", "pt30m", List.of());
        config.setActionPolicy(Map.of(
                "trigger", "SLA_TIMEOUT",
                "actions", List.of(Map.of(
                        "type", "NOTIFY",
                        "target", "USER:" + recipientId,
                        "order", 10,
                        "payload", Map.of(
                                "title", "SLA 已超时 ${sla.recordPid}",
                                "content", "流程 ${process.instanceId} 的节点 ${task.nodeId} 已超过 SLA"),
                        "idempotencyKeyTemplate", "${sla.recordPid}:timeout:NOTIFY"))));
        slaConfigMapper.updateById(config);

        SlaRecordEntity record = insertRecord(
                config.getPid(), "proc-sla-15", "task-sla-15",
                Instant.now().minus(Duration.ofMinutes(90)),
                Instant.now().minus(Duration.ofMinutes(30)),
                "running", 0, 0L);
        String idempotencyKey = record.getPid() + ":timeout:NOTIFY";

        scanAndRestoreContext();

        SlaRecordEntity reloaded = reloadRecord(record.getPid());
        assertNotNull(reloaded, "Record should exist after scan");
        assertEquals("overdue", reloaded.getStatus(), "Status should be OVERDUE when action policy fires");

        Integer execRows = jdbcTemplate.queryForObject(
                "select count(*) from ab_drt_policy_exec_log where tenant_id = ? and idempotency_key = ? and status = 'SUCCESS'",
                Integer.class, getTestTenant().getId(), idempotencyKey);
        assertEquals(1, execRows, "Unified PolicyExecutor should persist exactly one successful SLA action log");

        Map<String, Object> logRow = jdbcTemplate.queryForMap(
                "select policy_code, rule_code, action_type, result_payload from ab_drt_policy_exec_log "
                        + "where tenant_id = ? and idempotency_key = ?",
                getTestTenant().getId(), idempotencyKey);
        assertEquals("SLA_TIMEOUT:" + config.getPid(), logRow.get("policy_code"));
        assertEquals("SLA_TIMEOUT", logRow.get("rule_code"));
        assertEquals("NOTIFY", logRow.get("action_type"));
        assertTrue(String.valueOf(logRow.get("result_payload")).contains("\"sentCount\""),
                "Execution log should include structured action result payload");

        Map<String, Object> notification = jdbcTemplate.queryForMap("""
                select title, content, source_type, source_id
                from ab_notification
                where tenant_id = ? and user_id = ? and title = ?
                order by created_at desc
                limit 1
                """, getTestTenant().getId(), recipientId, "SLA 已超时 " + record.getPid());
        assertEquals("SLA 已超时 " + record.getPid(), notification.get("title"));
        assertEquals("EVENT_POLICY", notification.get("source_type"));
        assertEquals("SLA_TIMEOUT", notification.get("source_id"));

        scanAndRestoreContext();
        Integer execRowsAfterReplay = jdbcTemplate.queryForObject(
                "select count(*) from ab_drt_policy_exec_log where tenant_id = ? and idempotency_key = ?",
                Integer.class, getTestTenant().getId(), idempotencyKey);
        assertEquals(1, execRowsAfterReplay, "SLA timeout action should be idempotent across scheduler scans");

        log.info("SLA-SCHED-15 PASSED: actionPolicy executed via PolicyExecutor with idempotency key={}",
                idempotencyKey);
    }

    @Test
    @Order(16)
    @DisplayName("SLA-SCHED-16: SLA_TIMEOUT SEND_SMS records provider-unavailable failure evidence")
    void slaSched16_timeoutSendSmsRecordsProviderUnavailableFailureEvidence() {
        SlaConfigEntity config = BpmTestHelper.createSlaConfig(
                slaConfigMapper, getTestTenant().getId(), "proc-sched-16", "pt30m", List.of());
        config.setActionPolicy(Map.of(
                "trigger", "SLA_TIMEOUT",
                "actions", List.of(Map.of(
                        "type", "SEND_SMS",
                        "target", "PHONE:+8613800138000",
                        "order", 10,
                        "payload", Map.of(
                                "template", "sla_timeout",
                                "content", "SLA ${sla.recordPid} 已超时"),
                        "idempotencyKeyTemplate", "${sla.recordPid}:timeout:SEND_SMS"))));
        slaConfigMapper.updateById(config);

        SlaRecordEntity record = insertRecord(
                config.getPid(), "proc-sla-16", "task-sla-16",
                Instant.now().minus(Duration.ofMinutes(90)),
                Instant.now().minus(Duration.ofMinutes(30)),
                "running", 0, 0L);
        String idempotencyKey = record.getPid() + ":timeout:SEND_SMS";

        scanAndRestoreContext();

        SlaRecordEntity reloaded = reloadRecord(record.getPid());
        assertNotNull(reloaded, "Record should exist after scan");
        assertEquals("overdue", reloaded.getStatus(), "SLA record still becomes overdue when SMS action fails");

        Map<String, Object> logRow = jdbcTemplate.queryForMap(
                "select policy_code, rule_code, action_type, status, error_message from ab_drt_policy_exec_log "
                        + "where tenant_id = ? and idempotency_key = ?",
                getTestTenant().getId(), idempotencyKey);
        assertEquals("SLA_TIMEOUT:" + config.getPid(), logRow.get("policy_code"));
        assertEquals("SLA_TIMEOUT", logRow.get("rule_code"));
        assertEquals("SEND_SMS", logRow.get("action_type"));
        assertEquals("FAILED", logRow.get("status"));
        assertTrue(String.valueOf(logRow.get("error_message")).contains("No real SMS sender available"),
                "SLA Trace should preserve SMS provider unavailable reason");

        log.info("SLA-SCHED-16 PASSED: SMS provider-unavailable failure recorded for SLA action policy");
    }

    @Test
    @Order(17)
    @DisplayName("SLA-SCHED-17: SLA_TIMEOUT CREATE_TASK creates inbox task and action evidence")
    void slaSched17_timeoutCreateTaskCreatesInboxTaskAndActionEvidence() {
        Long assigneeId = getTestUser().getId();
        SlaConfigEntity config = BpmTestHelper.createSlaConfig(
                slaConfigMapper, getTestTenant().getId(), "wd_leave_request", "pt30m", List.of());
        config.setTargetType("RECORD");
        config.setTargetKey("wd_leave_request");
        config.setModelCode("wd_leave_request");
        config.setActionPolicy(Map.of(
                "trigger", "SLA_TIMEOUT",
                "actions", List.of(Map.of(
                        "type", "CREATE_TASK",
                        "target", "USER:" + assigneeId,
                        "order", 10,
                        "payload", Map.of(
                                "title", "SLA 待办 ${sla.recordPid}",
                                "message", "记录 ${record.recordPid} 已超时",
                                "priority", "urgent"),
                        "idempotencyKeyTemplate", "${sla.recordPid}:timeout:CREATE_TASK"))));
        slaConfigMapper.updateById(config);

        SlaRecordEntity record = insertRecord(
                config.getPid(), "leave-sla-17", "task-sla-17",
                Instant.now().minus(Duration.ofMinutes(90)),
                Instant.now().minus(Duration.ofMinutes(30)),
                "running", 0, 0L);
        String idempotencyKey = record.getPid() + ":timeout:CREATE_TASK";

        scanAndRestoreContext();

        SlaRecordEntity reloaded = reloadRecord(record.getPid());
        assertNotNull(reloaded, "Record should exist after scan");
        assertEquals("overdue", reloaded.getStatus(), "SLA record becomes overdue when task action fires");

        Map<String, Object> logRow = jdbcTemplate.queryForMap(
                "select policy_code, rule_code, action_type, status, result_payload from ab_drt_policy_exec_log "
                        + "where tenant_id = ? and idempotency_key = ?",
                getTestTenant().getId(), idempotencyKey);
        assertEquals("SLA_TIMEOUT:" + config.getPid(), logRow.get("policy_code"));
        assertEquals("SLA_TIMEOUT", logRow.get("rule_code"));
        assertEquals("CREATE_TASK", logRow.get("action_type"));
        assertEquals("SUCCESS", logRow.get("status"));
        String payload = String.valueOf(logRow.get("result_payload"));
        assertTrue(payload.contains("\"createdCount\""), "SLA task action log should include created task count");
        assertTrue(payload.contains("\"inboxItemIds\""), "SLA task action log should include inbox item ids");
        assertTrue(payload.contains("\"recordPid\"") && payload.contains("\"leave-sla-17\""),
                "SLA task action result should preserve the business record pid");

        Map<String, Object> inboxItem = jdbcTemplate.queryForMap("""
                select user_id, item_type, title, subtitle, priority, source_type, source_id,
                       model_code, record_pid, deep_link, client_item_id
                from ab_inbox_item
                where tenant_id = ? and client_item_id = ?
                """, getTestTenant().getId(), idempotencyKey + ":" + assigneeId);
        assertEquals(assigneeId, ((Number) inboxItem.get("user_id")).longValue());
        assertEquals("task", inboxItem.get("item_type"));
        assertEquals("SLA 待办 " + record.getPid(), inboxItem.get("title"));
        assertEquals("记录 leave-sla-17 已超时", inboxItem.get("subtitle"));
        assertEquals("urgent", inboxItem.get("priority"));
        assertEquals("event_policy", inboxItem.get("source_type"));
        assertEquals("SLA_TIMEOUT", inboxItem.get("source_id"));
        assertEquals("wd_leave_request", inboxItem.get("model_code"));
        assertEquals("leave-sla-17", inboxItem.get("record_pid"));
        assertEquals("/p/wd_leave_request/view/leave-sla-17", inboxItem.get("deep_link"));
        assertEquals(idempotencyKey + ":" + assigneeId, inboxItem.get("client_item_id"));

        scanAndRestoreContext();
        Integer execRowsAfterReplay = jdbcTemplate.queryForObject(
                "select count(*) from ab_drt_policy_exec_log where tenant_id = ? and idempotency_key = ?",
                Integer.class, getTestTenant().getId(), idempotencyKey);
        assertEquals(1, execRowsAfterReplay, "SLA CREATE_TASK action should be idempotent across scheduler scans");

        log.info("SLA-SCHED-17 PASSED: CREATE_TASK created inbox task through SLA action policy");
    }

    @Test
    @Order(18)
    @DisplayName("SLA-SCHED-18: SLA_TIMEOUT CC_TASK creates inbox mention and action evidence")
    void slaSched18_timeoutCcTaskCreatesInboxMentionAndActionEvidence() {
        Long targetUserId = getTestUser().getId();
        SlaConfigEntity config = BpmTestHelper.createSlaConfig(
                slaConfigMapper, getTestTenant().getId(), "wd_leave_request", "pt30m", List.of());
        config.setTargetType("RECORD");
        config.setTargetKey("wd_leave_request");
        config.setModelCode("wd_leave_request");
        config.setActionPolicy(Map.of(
                "trigger", "SLA_TIMEOUT",
                "actions", List.of(Map.of(
                        "type", "CC_TASK",
                        "target", "USER:" + targetUserId,
                        "order", 10,
                        "payload", Map.of(
                                "taskTitle", "SLA 抄送 ${sla.recordPid}",
                                "message", "记录 ${record.recordPid} 超时需关注"),
                        "idempotencyKeyTemplate", "${sla.recordPid}:timeout:CC_TASK"))));
        slaConfigMapper.updateById(config);

        SlaRecordEntity record = insertRecord(
                config.getPid(), "leave-sla-18", "task-sla-18",
                Instant.now().minus(Duration.ofMinutes(90)),
                Instant.now().minus(Duration.ofMinutes(30)),
                "running", 0, 0L);
        String idempotencyKey = record.getPid() + ":timeout:CC_TASK";

        scanAndRestoreContext();

        SlaRecordEntity reloaded = reloadRecord(record.getPid());
        assertNotNull(reloaded, "Record should exist after scan");
        assertEquals("overdue", reloaded.getStatus(), "SLA record becomes overdue when cc action fires");

        Map<String, Object> logRow = jdbcTemplate.queryForMap(
                "select policy_code, rule_code, action_type, status, result_payload from ab_drt_policy_exec_log "
                        + "where tenant_id = ? and idempotency_key = ?",
                getTestTenant().getId(), idempotencyKey);
        assertEquals("SLA_TIMEOUT:" + config.getPid(), logRow.get("policy_code"));
        assertEquals("SLA_TIMEOUT", logRow.get("rule_code"));
        assertEquals("CC_TASK", logRow.get("action_type"));
        assertEquals("SUCCESS", logRow.get("status"));
        String payload = String.valueOf(logRow.get("result_payload"));
        assertTrue(payload.contains("\"ccCount\""), "SLA cc action log should include cc count");
        assertTrue(payload.contains("\"inboxItemIds\""), "SLA cc action log should include inbox item ids");
        assertTrue(payload.contains("\"recordPid\"") && payload.contains("\"leave-sla-18\""),
                "SLA cc action result should preserve the business record pid");

        Map<String, Object> inboxItem = jdbcTemplate.queryForMap("""
                select user_id, item_type, title, subtitle, priority, source_type, source_id,
                       model_code, record_pid, deep_link, client_item_id
                from ab_inbox_item
                where tenant_id = ? and client_item_id = ?
                """, getTestTenant().getId(), idempotencyKey + ":" + targetUserId);
        assertEquals(targetUserId, ((Number) inboxItem.get("user_id")).longValue());
        assertEquals("mention", inboxItem.get("item_type"));
        assertEquals("SLA 抄送 " + record.getPid(), inboxItem.get("title"));
        assertEquals("记录 leave-sla-18 超时需关注", inboxItem.get("subtitle"));
        assertEquals("normal", inboxItem.get("priority"));
        assertEquals("event_policy", inboxItem.get("source_type"));
        assertEquals("SLA_TIMEOUT", inboxItem.get("source_id"));
        assertEquals("wd_leave_request", inboxItem.get("model_code"));
        assertEquals("leave-sla-18", inboxItem.get("record_pid"));
        assertEquals("/p/wd_leave_request/view/leave-sla-18", inboxItem.get("deep_link"));
        assertEquals(idempotencyKey + ":" + targetUserId, inboxItem.get("client_item_id"));

        scanAndRestoreContext();
        Integer execRowsAfterReplay = jdbcTemplate.queryForObject(
                "select count(*) from ab_drt_policy_exec_log where tenant_id = ? and idempotency_key = ?",
                Integer.class, getTestTenant().getId(), idempotencyKey);
        assertEquals(1, execRowsAfterReplay, "SLA CC_TASK action should be idempotent across scheduler scans");

        log.info("SLA-SCHED-18 PASSED: CC_TASK created inbox mention through SLA action policy");
    }

    @Test
    @Order(19)
    @DisplayName("SLA-SCHED-19: SLA_TIMEOUT SEND_IM creates bot conversation message and action evidence")
    void slaSched19_timeoutSendImCreatesBotConversationMessageAndActionEvidence() {
        Long targetUserId = getTestUser().getId();
        SlaConfigEntity config = BpmTestHelper.createSlaConfig(
                slaConfigMapper, getTestTenant().getId(), "wd_leave_request", "pt30m", List.of());
        config.setTargetType("RECORD");
        config.setTargetKey("wd_leave_request");
        config.setModelCode("wd_leave_request");
        config.setActionPolicy(Map.of(
                "trigger", "SLA_TIMEOUT",
                "actions", List.of(Map.of(
                        "type", "SEND_IM",
                        "target", "USER:" + targetUserId,
                        "order", 10,
                        "payload", Map.of(
                                "title", "SLA IM ${sla.recordPid}",
                                "content", "记录 ${record.recordPid} 超时，请在 IM 中关注",
                                "channel", "im"),
                        "idempotencyKeyTemplate", "${sla.recordPid}:timeout:SEND_IM"))));
        slaConfigMapper.updateById(config);

        SlaRecordEntity record = insertRecord(
                config.getPid(), "leave-sla-19", "task-sla-19",
                Instant.now().minus(Duration.ofMinutes(90)),
                Instant.now().minus(Duration.ofMinutes(30)),
                "running", 0, 0L);
        String idempotencyKey = record.getPid() + ":timeout:SEND_IM";
        String clientMsgId = idempotencyKey + ":" + targetUserId;

        scanAndRestoreContext();

        SlaRecordEntity reloaded = reloadRecord(record.getPid());
        assertNotNull(reloaded, "Record should exist after scan");
        assertEquals("overdue", reloaded.getStatus(), "SLA record becomes overdue when IM action fires");

        Map<String, Object> logRow = jdbcTemplate.queryForMap(
                "select policy_code, rule_code, action_type, status, result_payload from ab_drt_policy_exec_log "
                        + "where tenant_id = ? and idempotency_key = ?",
                getTestTenant().getId(), idempotencyKey);
        assertEquals("SLA_TIMEOUT:" + config.getPid(), logRow.get("policy_code"));
        assertEquals("SLA_TIMEOUT", logRow.get("rule_code"));
        assertEquals("SEND_IM", logRow.get("action_type"));
        assertEquals("SUCCESS", logRow.get("status"));
        String payload = String.valueOf(logRow.get("result_payload"));
        assertTrue(payload.contains("\"sentCount\""), "SLA IM action log should include sent message count");
        assertTrue(payload.contains("\"messageIds\""), "SLA IM action log should include IM message ids");
        assertTrue(payload.contains("\"conversationIds\""), "SLA IM action log should include conversation ids");
        assertTrue(payload.contains("\"recordPid\"") && payload.contains("\"leave-sla-19\""),
                "SLA IM action result should preserve the business record pid");

        Map<String, Object> imMessage = jdbcTemplate.queryForMap("""
                select m.id, m.conversation_id, m.sender_type, m.message_type, m.content, m.card_payload,
                       m.client_msg_id, c.type as conversation_type, c.name as conversation_name, c.owner_id
                from ab_im_message m
                join ab_im_conversation c on c.id = m.conversation_id and c.tenant_id = m.tenant_id
                where m.tenant_id = ? and m.client_msg_id = ?
                """, getTestTenant().getId(), clientMsgId);
        assertEquals("system", imMessage.get("sender_type"));
        assertEquals("system", imMessage.get("message_type"));
        assertEquals("记录 leave-sla-19 超时，请在 IM 中关注", imMessage.get("content"));
        assertEquals(clientMsgId, imMessage.get("client_msg_id"));
        assertEquals("bot", imMessage.get("conversation_type"));
        assertEquals("System Notifications", imMessage.get("conversation_name"));
        assertEquals(targetUserId, ((Number) imMessage.get("owner_id")).longValue());
        String cardPayload = String.valueOf(imMessage.get("card_payload"));
        assertTrue(cardPayload.contains("\"actionType\"") && cardPayload.contains("\"SEND_IM\""),
                "IM card should identify SEND_IM action");
        assertTrue(cardPayload.contains("\"ruleCode\"") && cardPayload.contains("\"SLA_TIMEOUT\""),
                "IM card should preserve SLA rule code");
        assertTrue(cardPayload.contains("\"recordPid\"") && cardPayload.contains("\"leave-sla-19\""),
                "IM card should preserve record pid");

        Integer memberRows = jdbcTemplate.queryForObject("""
                select count(*)
                from ab_im_conversation_member
                where tenant_id = ? and conversation_id = ? and member_type = 'human' and member_id = ?
                """, Integer.class, getTestTenant().getId(),
                ((Number) imMessage.get("conversation_id")).longValue(), targetUserId);
        assertEquals(1, memberRows, "SLA IM action should create a bot conversation visible to the target user");

        scanAndRestoreContext();
        Integer execRowsAfterReplay = jdbcTemplate.queryForObject(
                "select count(*) from ab_drt_policy_exec_log where tenant_id = ? and idempotency_key = ?",
                Integer.class, getTestTenant().getId(), idempotencyKey);
        assertEquals(1, execRowsAfterReplay, "SLA SEND_IM action should be idempotent across scheduler scans");
        Integer imRowsAfterReplay = jdbcTemplate.queryForObject(
                "select count(*) from ab_im_message where tenant_id = ? and client_msg_id = ?",
                Integer.class, getTestTenant().getId(), clientMsgId);
        assertEquals(1, imRowsAfterReplay, "SLA SEND_IM should not duplicate IM messages on scheduler replay");

        log.info("SLA-SCHED-19 PASSED: SEND_IM created IM message through SLA action policy");
    }

    @Test
    @Order(20)
    @DisplayName("SLA-SCHED-20: SLA_TIMEOUT WEBHOOK dispatches tracked delivery evidence")
    void slaSched20_timeoutWebhookDispatchesTrackedDeliveryEvidence() {
        String eventType = "sla.timeout." + System.nanoTime();
        WebhookSubscription subscription = new WebhookSubscription();
        subscription.setPid(UlidGenerator.generate());
        subscription.setTenantId(getTestTenant().getId());
        subscription.setName("SLA timeout webhook");
        subscription.setTargetUrl("http://127.0.0.1:6443/internal");
        subscription.setEventType(eventType);
        subscription.setEnabled(true);
        subscription.setMaxRetries(0);
        subscription.setTimeoutMs(1000);
        webhookSubscriptionMapper.insert(subscription);

        SlaConfigEntity config = BpmTestHelper.createSlaConfig(
                slaConfigMapper, getTestTenant().getId(), "wd_leave_request", "pt30m", List.of());
        config.setTargetType("RECORD");
        config.setTargetKey("wd_leave_request");
        config.setModelCode("wd_leave_request");
        config.setActionPolicy(Map.of(
                "trigger", "SLA_TIMEOUT",
                "actions", List.of(Map.of(
                        "type", "WEBHOOK",
                        "target", "WEBHOOK:" + eventType,
                        "order", 10,
                        "payload", Map.of(
                                "eventType", eventType,
                                "_eventId", "${sla.recordPid}:timeout:WEBHOOK:event",
                                "recordPid", "${record.recordPid}",
                                "slaRecordPid", "${sla.recordPid}",
                                "source", "sla-timeout"),
                        "idempotencyKeyTemplate", "${sla.recordPid}:timeout:WEBHOOK"))));
        slaConfigMapper.updateById(config);

        SlaRecordEntity record = insertRecord(
                config.getPid(), "leave-sla-20", "task-sla-20",
                Instant.now().minus(Duration.ofMinutes(90)),
                Instant.now().minus(Duration.ofMinutes(30)),
                "running", 0, 0L);
        String idempotencyKey = record.getPid() + ":timeout:WEBHOOK";
        String deliveryEventId = record.getPid() + ":timeout:WEBHOOK:event";

        scanAndRestoreContext();

        SlaRecordEntity reloaded = reloadRecord(record.getPid());
        assertNotNull(reloaded, "Record should exist after scan");
        assertEquals("overdue", reloaded.getStatus(), "SLA record becomes overdue when webhook action fires");

        Map<String, Object> logRow = jdbcTemplate.queryForMap(
                "select policy_code, rule_code, action_type, status, result_payload from ab_drt_policy_exec_log "
                        + "where tenant_id = ? and idempotency_key = ?",
                getTestTenant().getId(), idempotencyKey);
        assertEquals("SLA_TIMEOUT:" + config.getPid(), logRow.get("policy_code"));
        assertEquals("SLA_TIMEOUT", logRow.get("rule_code"));
        assertEquals("WEBHOOK", logRow.get("action_type"));
        assertEquals("SUCCESS", logRow.get("status"));
        String payload = String.valueOf(logRow.get("result_payload"));
        assertTrue(payload.contains("\"eventType\"") && payload.contains(eventType),
                "SLA webhook action log should include event type");
        assertTrue(payload.contains("\"deliveryEventId\"") && payload.contains(deliveryEventId),
                "SLA webhook action log should include delivery event id");
        assertTrue(payload.contains("\"deliveryLogPids\""),
                "SLA webhook action log should include tracked delivery log pids");
        assertTrue(payload.contains("\"deliveryReceipts\""),
                "SLA webhook action log should include delivery receipts");

        Map<String, Object> delivery = jdbcTemplate.queryForMap("""
                select pid, subscription_pid, event_id, request_url, request_body, delivery_status, error_message
                from ab_webhook_delivery_log
                where tenant_id = ? and subscription_pid = ? and event_id = ?
                """, getTestTenant().getId(), subscription.getPid(), deliveryEventId);
        assertEquals(subscription.getPid(), delivery.get("subscription_pid"));
        assertEquals(deliveryEventId, delivery.get("event_id"));
        assertEquals("http://127.0.0.1:6443/internal", delivery.get("request_url"));
        assertEquals("failed", delivery.get("delivery_status"));
        assertTrue(String.valueOf(delivery.get("error_message")).contains("not allowed"),
                "Blocked local webhook target should preserve failure reason in delivery log");
        String requestBody = String.valueOf(delivery.get("request_body"));
        assertTrue(requestBody.contains("\"recordPid\"") && requestBody.contains("leave-sla-20"),
                "Webhook delivery body should preserve business record pid");
        assertTrue(requestBody.contains("\"slaRecordPid\"") && requestBody.contains(record.getPid()),
                "Webhook delivery body should preserve SLA record pid");

        scanAndRestoreContext();
        Integer execRowsAfterReplay = jdbcTemplate.queryForObject(
                "select count(*) from ab_drt_policy_exec_log where tenant_id = ? and idempotency_key = ?",
                Integer.class, getTestTenant().getId(), idempotencyKey);
        assertEquals(1, execRowsAfterReplay, "SLA WEBHOOK action should be idempotent across scheduler scans");
        Integer deliveryRowsAfterReplay = jdbcTemplate.queryForObject(
                "select count(*) from ab_webhook_delivery_log where tenant_id = ? and subscription_pid = ? and event_id = ?",
                Integer.class, getTestTenant().getId(), subscription.getPid(), deliveryEventId);
        assertEquals(1, deliveryRowsAfterReplay, "SLA WEBHOOK should not duplicate delivery attempts on scheduler replay");

        log.info("SLA-SCHED-20 PASSED: WEBHOOK dispatched tracked delivery through SLA action policy");
    }

    @Test
    @Order(21)
    @DisplayName("SLA-SCHED-21: SLA_TIMEOUT WRITE_AUDIT writes business audit evidence")
    void slaSched21_timeoutWriteAuditWritesBusinessAuditEvidence() {
        SlaConfigEntity config = BpmTestHelper.createSlaConfig(
                slaConfigMapper, getTestTenant().getId(), "wd_leave_request", "pt30m", List.of());
        config.setTargetType("RECORD");
        config.setTargetKey("wd_leave_request");
        config.setModelCode("wd_leave_request");
        config.setActionPolicy(Map.of(
                "trigger", "SLA_TIMEOUT",
                "actions", List.of(Map.of(
                        "type", "WRITE_AUDIT",
                        "target", "AUDIT:${record.entityCode}",
                        "order", 10,
                        "payload", Map.of(
                                "message", "SLA 审计 ${record.recordPid}",
                                "source", "sla-timeout"),
                        "idempotencyKeyTemplate", "${sla.recordPid}:timeout:WRITE_AUDIT"))));
        slaConfigMapper.updateById(config);

        SlaRecordEntity record = insertRecord(
                config.getPid(), "leave-sla-21", "task-sla-21",
                Instant.now().minus(Duration.ofMinutes(90)),
                Instant.now().minus(Duration.ofMinutes(30)),
                "running", 0, 0L);
        String idempotencyKey = record.getPid() + ":timeout:WRITE_AUDIT";

        scanAndRestoreContext();

        SlaRecordEntity reloaded = reloadRecord(record.getPid());
        assertNotNull(reloaded, "Record should exist after scan");
        assertEquals("overdue", reloaded.getStatus(), "SLA record becomes overdue when audit action fires");

        Map<String, Object> logRow = jdbcTemplate.queryForMap(
                "select policy_code, rule_code, action_type, status, result_payload from ab_drt_policy_exec_log "
                        + "where tenant_id = ? and idempotency_key = ?",
                getTestTenant().getId(), idempotencyKey);
        assertEquals("SLA_TIMEOUT:" + config.getPid(), logRow.get("policy_code"));
        assertEquals("SLA_TIMEOUT", logRow.get("rule_code"));
        assertEquals("WRITE_AUDIT", logRow.get("action_type"));
        assertEquals("SUCCESS", logRow.get("status"));
        String payload = String.valueOf(logRow.get("result_payload"));
        assertTrue(payload.contains("\"auditPid\""), "SLA audit action log should include audit pid");
        assertTrue(payload.contains("\"message\"") && payload.contains("SLA 审计 leave-sla-21"),
                "SLA audit action result should preserve rendered audit message");

        Map<String, Object> audit = jdbcTemplate.queryForMap("""
                select rule_code, action_type, target, message, payload_json
                from ab_drt_action_audit
                where tenant_id = ? and idempotency_key = ?
                """, getTestTenant().getId(), idempotencyKey);
        assertEquals("SLA_TIMEOUT", audit.get("rule_code"));
        assertEquals("WRITE_AUDIT", audit.get("action_type"));
        assertEquals("AUDIT:wd_leave_request", audit.get("target"));
        assertEquals("SLA 审计 leave-sla-21", audit.get("message"));
        assertTrue(String.valueOf(audit.get("payload_json")).contains("\"source\": \"sla-timeout\"")
                        || String.valueOf(audit.get("payload_json")).contains("\"source\":\"sla-timeout\""),
                "Audit payload should keep SLA source metadata");

        scanAndRestoreContext();
        Integer execRowsAfterReplay = jdbcTemplate.queryForObject(
                "select count(*) from ab_drt_policy_exec_log where tenant_id = ? and idempotency_key = ?",
                Integer.class, getTestTenant().getId(), idempotencyKey);
        assertEquals(1, execRowsAfterReplay, "SLA WRITE_AUDIT action should be idempotent across scheduler scans");
        Integer auditRowsAfterReplay = jdbcTemplate.queryForObject(
                "select count(*) from ab_drt_action_audit where tenant_id = ? and idempotency_key = ?",
                Integer.class, getTestTenant().getId(), idempotencyKey);
        assertEquals(1, auditRowsAfterReplay, "SLA WRITE_AUDIT should not duplicate audit rows on scheduler replay");

        log.info("SLA-SCHED-21 PASSED: WRITE_AUDIT created business audit row through SLA action policy");
    }

    @Test
    @Order(22)
    @DisplayName("SLA-SCHED-22: SLA_TIMEOUT ADD_COMMENT writes record comment evidence")
    void slaSched22_timeoutAddCommentWritesRecordCommentEvidence() {
        SlaConfigEntity config = BpmTestHelper.createSlaConfig(
                slaConfigMapper, getTestTenant().getId(), "wd_leave_request", "pt30m", List.of());
        config.setTargetType("RECORD");
        config.setTargetKey("wd_leave_request");
        config.setModelCode("wd_leave_request");
        config.setActionPolicy(Map.of(
                "trigger", "SLA_TIMEOUT",
                "actions", List.of(Map.of(
                        "type", "ADD_COMMENT",
                        "target", "RECORD",
                        "order", 10,
                        "payload", Map.of(
                                "content", "SLA 评论 ${record.recordPid}",
                                "mentions", "ROLE:wd_manager"),
                        "idempotencyKeyTemplate", "${sla.recordPid}:timeout:ADD_COMMENT"))));
        slaConfigMapper.updateById(config);

        SlaRecordEntity record = insertRecord(
                config.getPid(), "leave-sla-22", "task-sla-22",
                Instant.now().minus(Duration.ofMinutes(90)),
                Instant.now().minus(Duration.ofMinutes(30)),
                "running", 0, 0L);
        String idempotencyKey = record.getPid() + ":timeout:ADD_COMMENT";

        scanAndRestoreContext();

        SlaRecordEntity reloaded = reloadRecord(record.getPid());
        assertNotNull(reloaded, "Record should exist after scan");
        assertEquals("overdue", reloaded.getStatus(), "SLA record becomes overdue when comment action fires");

        Map<String, Object> logRow = jdbcTemplate.queryForMap(
                "select policy_code, rule_code, action_type, status, result_payload from ab_drt_policy_exec_log "
                        + "where tenant_id = ? and idempotency_key = ?",
                getTestTenant().getId(), idempotencyKey);
        assertEquals("SLA_TIMEOUT:" + config.getPid(), logRow.get("policy_code"));
        assertEquals("SLA_TIMEOUT", logRow.get("rule_code"));
        assertEquals("ADD_COMMENT", logRow.get("action_type"));
        assertEquals("SUCCESS", logRow.get("status"));
        String payload = String.valueOf(logRow.get("result_payload"));
        assertTrue(payload.contains("\"commentPid\""), "SLA comment action log should include comment pid");
        assertTrue(payload.contains("\"recordPid\"") && payload.contains("\"leave-sla-22\""),
                "SLA comment action result should preserve the business record pid");
        assertTrue(payload.contains("\"content\"") && payload.contains("SLA 评论 leave-sla-22"),
                "SLA comment action result should preserve rendered content");

        Map<String, Object> comment = jdbcTemplate.queryForMap("""
                select model_code, record_pid, content, mentions
                from ab_record_comment
                where tenant_id = ? and model_code = ? and record_pid = ?
                """, getTestTenant().getId(), "wd_leave_request", "leave-sla-22");
        assertEquals("wd_leave_request", comment.get("model_code"));
        assertEquals("leave-sla-22", comment.get("record_pid"));
        assertEquals("SLA 评论 leave-sla-22", comment.get("content"));
        assertEquals("ROLE:wd_manager", comment.get("mentions"));

        scanAndRestoreContext();
        Integer execRowsAfterReplay = jdbcTemplate.queryForObject(
                "select count(*) from ab_drt_policy_exec_log where tenant_id = ? and idempotency_key = ?",
                Integer.class, getTestTenant().getId(), idempotencyKey);
        assertEquals(1, execRowsAfterReplay, "SLA ADD_COMMENT action should be idempotent across scheduler scans");
        Integer commentRowsAfterReplay = jdbcTemplate.queryForObject(
                "select count(*) from ab_record_comment where tenant_id = ? and model_code = ? and record_pid = ?",
                Integer.class, getTestTenant().getId(), "wd_leave_request", "leave-sla-22");
        assertEquals(1, commentRowsAfterReplay, "SLA ADD_COMMENT should not duplicate comments on scheduler replay");

        log.info("SLA-SCHED-22 PASSED: ADD_COMMENT created record comment through SLA action policy");
    }

    @Test
    @Order(23)
    @DisplayName("SLA-SCHED-23: SLA_TIMEOUT FAIL_FAST records blocked actions as NOT_EXECUTED")
    void slaSched23_timeoutFailFastRecordsBlockedActionsAsNotExecuted() {
        Long recipientId = getTestUser().getId();
        SlaConfigEntity config = BpmTestHelper.createSlaConfig(
                slaConfigMapper, getTestTenant().getId(), "wd_leave_request", "pt30m", List.of());
        config.setTargetType("RECORD");
        config.setTargetKey("wd_leave_request");
        config.setModelCode("wd_leave_request");
        config.setActionPolicy(Map.of(
                "trigger", "SLA_TIMEOUT",
                "failureStrategy", "FAIL_FAST",
                "actions", List.of(
                        Map.of(
                                "type", "UNKNOWN_ACTION",
                                "target", "SYSTEM",
                                "order", 10,
                                "payload", Map.of("reason", "force fail-fast"),
                                "idempotencyKeyTemplate", "${sla.recordPid}:timeout:UNKNOWN_ACTION"),
                        Map.of(
                                "type", "NOTIFY",
                                "target", "USER:" + recipientId,
                                "order", 20,
                                "payload", Map.of(
                                        "title", "不应发送 ${sla.recordPid}",
                                        "content", "前序动作失败后应被阻断"),
                                "idempotencyKeyTemplate", "${sla.recordPid}:timeout:NOTIFY"))));
        slaConfigMapper.updateById(config);

        SlaRecordEntity record = insertRecord(
                config.getPid(), "leave-sla-23", "task-sla-23",
                Instant.now().minus(Duration.ofMinutes(90)),
                Instant.now().minus(Duration.ofMinutes(30)),
                "running", 0, 0L);
        String failedKey = record.getPid() + ":timeout:UNKNOWN_ACTION";
        String blockedKey = record.getPid() + ":timeout:NOTIFY";

        scanAndRestoreContext();

        SlaRecordEntity reloaded = reloadRecord(record.getPid());
        assertNotNull(reloaded, "Record should exist after scan");
        assertEquals("overdue", reloaded.getStatus(), "SLA record still becomes overdue under fail-fast strategy");

        Map<String, Object> failedLog = jdbcTemplate.queryForMap(
                "select policy_code, rule_code, action_type, status, error_message from ab_drt_policy_exec_log "
                        + "where tenant_id = ? and idempotency_key = ?",
                getTestTenant().getId(), failedKey);
        assertEquals("SLA_TIMEOUT:" + config.getPid(), failedLog.get("policy_code"));
        assertEquals("SLA_TIMEOUT", failedLog.get("rule_code"));
        assertEquals("UNKNOWN_ACTION", failedLog.get("action_type"));
        assertEquals("NO_HANDLER", failedLog.get("status"));
        assertTrue(String.valueOf(failedLog.get("error_message")).contains("no handler for action type UNKNOWN_ACTION"),
                "SLA fail-fast trace should preserve the missing handler reason");

        Map<String, Object> blockedLog = jdbcTemplate.queryForMap(
                "select policy_code, rule_code, action_type, status, error_message from ab_drt_policy_exec_log "
                        + "where tenant_id = ? and idempotency_key = ?",
                getTestTenant().getId(), blockedKey);
        assertEquals("SLA_TIMEOUT:" + config.getPid(), blockedLog.get("policy_code"));
        assertEquals("SLA_TIMEOUT", blockedLog.get("rule_code"));
        assertEquals("NOTIFY", blockedLog.get("action_type"));
        assertEquals("NOT_EXECUTED", blockedLog.get("status"));
        assertNull(blockedLog.get("error_message"), "Blocked action should be marked without a fake handler error");

        Integer notificationRows = jdbcTemplate.queryForObject("""
                select count(*)
                from ab_notification
                where tenant_id = ? and user_id = ? and title = ?
                """, Integer.class, getTestTenant().getId(), recipientId,
                "不应发送 " + record.getPid());
        assertEquals(0, notificationRows, "Fail-fast should block the later NOTIFY side effect");

        log.info("SLA-SCHED-23 PASSED: FAIL_FAST persisted NO_HANDLER + NOT_EXECUTED action evidence");
    }

    @Test
    @Order(24)
    @DisplayName("SLA-SCHED-24: SLA_TIMEOUT RETRY_ASYNC records retry-pending SMS failure evidence")
    void slaSched24_timeoutRetryAsyncRecordsRetryPendingSmsFailureEvidence() {
        SlaConfigEntity config = BpmTestHelper.createSlaConfig(
                slaConfigMapper, getTestTenant().getId(), "wd_leave_request", "pt30m", List.of());
        config.setTargetType("RECORD");
        config.setTargetKey("wd_leave_request");
        config.setModelCode("wd_leave_request");
        config.setActionPolicy(Map.of(
                "trigger", "SLA_TIMEOUT",
                "failureStrategy", "RETRY_ASYNC",
                "actions", List.of(Map.of(
                        "type", "SEND_SMS",
                        "target", "PHONE:+8613800138000",
                        "order", 10,
                        "payload", Map.of(
                                "template", "sla_timeout",
                                "content", "SLA ${sla.recordPid} 等待异步重试"),
                        "idempotencyKeyTemplate", "${sla.recordPid}:timeout:SEND_SMS_RETRY"))));
        slaConfigMapper.updateById(config);

        SlaRecordEntity record = insertRecord(
                config.getPid(), "leave-sla-24", "task-sla-24",
                Instant.now().minus(Duration.ofMinutes(90)),
                Instant.now().minus(Duration.ofMinutes(30)),
                "running", 0, 0L);
        String idempotencyKey = record.getPid() + ":timeout:SEND_SMS_RETRY";

        scanAndRestoreContext();

        SlaRecordEntity reloaded = reloadRecord(record.getPid());
        assertNotNull(reloaded, "Record should exist after scan");
        assertEquals("overdue", reloaded.getStatus(), "SLA record still becomes overdue under retry-async strategy");

        Map<String, Object> logRow = jdbcTemplate.queryForMap(
                "select policy_code, rule_code, action_type, status, error_message, result_payload "
                        + "from ab_drt_policy_exec_log where tenant_id = ? and idempotency_key = ?",
                getTestTenant().getId(), idempotencyKey);
        assertEquals("SLA_TIMEOUT:" + config.getPid(), logRow.get("policy_code"));
        assertEquals("SLA_TIMEOUT", logRow.get("rule_code"));
        assertEquals("SEND_SMS", logRow.get("action_type"));
        assertEquals("RETRY_PENDING", logRow.get("status"));
        assertTrue(String.valueOf(logRow.get("error_message")).contains("No real SMS sender available"),
                "SLA retry trace should preserve SMS provider unavailable reason");
        assertTrue(String.valueOf(logRow.get("result_payload")).contains("+8613800138000"),
                "SLA retry trace should preserve the rendered SMS target for operator triage");

        scanAndRestoreContext();
        Integer execRowsAfterReplay = jdbcTemplate.queryForObject(
                "select count(*) from ab_drt_policy_exec_log where tenant_id = ? and idempotency_key = ?",
                Integer.class, getTestTenant().getId(), idempotencyKey);
        assertEquals(1, execRowsAfterReplay, "Retry-pending SLA action should update the same idempotency row on rescan");

        log.info("SLA-SCHED-24 PASSED: RETRY_ASYNC persisted RETRY_PENDING action evidence");
    }

    @Test
    @Order(25)
    @DisplayName("SLA-SCHED-25: SLA_TIMEOUT DEAD_LETTER records dead-letter SMS failure evidence")
    void slaSched25_timeoutDeadLetterRecordsDeadLetterSmsFailureEvidence() {
        SlaConfigEntity config = BpmTestHelper.createSlaConfig(
                slaConfigMapper, getTestTenant().getId(), "wd_leave_request", "pt30m", List.of());
        config.setTargetType("RECORD");
        config.setTargetKey("wd_leave_request");
        config.setModelCode("wd_leave_request");
        config.setActionPolicy(Map.of(
                "trigger", "SLA_TIMEOUT",
                "failureStrategy", "DEAD_LETTER",
                "actions", List.of(Map.of(
                        "type", "SEND_SMS",
                        "target", "PHONE:+8613800138001",
                        "order", 10,
                        "payload", Map.of(
                                "template", "sla_timeout",
                                "content", "SLA ${sla.recordPid} 进入死信"),
                        "idempotencyKeyTemplate", "${sla.recordPid}:timeout:SEND_SMS_DEAD_LETTER"))));
        slaConfigMapper.updateById(config);

        SlaRecordEntity record = insertRecord(
                config.getPid(), "leave-sla-25", "task-sla-25",
                Instant.now().minus(Duration.ofMinutes(90)),
                Instant.now().minus(Duration.ofMinutes(30)),
                "running", 0, 0L);
        String idempotencyKey = record.getPid() + ":timeout:SEND_SMS_DEAD_LETTER";

        scanAndRestoreContext();

        SlaRecordEntity reloaded = reloadRecord(record.getPid());
        assertNotNull(reloaded, "Record should exist after scan");
        assertEquals("overdue", reloaded.getStatus(), "SLA record still becomes overdue under dead-letter strategy");

        Map<String, Object> logRow = jdbcTemplate.queryForMap(
                "select policy_code, rule_code, action_type, status, error_message, result_payload "
                        + "from ab_drt_policy_exec_log where tenant_id = ? and idempotency_key = ?",
                getTestTenant().getId(), idempotencyKey);
        assertEquals("SLA_TIMEOUT:" + config.getPid(), logRow.get("policy_code"));
        assertEquals("SLA_TIMEOUT", logRow.get("rule_code"));
        assertEquals("SEND_SMS", logRow.get("action_type"));
        assertEquals("DEAD_LETTER", logRow.get("status"));
        assertTrue(String.valueOf(logRow.get("error_message")).contains("No real SMS sender available"),
                "SLA dead-letter trace should preserve SMS provider unavailable reason");
        assertTrue(String.valueOf(logRow.get("result_payload")).contains("+8613800138001"),
                "SLA dead-letter trace should preserve the rendered SMS target for operator triage");

        scanAndRestoreContext();
        Integer execRowsAfterReplay = jdbcTemplate.queryForObject(
                "select count(*) from ab_drt_policy_exec_log where tenant_id = ? and idempotency_key = ?",
                Integer.class, getTestTenant().getId(), idempotencyKey);
        assertEquals(1, execRowsAfterReplay, "Dead-letter SLA action should update the same idempotency row on rescan");

        log.info("SLA-SCHED-25 PASSED: DEAD_LETTER persisted dead-letter action evidence");
    }

    @Test
    @Order(26)
    @DisplayName("SLA-SCHED-26: SLA_TIMEOUT RETRY_ASYNC worker exhausts retries into dead letter")
    void slaSched26_timeoutRetryAsyncWorkerExhaustsRetriesIntoDeadLetter() {
        SlaConfigEntity config = BpmTestHelper.createSlaConfig(
                slaConfigMapper, getTestTenant().getId(), "wd_leave_request", "pt30m", List.of());
        config.setTargetType("RECORD");
        config.setTargetKey("wd_leave_request");
        config.setModelCode("wd_leave_request");
        config.setActionPolicy(Map.of(
                "trigger", "SLA_TIMEOUT",
                "failureStrategy", "RETRY_ASYNC",
                "actions", List.of(Map.of(
                        "type", "SEND_SMS",
                        "target", "PHONE:+8613800138002",
                        "order", 10,
                        "payload", Map.of(
                                "template", "sla_timeout",
                                "content", "SLA ${sla.recordPid} retry worker exhausts",
                                "retry", Map.of("maxAttempts", 3, "backoffSeconds", 1)),
                        "idempotencyKeyTemplate", "${sla.recordPid}:timeout:SEND_SMS_RETRY_WORKER"))));
        slaConfigMapper.updateById(config);

        SlaRecordEntity record = insertRecord(
                config.getPid(), "leave-sla-26", "task-sla-26",
                Instant.now().minus(Duration.ofMinutes(90)),
                Instant.now().minus(Duration.ofMinutes(30)),
                "running", 0, 0L);
        String idempotencyKey = record.getPid() + ":timeout:SEND_SMS_RETRY_WORKER";

        scanAndRestoreContext();

        Map<String, Object> firstAttempt = jdbcTemplate.queryForMap(
                "select status, attempt_count, max_attempts, next_retry_at, action_payload, context_payload, result_payload "
                        + "from ab_drt_policy_exec_log where tenant_id = ? and idempotency_key = ?",
                getTestTenant().getId(), idempotencyKey);
        assertEquals("RETRY_PENDING", firstAttempt.get("status"));
        assertEquals(1, ((Number) firstAttempt.get("attempt_count")).intValue());
        assertEquals(3, ((Number) firstAttempt.get("max_attempts")).intValue());
        assertNotNull(firstAttempt.get("next_retry_at"), "Initial retry-pending row should schedule next retry");
        assertTrue(String.valueOf(firstAttempt.get("action_payload")).contains("+8613800138002"),
                "Retry action envelope should keep rendered action target");
        assertTrue(String.valueOf(firstAttempt.get("context_payload")).contains(record.getPid()),
                "Retry context envelope should keep SLA record pid");

        makeRetryDue(idempotencyKey);
        assertEquals(1, eventPolicyActionRetryService.retryReadyActions(10),
                "Worker should retry the due RETRY_PENDING action once");

        Map<String, Object> secondAttempt = jdbcTemplate.queryForMap(
                "select status, attempt_count, max_attempts, next_retry_at, last_retry_at, error_message, result_payload "
                        + "from ab_drt_policy_exec_log where tenant_id = ? and idempotency_key = ?",
                getTestTenant().getId(), idempotencyKey);
        assertEquals("RETRY_PENDING", secondAttempt.get("status"));
        assertEquals(2, ((Number) secondAttempt.get("attempt_count")).intValue());
        assertEquals(3, ((Number) secondAttempt.get("max_attempts")).intValue());
        assertNotNull(secondAttempt.get("next_retry_at"), "Second failed attempt should schedule one final retry");
        assertNotNull(secondAttempt.get("last_retry_at"), "Worker retry should stamp last_retry_at");
        assertTrue(String.valueOf(secondAttempt.get("error_message")).contains("No real SMS sender available"),
                "Retry should preserve handler failure reason");

        makeRetryDue(idempotencyKey);
        assertEquals(1, eventPolicyActionRetryService.retryReadyActions(10),
                "Worker should execute the final allowed retry attempt");

        Map<String, Object> exhausted = jdbcTemplate.queryForMap(
                "select status, attempt_count, max_attempts, next_retry_at, last_retry_at, dead_lettered_at, "
                        + "error_message, result_payload "
                        + "from ab_drt_policy_exec_log where tenant_id = ? and idempotency_key = ?",
                getTestTenant().getId(), idempotencyKey);
        assertEquals("DEAD_LETTER", exhausted.get("status"));
        assertEquals(3, ((Number) exhausted.get("attempt_count")).intValue());
        assertEquals(3, ((Number) exhausted.get("max_attempts")).intValue());
        assertNull(exhausted.get("next_retry_at"), "Exhausted retry row should leave the retry queue");
        assertNotNull(exhausted.get("last_retry_at"), "Exhausted retry row should keep the last attempt timestamp");
        assertNotNull(exhausted.get("dead_lettered_at"), "Exhausted retry row should enter the dead-letter queue");
        assertTrue(String.valueOf(exhausted.get("error_message")).contains("Retry attempts exhausted after 3 attempts"),
                "Dead-letter row should explain retry exhaustion");
        assertTrue(String.valueOf(exhausted.get("result_payload")).contains("retryExhausted"),
                "Dead-letter result payload should include retry exhaustion evidence");
        assertTrue(String.valueOf(exhausted.get("result_payload")).contains("+8613800138002"),
                "Dead-letter result payload should preserve SMS target evidence");

        assertEquals(0, eventPolicyActionRetryService.retryReadyActions(10),
                "Dead-lettered row should not be retried again by the worker");

        log.info("SLA-SCHED-26 PASSED: RETRY_ASYNC worker exhausted retries into DEAD_LETTER evidence");
    }

    @Test
    @Order(27)
    @DisplayName("SLA-SCHED-27: RETRY_ASYNC worker polls due actions without request MetaContext")
    void slaSched27_retryWorkerPollsWithoutRequestMetaContext() {
        SlaConfigEntity config = BpmTestHelper.createSlaConfig(
                slaConfigMapper, getTestTenant().getId(), "wd_leave_request", "pt30m", List.of());
        config.setTargetType("RECORD");
        config.setTargetKey("wd_leave_request");
        config.setModelCode("wd_leave_request");
        config.setActionPolicy(Map.of(
                "trigger", "SLA_TIMEOUT",
                "failureStrategy", "RETRY_ASYNC",
                "actions", List.of(Map.of(
                        "type", "SEND_SMS",
                        "target", "PHONE:+8613800138003",
                        "order", 10,
                        "payload", Map.of(
                                "template", "sla_timeout",
                                "content", "SLA ${sla.recordPid} retry worker no context",
                                "retry", Map.of("maxAttempts", 3, "backoffSeconds", 1)),
                        "idempotencyKeyTemplate", "${sla.recordPid}:timeout:SEND_SMS_RETRY_NO_CONTEXT"))));
        slaConfigMapper.updateById(config);

        SlaRecordEntity record = insertRecord(
                config.getPid(), "leave-sla-27", "task-sla-27",
                Instant.now().minus(Duration.ofMinutes(90)),
                Instant.now().minus(Duration.ofMinutes(30)),
                "running", 0, 0L);
        String idempotencyKey = record.getPid() + ":timeout:SEND_SMS_RETRY_NO_CONTEXT";

        scanAndRestoreContext();
        makeRetryDue(idempotencyKey);

        try {
            MetaContext.clear();
            int retried = assertDoesNotThrow(
                    () -> eventPolicyActionRetryService.retryReadyActions(10),
                    "Scheduled retry polling must not require a request MetaContext");
            assertEquals(1, retried, "No-context worker should still retry the due row");
            assertFalse(MetaContext.exists(), "Retry worker should restore the no-context scheduler thread");
        } finally {
            applyTestMetaContext();
        }

        Map<String, Object> secondAttempt = jdbcTemplate.queryForMap(
                "select status, attempt_count, max_attempts, last_retry_at, error_message "
                        + "from ab_drt_policy_exec_log where tenant_id = ? and idempotency_key = ?",
                getTestTenant().getId(), idempotencyKey);
        assertEquals("RETRY_PENDING", secondAttempt.get("status"));
        assertEquals(2, ((Number) secondAttempt.get("attempt_count")).intValue());
        assertEquals(3, ((Number) secondAttempt.get("max_attempts")).intValue());
        assertNotNull(secondAttempt.get("last_retry_at"), "No-context worker retry should stamp last_retry_at");
        assertTrue(String.valueOf(secondAttempt.get("error_message")).contains("No real SMS sender available"),
                "No-context retry should preserve handler failure reason");

        log.info("SLA-SCHED-27 PASSED: RETRY_ASYNC worker runs without request MetaContext");
    }

    private void makeRetryDue(String idempotencyKey) {
        jdbcTemplate.update(
                "update ab_drt_policy_exec_log "
                        + "set next_retry_at = CURRENT_TIMESTAMP - interval '1 second' "
                        + "where tenant_id = ? and idempotency_key = ?",
                getTestTenant().getId(), idempotencyKey);
    }
}
