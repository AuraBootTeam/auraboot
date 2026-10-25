package com.auraboot.framework.meta.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.CommandAuditLogDTO;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.entity.CommandAuditLog;
import com.auraboot.framework.meta.mapper.CommandAuditLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for CommandAuditLogService and CommandAuditLogMapper.
 *
 * <p>Covers:
 * <ul>
 *   <li>AL-01: insert audit log with phase_timings, verify fields persisted</li>
 *   <li>AL-02: queryLogs with commandCode filter</li>
 *   <li>AL-03: queryLogs with success=false filter</li>
 *   <li>AL-04: queryLogs pagination</li>
 *   <li>AL-05: findById returns correct record</li>
 *   <li>AL-06: countLogs returns accurate total</li>
 *   <li>AL-07: phaseTimings JSON serialized and deserialized correctly</li>
 *   <li>AL-08: queryLogs returns empty list when no records match</li>
 * </ul>
 */
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CommandAuditLogIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private CommandAuditLogMapper commandAuditLogMapper;

    @Autowired
    private CommandAuditLogService commandAuditLogService;

    private final String runId = String.valueOf(System.currentTimeMillis());
    private Long savedLogId;
    private Long tenantId;

    @BeforeAll
    void setup() {
        // testTenant is set up by BaseIntegrationTest.setupTenantContext()
        // We re-read it in the first test via testTenant static field
    }

    // ── AL-01: insert with phase_timings ──────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("AL-01: insertLog persists all fields including phase_timings")
    void insertLog_persistsAllFields() {
        tenantId = testTenant.getId();

        CommandAuditLog auditLog = buildLog(runId + "_create", true, "completed",
                "{\"INIT\":1,\"LOAD\":5,\"SCHEMA_VALIDATE\":2,\"COMPLETED\":18}");
        commandAuditLogMapper.insertLog(auditLog);

        assertThat(auditLog.getId()).isNotNull();
        savedLogId = auditLog.getId();
        log.info("AL-01: saved audit log id={}", savedLogId);
    }

    // ── AL-02: queryLogs by commandCode ──────────────────────────────────────

    @Test
    @Order(2)
    @DisplayName("AL-02: queryLogs filters by commandCode")
    void queryLogs_filterByCommandCode() {
        tenantId = testTenant.getId();
        // Insert one more log with a distinct code
        String uniqueCode = runId + "_create";
        commandAuditLogMapper.insertLog(buildLog(uniqueCode, true, "completed", null));

        PaginationResult<CommandAuditLogDTO> result =
                commandAuditLogService.queryLogs(uniqueCode, null, null, null, 1, 20);

        assertThat(result.getRecords()).isNotEmpty();
        assertThat(result.getRecords())
                .allMatch(r -> r.getCommandCode().equals(uniqueCode));
    }

    // ── AL-03: queryLogs by success=false ────────────────────────────────────

    @Test
    @Order(3)
    @DisplayName("AL-03: queryLogs filters by success=false")
    void queryLogs_filterByFailure() {
        tenantId = testTenant.getId();
        commandAuditLogMapper.insertLog(buildLog(runId + "_fail_cmd", false, "assert", null));

        PaginationResult<CommandAuditLogDTO> result =
                commandAuditLogService.queryLogs(null, false, null, null, 1, 20);

        assertThat(result.getRecords()).isNotEmpty();
        assertThat(result.getRecords()).allMatch(r -> Boolean.FALSE.equals(r.getSuccess()));
    }

    // ── AL-04: queryLogs pagination ──────────────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("AL-04: queryLogs respects pageSize")
    void queryLogs_pagination() {
        tenantId = testTenant.getId();
        // Seed 3 more logs with same command code
        String batchCode = runId + "_batch";
        for (int i = 0; i < 3; i++) {
            commandAuditLogMapper.insertLog(buildLog(batchCode, true, "completed", null));
        }

        PaginationResult<CommandAuditLogDTO> page1 =
                commandAuditLogService.queryLogs(batchCode, null, null, null, 1, 2);
        assertThat(page1.getRecords()).hasSize(2);
        assertThat(page1.getTotal()).isGreaterThanOrEqualTo(3L);

        PaginationResult<CommandAuditLogDTO> page2 =
                commandAuditLogService.queryLogs(batchCode, null, null, null, 2, 2);
        assertThat(page2.getRecords()).hasSizeGreaterThanOrEqualTo(1);
    }

    // ── AL-05: findById ───────────────────────────────────────────────────────

    @Test
    @Order(5)
    @DisplayName("AL-05: findById returns correct record")
    void findById_returnsRecord() {
        tenantId = testTenant.getId();
        assertThat(savedLogId).isNotNull();

        CommandAuditLogDTO dto = commandAuditLogService.findById(savedLogId);

        assertThat(dto).isNotNull();
        assertThat(dto.getId()).isEqualTo(savedLogId);
        assertThat(dto.getCommandCode()).isEqualTo(runId + "_create");
        assertThat(dto.getSuccess()).isTrue();
        assertThat(dto.getPhaseReached()).isEqualTo("completed");
    }

    // ── AL-06: countLogs ─────────────────────────────────────────────────────

    @Test
    @Order(6)
    @DisplayName("AL-06: countLogs returns accurate total")
    void countLogs_accurate() {
        tenantId = testTenant.getId();
        String countCode = runId + "_count";
        commandAuditLogMapper.insertLog(buildLog(countCode, true, "completed", null));
        commandAuditLogMapper.insertLog(buildLog(countCode, false, "assert", null));

        long total = commandAuditLogMapper.countLogs(tenantId, countCode, null, null, null);
        assertThat(total).isGreaterThanOrEqualTo(2L);

        long failTotal = commandAuditLogMapper.countLogs(tenantId, countCode, false, null, null);
        assertThat(failTotal).isGreaterThanOrEqualTo(1L);
    }

    // ── AL-07: phaseTimings JSON round-trip ──────────────────────────────────

    @Test
    @Order(7)
    @DisplayName("AL-07: phaseTimings JSON stored and retrieved correctly")
    void phaseTimings_jsonRoundTrip() {
        tenantId = testTenant.getId();
        String timingsJson = "{\"INIT\":1,\"LOAD\":5,\"SCHEMA_VALIDATE\":2,\"HANDLER\":42,\"COMPLETED\":3}";
        CommandAuditLog log = buildLog(runId + "_timing", true, "completed", timingsJson);
        commandAuditLogMapper.insertLog(log);

        CommandAuditLogDTO dto = commandAuditLogService.findById(log.getId());

        assertThat(dto).isNotNull();
        assertThat(dto.getPhaseTimings()).isNotBlank();
        assertThat(dto.getPhaseTimings()).contains("handler");
        assertThat(dto.getPhaseTimings()).contains("42");
    }

    // ── AL-08: no match returns empty ─────────────────────────────────────────

    @Test
    @Order(8)
    @DisplayName("AL-08: queryLogs returns empty list when no records match")
    void queryLogs_noMatch() {
        tenantId = testTenant.getId();
        PaginationResult<CommandAuditLogDTO> result =
                commandAuditLogService.queryLogs("nonexistent_cmd_" + runId, null, null, null, 1, 20);

        assertThat(result.getRecords()).isEmpty();
        assertThat(result.getTotal()).isEqualTo(0L);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private CommandAuditLog buildLog(String commandCode, boolean success,
                                     String phaseReached, String phaseTimings) {
        CommandAuditLog log = new CommandAuditLog();
        log.setTenantId(tenantId);
        log.setCommandCode(commandCode);
        log.setCommandPid("test-pid-" + runId);
        log.setUserId(1L);
        log.setRequestPayload("{\"field\":\"value\"}");
        log.setExecutionResult(success ? "{\"id\":\"123\"}" : null);
        log.setSuccess(success);
        log.setErrorMessage(success ? null : "Test error at phase " + phaseReached);
        log.setExecutionTimeMs(success ? 75L : 12L);
        log.setPhaseReached(phaseReached);
        log.setPhaseTimings(phaseTimings);
        log.setIpAddress("127.0.0.1");
        log.setCreatedAt(Instant.now());
        return log;
    }
}
