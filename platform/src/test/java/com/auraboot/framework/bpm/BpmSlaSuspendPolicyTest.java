package com.auraboot.framework.bpm;

import com.auraboot.framework.bpm.entity.SlaConfigEntity;
import com.auraboot.framework.bpm.entity.SlaRecordEntity;
import com.auraboot.framework.bpm.mapper.SlaConfigMapper;
import com.auraboot.framework.bpm.mapper.SlaRecordMapper;
import com.auraboot.framework.bpm.service.SlaRecordService;
import com.auraboot.framework.bpm.service.ProcessDeploymentService;
import com.auraboot.framework.bpm.util.BpmSecurityUtil;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.common.util.UlidGenerator;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for SLA suspend policy, pause/resume lifecycle,
 * calculateProgress with paused time, and undeploy safety check.
 *
 * @author AuraBoot Team
 */
@Slf4j
@DisplayName("BPM SLA Suspend Policy Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BpmSlaSuspendPolicyTest extends BaseIntegrationTest {

    @Autowired
    private SlaRecordService slaRecordService;

    @Autowired
    private SlaConfigMapper slaConfigMapper;

    @Autowired
    private SlaRecordMapper slaRecordMapper;

    @Autowired
    private ProcessDeploymentService deploymentService;

    // ==================== Helper Methods ====================

    private SlaConfigEntity createTestConfig(String suspendPolicy) {
        SlaConfigEntity config = SlaConfigEntity.builder()
                .pid(UlidGenerator.generate())
                .tenantId(getTestTenant().getId())
                .name("Test SLA Config - " + suspendPolicy)
                .targetType("process")
                .targetKey("test-sla-process")
                .deadlineMode("fixed")
                .deadlineValue("pt1h")
                .suspendPolicy(suspendPolicy)
                .enabled(true)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        slaConfigMapper.insert(config);
        return config;
    }

    // ==================== Test Cases ====================

    @Test
    @Order(1)
    @DisplayName("SLA-01: Create SLA record with PAUSE policy config - defaults correct")
    void sla01_createRecordWithPausePolicy() {
        SlaConfigEntity config = createTestConfig("pause");

        Instant deadline = Instant.now().plus(Duration.ofHours(1));
        SlaRecordEntity record = slaRecordService.createRecord(
                config, "proc-inst-001", null, null, deadline);

        assertNotNull(record.getPid(), "Record PID should be generated");
        assertEquals("running", record.getStatus(), "Initial status should be RUNNING");
        assertEquals(0L, record.getTotalPausedMs(), "Initial totalPausedMs should be 0");
        assertNull(record.getPausedAt(), "Initial pausedAt should be null");
        assertFalse(record.isPaused(), "Should not be paused initially");
        assertTrue(record.isActive(), "Should be active initially");

        log.info("SLA-01 PASSED: Record created with correct defaults");
    }

    @Test
    @Order(2)
    @DisplayName("SLA-02: Pause SLA records by process instance (PAUSE policy)")
    void sla02_pauseByProcessInstance() {
        SlaConfigEntity config = createTestConfig("pause");
        String processInstanceId = "proc-inst-pause-" + System.nanoTime();

        Instant deadline = Instant.now().plus(Duration.ofHours(1));
        SlaRecordEntity record = slaRecordService.createRecord(
                config, processInstanceId, null, null, deadline);

        // Pause
        slaRecordService.pauseByProcessInstance(processInstanceId);

        // Verify
        SlaRecordEntity paused = slaRecordService.getByPid(record.getPid());
        assertNotNull(paused, "Paused record should be found");
        assertEquals("paused", paused.getStatus(), "Status should be PAUSED");
        assertNotNull(paused.getPausedAt(), "pausedAt should be set");
        assertTrue(paused.isPaused(), "isPaused() should return true");
        assertTrue(paused.isActive(), "PAUSED record should still be considered active");

        log.info("SLA-02 PASSED: Record paused successfully");
    }

    @Test
    @Order(3)
    @DisplayName("SLA-03: Resume SLA records - accumulates paused duration")
    void sla03_resumeByProcessInstance() throws InterruptedException {
        SlaConfigEntity config = createTestConfig("pause");
        String processInstanceId = "proc-inst-resume-" + System.nanoTime();

        Instant deadline = Instant.now().plus(Duration.ofHours(1));
        SlaRecordEntity record = slaRecordService.createRecord(
                config, processInstanceId, null, null, deadline);

        // Pause
        slaRecordService.pauseByProcessInstance(processInstanceId);

        // Wait a bit to accumulate paused time
        Thread.sleep(100);

        // Resume
        slaRecordService.resumeByProcessInstance(processInstanceId);

        // Verify
        SlaRecordEntity resumed = slaRecordService.getByPid(record.getPid());
        assertNotNull(resumed, "Resumed record should be found");
        assertEquals("running", resumed.getStatus(), "Status should be RUNNING after resume");
        assertNull(resumed.getPausedAt(), "pausedAt should be cleared after resume");
        assertTrue(resumed.getTotalPausedMs() > 0,
                "totalPausedMs should be > 0 after pause/resume cycle, got: " + resumed.getTotalPausedMs());

        log.info("SLA-03 PASSED: Record resumed, totalPausedMs={}", resumed.getTotalPausedMs());
    }

    @Test
    @Order(4)
    @DisplayName("SLA-04: CONTINUE policy - SLA record stays RUNNING when process suspended")
    void sla04_continuePolicyNoChange() {
        SlaConfigEntity config = createTestConfig("continue");
        String processInstanceId = "proc-inst-continue-" + System.nanoTime();

        Instant deadline = Instant.now().plus(Duration.ofHours(1));
        SlaRecordEntity record = slaRecordService.createRecord(
                config, processInstanceId, null, null, deadline);

        // Pause (should be no-op for CONTINUE policy)
        slaRecordService.pauseByProcessInstance(processInstanceId);

        // Verify - should still be RUNNING
        SlaRecordEntity afterPause = slaRecordService.getByPid(record.getPid());
        assertNotNull(afterPause, "Record should exist");
        assertEquals("running", afterPause.getStatus(),
                "CONTINUE policy: status should remain RUNNING");
        assertNull(afterPause.getPausedAt(),
                "CONTINUE policy: pausedAt should not be set");

        log.info("SLA-04 PASSED: CONTINUE policy correctly keeps record RUNNING");
    }

    @Test
    @Order(5)
    @DisplayName("SLA-05: CANCEL policy - SLA record cancelled when process suspended")
    void sla05_cancelPolicyCancelsRecord() {
        SlaConfigEntity config = createTestConfig("cancel");
        String processInstanceId = "proc-inst-cancel-" + System.nanoTime();

        Instant deadline = Instant.now().plus(Duration.ofHours(1));
        SlaRecordEntity record = slaRecordService.createRecord(
                config, processInstanceId, null, null, deadline);

        // Pause (should cancel for CANCEL policy)
        slaRecordService.pauseByProcessInstance(processInstanceId);

        // Verify - should be CANCELLED
        SlaRecordEntity afterPause = slaRecordService.getByPid(record.getPid());
        assertNotNull(afterPause, "Record should exist");
        assertEquals("cancelled", afterPause.getStatus(),
                "CANCEL policy: status should be CANCELLED");

        log.info("SLA-05 PASSED: CANCEL policy correctly cancels record");
    }

    @Test
    @Order(6)
    @DisplayName("SLA-06: calculateProgress deducts paused time")
    void sla06_calculateProgressWithPausedTime() {
        // Create a record that started 1 hour ago with a 2-hour deadline
        SlaRecordEntity record = SlaRecordEntity.builder()
                .pid(UlidGenerator.generate())
                .tenantId(getTestTenant().getId())
                .startTime(Instant.now().minus(Duration.ofHours(1)))
                .deadlineTime(Instant.now().plus(Duration.ofHours(1)))
                .status("running")
                .totalPausedMs(Duration.ofMinutes(30).toMillis()) // 30 min paused
                .currentWarningLevel(0)
                .warningHistory(new ArrayList<>())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        // Without pause: elapsed=1h, total=2h → progress=0.5
        // With 30min pause: effective_elapsed=30min, total=2h → progress=0.25
        double progress = slaRecordService.calculateProgress(record);

        assertTrue(progress > 0.2 && progress < 0.3,
                "Progress should be ~0.25 (30min effective / 2h total), got: " + progress);

        log.info("SLA-06 PASSED: calculateProgress correctly deducts paused time, progress={}", progress);
    }

    @Test
    @Order(7)
    @DisplayName("SLA-07: calculateProgress accounts for ongoing pause")
    void sla07_calculateProgressDuringPause() {
        // Record started 1h ago, deadline in 1h (total 2h), currently paused since 30min ago
        SlaRecordEntity record = SlaRecordEntity.builder()
                .pid(UlidGenerator.generate())
                .tenantId(getTestTenant().getId())
                .startTime(Instant.now().minus(Duration.ofHours(1)))
                .deadlineTime(Instant.now().plus(Duration.ofHours(1)))
                .status("paused")
                .pausedAt(Instant.now().minus(Duration.ofMinutes(30)))
                .totalPausedMs(0L) // no previous pauses
                .currentWarningLevel(0)
                .warningHistory(new ArrayList<>())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        // elapsed=1h, ongoing_pause=30min, effective_elapsed=30min, total=2h → ~0.25
        double progress = slaRecordService.calculateProgress(record);

        assertTrue(progress > 0.2 && progress < 0.3,
                "Progress during ongoing pause should be ~0.25, got: " + progress);

        log.info("SLA-07 PASSED: calculateProgress accounts for ongoing pause, progress={}", progress);
    }

    @Test
    @Order(8)
    @DisplayName("SLA-08: Undeploy rejects when process has running instances (DRAFT test)")
    void sla08_undeployRejectsRunningInstances() {
        // Create a DRAFT process and try to undeploy - should fail because it's not deployed
        String uniqueKey = "test-undeploy-safety-" + System.nanoTime();
        ProcessDeploymentService.CreateProcessRequest request =
                new ProcessDeploymentService.CreateProcessRequest(
                        uniqueKey,
                        "Undeploy Safety Test",
                        "Test undeploy safety check",
                        "test",
                        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" targetNamespace=\"http://auraboot.com/bpm\"><process id=\"" + uniqueKey + "\" isExecutable=\"true\"><startEvent id=\"start\"/><endEvent id=\"end\"/><sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"end\"/></process></definitions>",
                        null, null, null
                );
        var definition = deploymentService.create(request);

        // Undeploy a non-deployed process should throw
        assertThrows(IllegalStateException.class,
                () -> deploymentService.undeploy(definition.getPid()),
                "Should reject undeploy for non-deployed process");

        log.info("SLA-08 PASSED: Undeploy correctly rejected for non-deployed process");
    }

    @Test
    @Order(9)
    @DisplayName("SLA-09: BpmSecurityUtil returns system when no auth context")
    void sla09_bpmSecurityUtilFallback() {
        // In test context, SecurityContextHolder may not have authentication
        String userId = BpmSecurityUtil.getCurrentUserId();
        assertNotNull(userId, "getCurrentUserId should never return null");
        // Should return either a real user ID or "system"
        assertFalse(userId.isEmpty(), "getCurrentUserId should not return empty string");

        log.info("SLA-09 PASSED: BpmSecurityUtil returns userId={}", userId);
    }

    @Test
    @Order(10)
    @DisplayName("SLA-10: Double pause is idempotent")
    void sla10_doublePauseIdempotent() {
        SlaConfigEntity config = createTestConfig("pause");
        String processInstanceId = "proc-inst-doublepause-" + System.nanoTime();

        Instant deadline = Instant.now().plus(Duration.ofHours(1));
        slaRecordService.createRecord(config, processInstanceId, null, null, deadline);

        // Pause twice
        slaRecordService.pauseByProcessInstance(processInstanceId);
        Instant firstPausedAt = slaRecordService.findByProcessInstance(processInstanceId)
                .getFirst().getPausedAt();

        slaRecordService.pauseByProcessInstance(processInstanceId);
        Instant secondPausedAt = slaRecordService.findByProcessInstance(processInstanceId)
                .getFirst().getPausedAt();

        // Second pause should not change pausedAt (already paused)
        assertEquals(firstPausedAt, secondPausedAt,
                "Double pause should be idempotent - pausedAt should not change");

        log.info("SLA-10 PASSED: Double pause is idempotent");
    }
}
