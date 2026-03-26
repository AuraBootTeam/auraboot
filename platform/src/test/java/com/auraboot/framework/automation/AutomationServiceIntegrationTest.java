package com.auraboot.framework.automation;

import com.auraboot.framework.automation.dto.AutomationDTO;
import com.auraboot.framework.automation.dto.AutomationCreateRequest;
import com.auraboot.framework.automation.dto.AutomationLogDTO;
import com.auraboot.framework.automation.dto.AutomationUpdateRequest;
import com.auraboot.framework.automation.service.AutomationService;
import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for AutomationService.
 * Covers CRUD, search, state transitions (enable/disable/toggle),
 * duplicate, validate, and log management.
 * Uses real database, no mocking. Data persists (no rollback).
 *
 * Note: triggerManually() is NOT tested — it depends on AutomationTriggerService (external engine).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class AutomationServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AutomationService automationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String testRunId = String.valueOf(System.currentTimeMillis());
    private final String modelCode = "test-model-" + testRunId;

    // Shared state across ordered tests
    private String createdPid;

    // ========== Helper ==========

    /**
     * Build a minimal valid AutomationCreateRequest.
     * Uses flowConfig to bypass the service-level "actions required" check.
     * Still provides modelCode and triggerType to satisfy DB NOT NULL constraints.
     */
    private AutomationCreateRequest buildRequest(String name) {
        AutomationCreateRequest req = new AutomationCreateRequest();
        req.setName(name);
        req.setModelCode(modelCode);
        req.setTriggerType("on_record_create");
        req.setActions(List.of()); // empty list satisfies DB NOT NULL DEFAULT '[]'
        req.setFlowConfig(Map.of("type", "simple")); // non-empty flowConfig bypasses actions validation
        req.setEnabled(false);
        return req;
    }

    // ========== Test 1: create ==========

    @Test
    @Order(1)
    void create_withValidRequest_returnsAutomationDTO() {
        AutomationDTO dto = automationService.create(buildRequest("Auto-" + testRunId));

        assertNotNull(dto, "create() must return a non-null DTO");
        assertNotNull(dto.getPid(), "Created automation must have a pid");
        assertEquals("Auto-" + testRunId, dto.getName());
        assertEquals(modelCode, dto.getModelCode());
        assertFalse(dto.getEnabled(), "Default enabled should be false");
        createdPid = dto.getPid();
    }

    // ========== Test 2: findByPid existing ==========

    @Test
    @Order(2)
    void findByPid_existingAutomation_returnsDTO() {
        assertNotNull(createdPid, "createdPid must be set by test 1");

        AutomationDTO dto = automationService.findByPid(createdPid);

        assertNotNull(dto, "findByPid must return the created automation");
        assertEquals(createdPid, dto.getPid());
    }

    // ========== Test 3: findByPid non-existent returns null ==========

    @Test
    @Order(3)
    void findByPid_nonExistent_returnsNull() {
        String fakePid = "nonexistent-" + testRunId;

        AutomationDTO result = automationService.findByPid(fakePid);

        assertNull(result, "Non-existent pid should return null");
    }

    // ========== Test 4: getByModelCode ==========

    @Test
    @Order(4)
    void getByModelCode_returnsMatchingAutomations() {
        List<AutomationDTO> list = automationService.getByModelCode(modelCode);

        assertNotNull(list);
        assertTrue(list.size() >= 1, "Should find at least the automation created in test 1");
        assertTrue(list.stream().allMatch(a -> modelCode.equals(a.getModelCode())),
                "All returned automations should have the correct modelCode");
    }

    // ========== Test 5: enable → getEnabledByModelCode ==========

    @Test
    @Order(5)
    void enable_andGetEnabledByModelCode_returnsOnlyEnabled() {
        AutomationDTO enabled = automationService.enable(createdPid);
        assertTrue(enabled.getEnabled(), "enable() must return DTO with enabled=true");

        List<AutomationDTO> enabledList = automationService.getEnabledByModelCode(modelCode);

        assertTrue(enabledList.stream().anyMatch(a -> createdPid.equals(a.getPid())),
                "Enabled automation must appear in getEnabledByModelCode results");
        assertTrue(enabledList.stream().allMatch(AutomationDTO::getEnabled),
                "All returned automations must be enabled");
    }

    // ========== Test 6: search by keyword ==========

    @Test
    @Order(6)
    void search_byKeyword_returnsMatching() {
        String uniqueName = "SearchTarget-" + testRunId;
        AutomationCreateRequest searchReq = buildRequest(uniqueName);
        automationService.create(searchReq);

        PageResult<AutomationDTO> result = automationService.search(uniqueName, null, null, null, 1, 10);

        assertNotNull(result);
        assertTrue(result.getTotal() >= 1,
                "Search by keyword '" + uniqueName + "' should find at least 1 automation");
    }

    // ========== Test 7: search by triggerType ==========

    @Test
    @Order(7)
    void search_byTriggerType_returnsFiltered() {
        PageResult<AutomationDTO> result = automationService.search(
                null, modelCode, "on_record_create", null, 1, 50);

        assertNotNull(result);
        assertTrue(result.getTotal() >= 1);
        result.getRecords().forEach(a ->
                assertEquals("on_record_create", a.getTriggerType(),
                        "All results must have the requested triggerType"));
    }

    // ========== Test 8: update ==========

    @Test
    @Order(8)
    void update_changesNameAndDescription() {
        AutomationUpdateRequest req = new AutomationUpdateRequest();
        req.setName("Updated-" + testRunId);
        req.setDescription("Updated description " + testRunId);

        AutomationDTO updated = automationService.update(createdPid, req);

        assertNotNull(updated);
        assertEquals("Updated-" + testRunId, updated.getName());
        assertEquals("Updated description " + testRunId, updated.getDescription());
    }

    // ========== Test 9: disable ==========

    @Test
    @Order(9)
    void disable_updatesEnabledToFalse() {
        // Was enabled in test 5
        AutomationDTO disabled = automationService.disable(createdPid);

        assertNotNull(disabled);
        assertFalse(disabled.getEnabled(), "disable() must set enabled=false");
    }

    // ========== Test 10: toggle flips state ==========

    @Test
    @Order(10)
    void toggle_flipsEnabledState() {
        // Currently disabled (from test 9)
        AutomationDTO toggled = automationService.toggle(createdPid);
        assertTrue(toggled.getEnabled(), "toggle() on disabled should flip to true");

        AutomationDTO toggledBack = automationService.toggle(createdPid);
        assertFalse(toggledBack.getEnabled(), "toggle() again should flip back to false");
    }

    // ========== Test 11: duplicate ==========

    @Test
    @Order(11)
    void duplicate_createsNewRecordWithSameName() {
        AutomationDTO copy = automationService.duplicate(createdPid);

        assertNotNull(copy, "duplicate() must return a new AutomationDTO");
        assertNotEquals(createdPid, copy.getPid(), "Duplicate must have a different pid");
        assertEquals(modelCode, copy.getModelCode(), "Duplicate should have the same modelCode");
        assertFalse(copy.getEnabled(), "Duplicate should start as disabled");
    }

    // ========== Test 12: validate valid request ==========

    @Test
    @Order(12)
    void validate_withValidRequest_returnsValidResult() {
        AutomationCreateRequest req = buildRequest("ValidateTest-" + testRunId);

        Map<String, Object> result = automationService.validate(req);

        assertNotNull(result, "validate() must return a non-null Map");
        assertTrue((Boolean) result.get("valid"),
                "A valid request should return valid=true");
        List<?> errors = (List<?>) result.get("errors");
        assertTrue(errors == null || errors.isEmpty(),
                "A valid request should have no errors");
    }

    // ========== Test 13: validate invalid request ==========

    @Test
    @Order(13)
    void validate_withMissingName_returnsInvalid() {
        AutomationCreateRequest req = new AutomationCreateRequest();
        // No name set — should fail validation

        Map<String, Object> result = automationService.validate(req);

        assertNotNull(result);
        assertFalse((Boolean) result.get("valid"),
                "A request missing the name should return valid=false");
        List<?> errors = (List<?>) result.get("errors");
        assertNotNull(errors);
        assertFalse(errors.isEmpty(), "Validation errors should be populated");
    }

    // ========== Test 14: getLogs ==========

    @Test
    @Order(14)
    void getLogs_returnsLogsForAutomation() {
        Long tenantId = getTestTenant().getId();
        String logPid = "log-" + testRunId;
        jdbcTemplate.update(
                "INSERT INTO ab_automation_log (pid, tenant_id, automation_id, trigger_type, status, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
                logPid, tenantId, createdPid, "on_record_create", "success",
                Timestamp.from(Instant.now()));

        List<AutomationLogDTO> logs = automationService.getLogs(createdPid, 10);

        assertNotNull(logs);
        assertTrue(logs.stream().anyMatch(l -> logPid.equals(l.getPid())),
                "getLogs must include the manually inserted log");
    }

    // ========== Test 15: getRecentFailedLogs ==========

    @Test
    @Order(15)
    void getRecentFailedLogs_returnsFailedLogs() {
        Long tenantId = getTestTenant().getId();
        String failLogPid = "fail-log-" + testRunId;
        jdbcTemplate.update(
                "INSERT INTO ab_automation_log (pid, tenant_id, automation_id, trigger_type, status, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
                failLogPid, tenantId, createdPid, "on_record_create", "failed",
                Timestamp.from(Instant.now()));

        List<AutomationLogDTO> failedLogs = automationService.getRecentFailedLogs(100);

        assertNotNull(failedLogs);
        assertTrue(failedLogs.stream().anyMatch(l -> failLogPid.equals(l.getPid())),
                "getRecentFailedLogs must include the FAILED log we inserted");
        assertTrue(failedLogs.stream().allMatch(l -> "failed".equals(l.getStatus())),
                "getRecentFailedLogs must only return FAILED logs");
    }

    // ========== Test 16: cleanupOldLogs ==========

    @Test
    @Order(16)
    void cleanupOldLogs_removesExpiredLogs() {
        Long tenantId = getTestTenant().getId();
        String oldLogPid = "old-log-" + testRunId;
        Instant thirtyOneDaysAgo = Instant.now().minus(31, ChronoUnit.DAYS);
        jdbcTemplate.update(
                "INSERT INTO ab_automation_log (pid, tenant_id, automation_id, trigger_type, status, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
                oldLogPid, tenantId, createdPid, "scheduled", "success",
                Timestamp.from(thirtyOneDaysAgo));

        int removed = automationService.cleanupOldLogs(30);

        assertTrue(removed >= 1, "cleanupOldLogs(30) should remove at least 1 log older than 30 days");

        List<Map<String, Object>> check = jdbcTemplate.queryForList(
                "SELECT id FROM ab_automation_log WHERE pid = ?", oldLogPid);
        assertTrue(check.isEmpty(), "The old log should have been physically removed");
    }

    // ========== Test 17: delete removes record ==========

    @Test
    @Order(17)
    void delete_removesRecord() {
        AutomationDTO toDelete = automationService.create(buildRequest("ToDelete-" + testRunId));
        String deletePid = toDelete.getPid();

        automationService.delete(deletePid);

        AutomationDTO result = automationService.findByPid(deletePid);
        assertNull(result, "Deleted automation should not be found via findByPid");
    }
}
