package com.auraboot.framework.bpm;

import com.auraboot.framework.bpm.entity.SlaConfigEntity;
import com.auraboot.framework.bpm.entity.SlaRecordEntity;
import com.auraboot.framework.bpm.mapper.SlaConfigMapper;
import com.auraboot.framework.bpm.mapper.SlaRecordMapper;
import com.auraboot.framework.bpm.service.ProcessDeploymentService;
import com.auraboot.framework.bpm.service.SlaConfigService;
import com.auraboot.framework.bpm.service.SlaRecordService;
import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for BPM dashboard statistics.
 * Verifies process definition counts, SLA config counts,
 * and active SLA record counts are computed correctly.
 *
 * @author AuraBoot Team
 */
@Slf4j
@DisplayName("BPM Dashboard Statistics Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BpmDashboardTest extends BaseIntegrationTest {

    @Autowired
    private ProcessDeploymentService deploymentService;

    @Autowired
    private SlaConfigService slaConfigService;

    @Autowired
    private SlaRecordService slaRecordService;

    @Autowired
    private SlaConfigMapper slaConfigMapper;

    @Autowired
    private SlaRecordMapper slaRecordMapper;

    // ==================== DASH-01: Empty state ====================

    @Test
    @Order(1)
    @DisplayName("DASH-01: Process definitions list returns valid result (may be empty or not)")
    void dash01_processDefinitionsList() {
        // Act
        var definitions = deploymentService.listProcessDefinitions();

        // Assert
        assertNotNull(definitions, "Definitions list should never be null");
        log.info("DASH-01 PASSED: Process definitions list returned {} items", definitions.size());
    }

    // ==================== DASH-02: SLA config statistics ====================

    @Test
    @Order(2)
    @DisplayName("DASH-02: SLA config count reflects created configs")
    void dash02_slaConfigCount() {
        // Arrange: create an SLA config
        SlaConfigEntity config = SlaConfigEntity.builder()
                .pid(UlidGenerator.generate())
                .tenantId(getTestTenant().getId())
                .name("Dashboard Test SLA")
                .targetType("process")
                .targetKey("dash-test-process")
                .deadlineMode("fixed")
                .deadlineValue("pt2h")
                .suspendPolicy("pause")
                .enabled(true)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        slaConfigMapper.insert(config);

        // Act
        List<SlaConfigEntity> allConfigs = slaConfigService.listAll();
        long enabledCount = allConfigs.stream().filter(c -> Boolean.TRUE.equals(c.getEnabled())).count();

        // Assert
        assertTrue(allConfigs.size() >= 1, "Should have at least 1 config");
        assertTrue(enabledCount >= 1, "Should have at least 1 enabled config");

        log.info("DASH-02 PASSED: SLA configs total={}, enabled={}", allConfigs.size(), enabledCount);
    }

    // ==================== DASH-03: Active SLA record statistics ====================

    @Test
    @Order(3)
    @DisplayName("DASH-03: Active SLA records counted correctly")
    void dash03_activeSlaRecords() {
        // Arrange: create SLA config + record
        SlaConfigEntity config = SlaConfigEntity.builder()
                .pid(UlidGenerator.generate())
                .tenantId(getTestTenant().getId())
                .name("Dashboard Active Record SLA")
                .targetType("process")
                .targetKey("dash-active-process")
                .deadlineMode("fixed")
                .deadlineValue("pt1h")
                .suspendPolicy("pause")
                .enabled(true)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        slaConfigMapper.insert(config);

        // Create a running SLA record
        Instant deadline = Instant.now().plus(Duration.ofHours(1));
        SlaRecordEntity record = slaRecordService.createRecord(
                config, "dash-proc-inst-" + System.nanoTime(), null, null, deadline);

        // Act
        List<SlaRecordEntity> activeRecords = slaRecordService.getActiveRecords();

        // Assert
        assertNotNull(activeRecords, "Active records list should not be null");
        assertTrue(activeRecords.size() >= 1, "Should have at least 1 active record");

        long runningCount = activeRecords.stream()
                .filter(r -> "running".equals(r.getStatus())).count();
        assertTrue(runningCount >= 1, "Should have at least 1 running record");

        log.info("DASH-03 PASSED: Active records={}, running={}", activeRecords.size(), runningCount);
    }
}
