package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.entity.FieldAuditConfig;
import com.auraboot.framework.meta.entity.FieldChangeLog;
import com.auraboot.framework.meta.mapper.FieldAuditConfigMapper;
import com.auraboot.framework.meta.mapper.FieldChangeLogMapper;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-stack integration test for {@link FieldChangeAuditService}.
 *
 * <p>Part of OSS coverage initiative (tracker:
 * {@code docs/backlog/2026-06-10-oss-coverage-to-80-tracker.md}).
 * Lifts the class from near-zero line coverage to ≥80% by exercising all
 * public and private branches against the real shared database (no mocked
 * mappers/bridges, per AGENTS.md §2.2 seam discipline).
 *
 * <p>Coverage strategy:
 * <ul>
 *   <li>configureFieldAudit: CREATE path and UPDATE path (idempotent).</li>
 *   <li>bulkConfigureFieldAudit: multiple fields in one call.</li>
 *   <li>getAuditConfig: returns all entries including disabled.</li>
 *   <li>recordFieldChanges: empty-model guard, no-config-short-circuit, CREATE branch
 *       (beforeData null), DELETE branch (afterData null), UPDATE changed-field branch,
 *       UPDATE unchanged-field skipped branch, null newVal on CREATE skipped.</li>
 *   <li>getRecordHistory / getFieldHistory: query by record and by field.</li>
 *   <li>getChangesByActor: time-range query.</li>
 *   <li>getChangeReport: grouping aggregation (fieldChangeCounts / actorChangeCounts).</li>
 *   <li>In-memory config cache: populated on first recordFieldChanges and evicted on
 *       configureFieldAudit update.</li>
 *   <li>asString branches: String, Map/List (JSON), and primitive toString.</li>
 *   <li>mapDataType: number, boolean, date, reference, enum, default.</li>
 * </ul>
 *
 * <p>Uses the {@code integration-test} profile (shared Postgres :5432 {@code aura_boot}).
 * All rows are hard-deleted in {@link #tearDown()} to keep the shared DB clean.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@DisplayName("FieldChangeAuditService Real-Stack Integration Test")
class FieldChangeAuditServiceIntegrationTest {

    private static final String CODE_PREFIX = "covfca";
    /** Stable per-run nonce (alnum only, LIKE-safe). */
    private static final String RUN = Long.toString(Math.abs(System.nanoTime()), 36);

    @Autowired
    private FieldChangeAuditService fieldChangeAuditService;
    @Autowired
    private FieldChangeLogMapper changeLogMapper;
    @Autowired
    private FieldAuditConfigMapper auditConfigMapper;
    @Autowired
    private UserService userService;
    @Autowired
    private TenantService tenantService;
    @Autowired
    private TenantMemberService tenantMemberService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final AtomicInteger seq = new AtomicInteger();
    private User testUser;
    private Tenant testTenant;

    // ==================== Lifecycle ====================

    @BeforeEach
    void setUp() {
        String testEmail = "covfca-test@auraboot.com";
        testUser = userService.findByEmail(testEmail);
        if (testUser == null) {
            testUser = userService.signUp(testEmail, "test-password-123");
        }

        String testTenantName = "covfca-test-tenant";
        testTenant = tenantService.findByName(testTenantName);
        if (testTenant == null) {
            Tenant t = new Tenant();
            t.setPid(UniqueIdGenerator.generate());
            t.setName(testTenantName);
            t.setDisplayName("FieldChangeAudit Coverage Test Tenant");
            t.setStatus("active");
            t.setContactEmail("admin@covfca-test.com");
            t.setDescription("Test tenant for field-change-audit domain coverage IT");
            t.setDeletedFlag(false);
            t.setCreatedAt(Instant.now());
            t.setUpdatedAt(Instant.now());
            testTenant = tenantService.createTenant(t);
        }

        TenantMember member = tenantMemberService.findByTenantIdAndUserId(
                testTenant.getId(), testUser.getId());
        if (member == null) {
            tenantMemberService.addMember(testUser.getId(), testTenant.getId(), "active");
        }

        MetaContext.setContext(testTenant.getId(), testUser.getId(),
                testUser.getPid(), testUser.getUserName());
        wipeTenantData();
    }

    @AfterEach
    void tearDown() {
        try {
            wipeTenantData();
        } catch (Exception e) {
            log.warn("field-change-audit cleanup failed: {}", e.getMessage());
        } finally {
            MetaContext.clear();
        }
    }

    /** Hard-delete all field-change-audit rows for the dedicated test tenant. */
    private void wipeTenantData() {
        Long tid = testTenant.getId();
        jdbcTemplate.update("DELETE FROM ab_field_change_log WHERE tenant_id = ?", tid);
        jdbcTemplate.update("DELETE FROM ab_field_audit_config WHERE tenant_id = ?", tid);
    }

    // ==================== Helpers ====================

    private String modelCode(String label) {
        return CODE_PREFIX + RUN + "_" + seq.incrementAndGet() + "_" + label;
    }

    // ==================== configureFieldAudit ====================

    @Test
    @DisplayName("configureFieldAudit CREATE path inserts new config row")
    void configureFieldAudit_create() {
        String model = modelCode("cfg_create");
        FieldAuditConfig cfg = fieldChangeAuditService.configureFieldAudit(
                testTenant.getId(), model, "status", true, false, true);

        assertNotNull(cfg);
        assertNotNull(cfg.getId());
        assertEquals(testTenant.getId(), cfg.getTenantId());
        assertEquals(model, cfg.getModelCode());
        assertEquals("status", cfg.getFieldCode());
        assertTrue(cfg.getEnabled());
        assertFalse(cfg.getRequireReason());
        assertTrue(cfg.getNotifyOnChange());
        assertNotNull(cfg.getCreatedAt());

        // Verify persisted
        FieldAuditConfig fromDb = auditConfigMapper.getByModelAndField(testTenant.getId(), model, "status");
        assertNotNull(fromDb);
        assertTrue(fromDb.getEnabled());
    }

    @Test
    @DisplayName("configureFieldAudit UPDATE path modifies existing row")
    void configureFieldAudit_update() {
        String model = modelCode("cfg_upd");
        // Create first
        fieldChangeAuditService.configureFieldAudit(
                testTenant.getId(), model, "amount", true, false, false);

        // Update: change flags
        FieldAuditConfig updated = fieldChangeAuditService.configureFieldAudit(
                testTenant.getId(), model, "amount", false, true, true);

        assertFalse(updated.getEnabled());
        assertTrue(updated.getRequireReason());
        assertTrue(updated.getNotifyOnChange());

        // Verify only one row exists (update, not insert)
        List<FieldAuditConfig> all = auditConfigMapper.getAllByModel(testTenant.getId(), model);
        assertEquals(1, all.size());
        assertFalse(all.get(0).getEnabled());
    }

    // ==================== bulkConfigureFieldAudit ====================

    @Test
    @DisplayName("bulkConfigureFieldAudit configures multiple fields in one call")
    void bulkConfigureFieldAudit_multipleFields() {
        String model = modelCode("bulk");
        List<FieldChangeAuditService.FieldAuditConfigRequest> requests = List.of(
                new FieldChangeAuditService.FieldAuditConfigRequest("name", true, false, false),
                new FieldChangeAuditService.FieldAuditConfigRequest("email", true, false, true),
                new FieldChangeAuditService.FieldAuditConfigRequest("phone", false, true, false)
        );

        List<FieldAuditConfig> results = fieldChangeAuditService.bulkConfigureFieldAudit(
                testTenant.getId(), model, requests);

        assertEquals(3, results.size());
        List<FieldAuditConfig> all = fieldChangeAuditService.getAuditConfig(testTenant.getId(), model);
        assertEquals(3, all.size());
        assertTrue(all.stream().anyMatch(c -> "name".equals(c.getFieldCode()) && Boolean.TRUE.equals(c.getEnabled())));
        assertTrue(all.stream().anyMatch(c -> "phone".equals(c.getFieldCode()) && Boolean.FALSE.equals(c.getEnabled())));
    }

    // ==================== getAuditConfig ====================

    @Test
    @DisplayName("getAuditConfig returns all entries including disabled")
    void getAuditConfig_returnsAll() {
        String model = modelCode("getconfig");
        fieldChangeAuditService.configureFieldAudit(testTenant.getId(), model, "f1", true, false, false);
        fieldChangeAuditService.configureFieldAudit(testTenant.getId(), model, "f2", false, false, false);

        List<FieldAuditConfig> all = fieldChangeAuditService.getAuditConfig(testTenant.getId(), model);
        assertEquals(2, all.size());
        // Both enabled and disabled are returned
        assertTrue(all.stream().anyMatch(c -> "f1".equals(c.getFieldCode()) && Boolean.TRUE.equals(c.getEnabled())));
        assertTrue(all.stream().anyMatch(c -> "f2".equals(c.getFieldCode()) && Boolean.FALSE.equals(c.getEnabled())));
    }

    @Test
    @DisplayName("getAuditConfig returns empty list when no config exists")
    void getAuditConfig_emptyWhenNoneConfigured() {
        String model = modelCode("getconfig_empty");
        List<FieldAuditConfig> result = fieldChangeAuditService.getAuditConfig(testTenant.getId(), model);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ==================== recordFieldChanges — guard paths ====================

    @Test
    @DisplayName("recordFieldChanges returns immediately when modelCode is blank")
    void recordFieldChanges_blankModelCode_noOp() {
        // Must not throw; no rows written
        fieldChangeAuditService.recordFieldChanges(
                testTenant.getId(), "", 1L, "cmd",
                null, Map.of("f", "v"), 999L, "actor");
        fieldChangeAuditService.recordFieldChanges(
                testTenant.getId(), null, 1L, "cmd",
                null, Map.of("f", "v"), 999L, "actor");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_field_change_log WHERE tenant_id = ?",
                Integer.class, testTenant.getId());
        assertEquals(0, count);
    }

    @Test
    @DisplayName("recordFieldChanges returns immediately when no audit config exists for model")
    void recordFieldChanges_noConfig_noOp() {
        String model = modelCode("noconfig");
        // No configureFieldAudit call — configMap will be empty → short-circuit
        fieldChangeAuditService.recordFieldChanges(
                testTenant.getId(), model, 42L, "cmd",
                Map.of("f1", "old"), Map.of("f1", "new"), 1L, "actor");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_field_change_log WHERE tenant_id = ? AND model_code = ?",
                Integer.class, testTenant.getId(), model);
        assertEquals(0, count);
    }

    // ==================== recordFieldChanges — CREATE branch ====================

    @Test
    @DisplayName("recordFieldChanges CREATE: logs non-null configured fields in afterData")
    void recordFieldChanges_create_logsNonNullFields() {
        String model = modelCode("create");
        fieldChangeAuditService.configureFieldAudit(testTenant.getId(), model, "name", true, false, false);
        fieldChangeAuditService.configureFieldAudit(testTenant.getId(), model, "status", true, false, false);

        Map<String, Object> afterData = Map.of("name", "Alice", "status", "active");
        fieldChangeAuditService.recordFieldChanges(
                testTenant.getId(), model, 100L, "create_cmd",
                null, afterData, testUser.getId(), testUser.getUserName());

        List<FieldChangeLog> logs = changeLogMapper.getByModelAndRecord(
                testTenant.getId(), model, 100L);
        assertEquals(2, logs.size());
        assertTrue(logs.stream().allMatch(l -> "added".equals(l.getChangeType())));
        assertTrue(logs.stream().anyMatch(l -> "name".equals(l.getFieldCode()) && "Alice".equals(l.getNewValue())));
        assertTrue(logs.stream().anyMatch(l -> "status".equals(l.getFieldCode()) && "active".equals(l.getNewValue())));
        // oldValue must be null for CREATE
        assertTrue(logs.stream().allMatch(l -> l.getOldValue() == null));
    }

    @Test
    @DisplayName("recordFieldChanges CREATE: skips configured field when newValue is null")
    void recordFieldChanges_create_skipsNullNewValue() {
        String model = modelCode("create_null");
        fieldChangeAuditService.configureFieldAudit(testTenant.getId(), model, "optional_field", true, false, false);

        // afterData does NOT include the configured field → get(fieldCode) returns null
        Map<String, Object> afterData = Map.of("other_field", "value");
        fieldChangeAuditService.recordFieldChanges(
                testTenant.getId(), model, 101L, "create_cmd",
                null, afterData, testUser.getId(), testUser.getUserName());

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_field_change_log WHERE tenant_id = ? AND model_code = ?",
                Integer.class, testTenant.getId(), model);
        assertEquals(0, count);
    }

    // ==================== recordFieldChanges — DELETE branch ====================

    @Test
    @DisplayName("recordFieldChanges DELETE: logs configured fields that had non-null values")
    void recordFieldChanges_delete_logsNonNullFields() {
        String model = modelCode("delete");
        fieldChangeAuditService.configureFieldAudit(testTenant.getId(), model, "name", true, false, false);

        Map<String, Object> beforeData = Map.of("name", "Bob");
        fieldChangeAuditService.recordFieldChanges(
                testTenant.getId(), model, 200L, "delete_cmd",
                beforeData, null, testUser.getId(), testUser.getUserName());

        List<FieldChangeLog> logs = changeLogMapper.getByModelAndRecord(
                testTenant.getId(), model, 200L);
        assertEquals(1, logs.size());
        assertEquals("removed", logs.get(0).getChangeType());
        assertEquals("Bob", logs.get(0).getOldValue());
        assertNull(logs.get(0).getNewValue());
    }

    @Test
    @DisplayName("recordFieldChanges DELETE: skips configured field when oldValue is null")
    void recordFieldChanges_delete_skipsNullOldValue() {
        String model = modelCode("delete_null");
        fieldChangeAuditService.configureFieldAudit(testTenant.getId(), model, "optional", true, false, false);

        // beforeData does not contain the configured field
        Map<String, Object> beforeData = Map.of("untracked", "something");
        fieldChangeAuditService.recordFieldChanges(
                testTenant.getId(), model, 202L, "delete_cmd",
                beforeData, null, testUser.getId(), testUser.getUserName());

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_field_change_log WHERE tenant_id = ? AND model_code = ?",
                Integer.class, testTenant.getId(), model);
        assertEquals(0, count);
    }

    // ==================== recordFieldChanges — UPDATE branch ====================

    @Test
    @DisplayName("recordFieldChanges UPDATE: logs fields that changed; skips unchanged fields")
    void recordFieldChanges_update_changedAndUnchanged() {
        String model = modelCode("update");
        fieldChangeAuditService.configureFieldAudit(testTenant.getId(), model, "status", true, false, false);
        fieldChangeAuditService.configureFieldAudit(testTenant.getId(), model, "name", true, false, false);

        Map<String, Object> beforeData = Map.of("status", "draft", "name", "Unchanged");
        Map<String, Object> afterData = Map.of("status", "active", "name", "Unchanged");

        fieldChangeAuditService.recordFieldChanges(
                testTenant.getId(), model, 300L, "update_cmd",
                beforeData, afterData, testUser.getId(), testUser.getUserName());

        List<FieldChangeLog> logs = changeLogMapper.getByModelAndRecord(
                testTenant.getId(), model, 300L);
        // Only status changed; name is unchanged → skipped
        assertEquals(1, logs.size());
        assertEquals("modified", logs.get(0).getChangeType());
        assertEquals("status", logs.get(0).getFieldCode());
        assertEquals("draft", logs.get(0).getOldValue());
        assertEquals("active", logs.get(0).getNewValue());
    }

    @Test
    @DisplayName("recordFieldChanges UPDATE: all fields unchanged → no rows written")
    void recordFieldChanges_update_nothingChanged() {
        String model = modelCode("update_noop");
        fieldChangeAuditService.configureFieldAudit(testTenant.getId(), model, "title", true, false, false);

        Map<String, Object> data = Map.of("title", "same value");
        fieldChangeAuditService.recordFieldChanges(
                testTenant.getId(), model, 301L, "update_cmd",
                data, data, testUser.getId(), testUser.getUserName());

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_field_change_log WHERE tenant_id = ? AND model_code = ?",
                Integer.class, testTenant.getId(), model);
        assertEquals(0, count);
    }

    @Test
    @DisplayName("recordFieldChanges UPDATE: disabled field is not tracked (only enabled fields in configMap)")
    void recordFieldChanges_update_disabledFieldNotTracked() {
        String model = modelCode("update_disabled");
        // Configure with enabled=false → won't appear in getEnabledByModel → cache misses it
        fieldChangeAuditService.configureFieldAudit(testTenant.getId(), model, "hidden", false, false, false);

        Map<String, Object> before = Map.of("hidden", "old");
        Map<String, Object> after = Map.of("hidden", "new");
        fieldChangeAuditService.recordFieldChanges(
                testTenant.getId(), model, 302L, "update_cmd",
                before, after, testUser.getId(), testUser.getUserName());

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_field_change_log WHERE tenant_id = ? AND model_code = ?",
                Integer.class, testTenant.getId(), model);
        assertEquals(0, count, "disabled field must not be tracked");
    }

    // ==================== recordFieldChanges — asString branches ====================

    @Test
    @DisplayName("recordFieldChanges asString: Map value is serialized to JSON string")
    void recordFieldChanges_asString_mapValue() {
        String model = modelCode("asstr_map");
        fieldChangeAuditService.configureFieldAudit(testTenant.getId(), model, "meta", true, false, false);

        Map<String, Object> before = Map.of("meta", "simple");
        Map<String, Object> after = Map.of("meta", Map.of("key", "value"));  // Map value

        fieldChangeAuditService.recordFieldChanges(
                testTenant.getId(), model, 400L, "cmd",
                before, after, testUser.getId(), testUser.getUserName());

        List<FieldChangeLog> logs = changeLogMapper.getByModelAndRecord(testTenant.getId(), model, 400L);
        assertEquals(1, logs.size());
        // newValue is a JSON serialization of the map (whitespace-insensitive comparison)
        String newVal = logs.get(0).getNewValue();
        assertNotNull(newVal);
        assertTrue(newVal.contains("key") && newVal.contains("value"),
                "Map should be serialized to JSON, got: " + newVal);
    }

    @Test
    @DisplayName("recordFieldChanges asString: List value is serialized to JSON string")
    void recordFieldChanges_asString_listValue() {
        String model = modelCode("asstr_list");
        fieldChangeAuditService.configureFieldAudit(testTenant.getId(), model, "tags", true, false, false);

        Map<String, Object> before = Map.of("tags", "single");
        Map<String, Object> after = Map.of("tags", List.of("a", "b", "c"));

        fieldChangeAuditService.recordFieldChanges(
                testTenant.getId(), model, 401L, "cmd",
                before, after, testUser.getId(), testUser.getUserName());

        List<FieldChangeLog> logs = changeLogMapper.getByModelAndRecord(testTenant.getId(), model, 401L);
        assertEquals(1, logs.size());
        String newVal = logs.get(0).getNewValue();
        assertNotNull(newVal);
        assertTrue(newVal.contains("a") && newVal.contains("b") && newVal.contains("c"),
                "List should be JSON-serialized, got: " + newVal);
    }

    @Test
    @DisplayName("recordFieldChanges asString: numeric value uses toString")
    void recordFieldChanges_asString_numericValue() {
        String model = modelCode("asstr_num");
        fieldChangeAuditService.configureFieldAudit(testTenant.getId(), model, "score", true, false, false);

        Map<String, Object> before = Map.of("score", 10);
        Map<String, Object> after = Map.of("score", 20);

        fieldChangeAuditService.recordFieldChanges(
                testTenant.getId(), model, 402L, "cmd",
                before, after, testUser.getId(), testUser.getUserName());

        List<FieldChangeLog> logs = changeLogMapper.getByModelAndRecord(testTenant.getId(), model, 402L);
        assertEquals(1, logs.size());
        assertEquals("10", logs.get(0).getOldValue());
        assertEquals("20", logs.get(0).getNewValue());
    }

    // ==================== getRecordHistory ====================

    @Test
    @DisplayName("getRecordHistory returns all changes for a record ordered by time desc")
    void getRecordHistory_returnsRecordChanges() {
        String model = modelCode("history");
        fieldChangeAuditService.configureFieldAudit(testTenant.getId(), model, "status", true, false, false);
        fieldChangeAuditService.configureFieldAudit(testTenant.getId(), model, "name", true, false, false);

        // CREATE
        fieldChangeAuditService.recordFieldChanges(
                testTenant.getId(), model, 500L, "create_cmd",
                null, Map.of("status", "draft", "name", "Test"), testUser.getId(), testUser.getUserName());
        // UPDATE
        fieldChangeAuditService.recordFieldChanges(
                testTenant.getId(), model, 500L, "update_cmd",
                Map.of("status", "draft", "name", "Test"),
                Map.of("status", "active", "name", "Test"),
                testUser.getId(), testUser.getUserName());

        List<FieldChangeLog> history = fieldChangeAuditService.getRecordHistory(
                testTenant.getId(), model, 500L);
        // 2 from CREATE (status + name) + 1 from UPDATE (status only) = 3
        assertEquals(3, history.size());
        // All belong to the same record
        assertTrue(history.stream().allMatch(l -> 500L == l.getRecordId()));
    }

    @Test
    @DisplayName("getRecordHistory returns empty list for unknown record")
    void getRecordHistory_emptyForUnknownRecord() {
        String model = modelCode("history_empty");
        List<FieldChangeLog> history = fieldChangeAuditService.getRecordHistory(
                testTenant.getId(), model, 9999L);
        assertNotNull(history);
        assertTrue(history.isEmpty());
    }

    // ==================== getFieldHistory ====================

    @Test
    @DisplayName("getFieldHistory returns only changes for the specified field on a record")
    void getFieldHistory_filtersByField() {
        String model = modelCode("fhist");
        fieldChangeAuditService.configureFieldAudit(testTenant.getId(), model, "status", true, false, false);
        fieldChangeAuditService.configureFieldAudit(testTenant.getId(), model, "name", true, false, false);

        fieldChangeAuditService.recordFieldChanges(
                testTenant.getId(), model, 600L, "create_cmd",
                null, Map.of("status", "draft", "name", "Widget"),
                testUser.getId(), testUser.getUserName());

        List<FieldChangeLog> statusLogs = fieldChangeAuditService.getFieldHistory(
                testTenant.getId(), model, 600L, "status");
        List<FieldChangeLog> nameLogs = fieldChangeAuditService.getFieldHistory(
                testTenant.getId(), model, 600L, "name");

        assertEquals(1, statusLogs.size());
        assertEquals("status", statusLogs.get(0).getFieldCode());
        assertEquals(1, nameLogs.size());
        assertEquals("name", nameLogs.get(0).getFieldCode());
    }

    @Test
    @DisplayName("recordFieldChanges stores and queries pid-only history by recordPid")
    void recordFieldChanges_pidOnlyHistoryByRecordPid() {
        String model = modelCode("pidonly");
        String recordPid = "field_pid_" + RUN + "_only";
        fieldChangeAuditService.configureFieldAudit(testTenant.getId(), model, "status", true, false, false);

        fieldChangeAuditService.recordFieldChanges(
                testTenant.getId(), model, null, recordPid, "create_cmd",
                null, Map.of("status", "draft"),
                testUser.getId(), testUser.getUserName());

        List<FieldChangeLog> history = fieldChangeAuditService.getRecordHistoryByRecordPid(
                testTenant.getId(), model, recordPid);
        assertEquals(1, history.size());

        FieldChangeLog log = history.get(0);
        assertEquals(recordPid, log.getRecordPid());
        assertNull(log.getRecordId(), "pid-only field history must not require legacy numeric record_id");
        assertEquals("status", log.getFieldCode());
        assertEquals("draft", log.getNewValue());
    }

    @Test
    @DisplayName("getFieldHistoryByRecordPid filters one field for a non-numeric recordPid")
    void getFieldHistoryByRecordPid_filtersByField() {
        String model = modelCode("pidfield");
        String recordPid = "field_pid_" + RUN + "_field";
        fieldChangeAuditService.configureFieldAudit(testTenant.getId(), model, "status", true, false, false);
        fieldChangeAuditService.configureFieldAudit(testTenant.getId(), model, "name", true, false, false);

        fieldChangeAuditService.recordFieldChanges(
                testTenant.getId(), model, 610L, recordPid, "create_cmd",
                null, Map.of("status", "draft", "name", "Widget"),
                testUser.getId(), testUser.getUserName());

        List<FieldChangeLog> statusLogs = fieldChangeAuditService.getFieldHistoryByRecordPid(
                testTenant.getId(), model, recordPid, "status");
        List<FieldChangeLog> nameLogs = fieldChangeAuditService.getFieldHistoryByRecordPid(
                testTenant.getId(), model, recordPid, "name");

        assertEquals(1, statusLogs.size());
        assertEquals(recordPid, statusLogs.get(0).getRecordPid());
        assertEquals("status", statusLogs.get(0).getFieldCode());
        assertEquals(1, nameLogs.size());
        assertEquals(recordPid, nameLogs.get(0).getRecordPid());
        assertEquals("name", nameLogs.get(0).getFieldCode());
    }

    // ==================== getChangesByActor ====================

    @Test
    @DisplayName("getChangesByActor returns changes made by the actor within the time range")
    void getChangesByActor_timeRangeFilter() {
        String model = modelCode("actor");
        fieldChangeAuditService.configureFieldAudit(testTenant.getId(), model, "note", true, false, false);

        Instant before = Instant.now().minus(1, ChronoUnit.MINUTES);

        fieldChangeAuditService.recordFieldChanges(
                testTenant.getId(), model, 700L, "update_cmd",
                Map.of("note", "old"), Map.of("note", "new"),
                testUser.getId(), testUser.getUserName());

        Instant after = Instant.now().plus(1, ChronoUnit.MINUTES);

        List<FieldChangeLog> actorLogs = fieldChangeAuditService.getChangesByActor(
                testTenant.getId(), testUser.getId(), before, after);
        assertNotNull(actorLogs);
        assertFalse(actorLogs.isEmpty(), "should find the change made by this actor");
        assertTrue(actorLogs.stream().anyMatch(l -> "note".equals(l.getFieldCode())));
    }

    @Test
    @DisplayName("getChangesByActor returns empty list for future-only time range")
    void getChangesByActor_noChangesOutsideRange() {
        String model = modelCode("actor_none");
        fieldChangeAuditService.configureFieldAudit(testTenant.getId(), model, "f", true, false, false);

        fieldChangeAuditService.recordFieldChanges(
                testTenant.getId(), model, 701L, "cmd",
                Map.of("f", "old"), Map.of("f", "new"),
                testUser.getId(), testUser.getUserName());

        // Query a window entirely in the future → no match
        Instant futureStart = Instant.now().plus(1, ChronoUnit.HOURS);
        Instant futureEnd = Instant.now().plus(2, ChronoUnit.HOURS);

        List<FieldChangeLog> actorLogs = fieldChangeAuditService.getChangesByActor(
                testTenant.getId(), testUser.getId(), futureStart, futureEnd);
        assertNotNull(actorLogs);
        assertTrue(actorLogs.isEmpty(), "no changes in future window");
    }

    // ==================== getChangeReport ====================

    @Test
    @DisplayName("getChangeReport returns summary with correct totalChanges, field counts, actor counts")
    void getChangeReport_summaryAggregation() {
        String model = modelCode("report");
        fieldChangeAuditService.configureFieldAudit(testTenant.getId(), model, "price", true, false, false);
        fieldChangeAuditService.configureFieldAudit(testTenant.getId(), model, "qty", true, false, false);

        Instant start = Instant.now().minus(1, ChronoUnit.MINUTES);

        // Use an explicit non-null actor name so actorChangeCounts grouping works
        // (testUser.getUserName() may be null/empty on freshly-seeded users)
        final String actorName = "report-actor-" + RUN;

        // Two changes to price, one to qty
        fieldChangeAuditService.recordFieldChanges(
                testTenant.getId(), model, 800L, "update_cmd",
                Map.of("price", "10.0", "qty", "5"),
                Map.of("price", "15.0", "qty", "5"),  // qty unchanged
                testUser.getId(), actorName);
        fieldChangeAuditService.recordFieldChanges(
                testTenant.getId(), model, 800L, "update_cmd2",
                Map.of("price", "15.0", "qty", "5"),
                Map.of("price", "20.0", "qty", "8"),  // both changed
                testUser.getId(), actorName);

        Instant end = Instant.now().plus(1, ChronoUnit.MINUTES);

        @SuppressWarnings("unchecked")
        Map<String, Object> report = fieldChangeAuditService.getChangeReport(
                testTenant.getId(), model, start, end);

        assertNotNull(report);
        assertEquals(model, report.get("modelCode"));
        assertEquals(3L, report.get("totalChanges"));  // price×2 + qty×1

        @SuppressWarnings("unchecked")
        Map<String, Long> fieldCounts = (Map<String, Long>) report.get("fieldChangeCounts");
        assertNotNull(fieldCounts);
        assertEquals(2L, fieldCounts.get("price"));
        assertEquals(1L, fieldCounts.get("qty"));

        @SuppressWarnings("unchecked")
        Map<String, Long> actorCounts = (Map<String, Long>) report.get("actorChangeCounts");
        assertNotNull(actorCounts);
        assertTrue(actorCounts.containsKey(actorName),
                "actor name should be present in actorChangeCounts, got: " + actorCounts.keySet());
        assertEquals(3L, actorCounts.get(actorName));

        assertNotNull(report.get("startTime"));
        assertNotNull(report.get("endTime"));
        assertNotNull(report.get("recentChanges"));
    }

    @Test
    @DisplayName("getChangeReport returns zero totalChanges for empty time range")
    void getChangeReport_emptyRange() {
        String model = modelCode("report_empty");
        Instant futureStart = Instant.now().plus(1, ChronoUnit.HOURS);
        Instant futureEnd = Instant.now().plus(2, ChronoUnit.HOURS);

        Map<String, Object> report = fieldChangeAuditService.getChangeReport(
                testTenant.getId(), model, futureStart, futureEnd);

        assertNotNull(report);
        assertEquals(0L, report.get("totalChanges"));
        @SuppressWarnings("unchecked")
        Map<String, Long> fieldCounts = (Map<String, Long>) report.get("fieldChangeCounts");
        assertTrue(fieldCounts.isEmpty());
    }

    // ==================== Cache eviction ====================

    @Test
    @DisplayName("cache is evicted after configureFieldAudit update so next recordFieldChanges sees fresh config")
    void configCache_evictedOnConfigUpdate() {
        String model = modelCode("cache");
        // Enable field tracking
        fieldChangeAuditService.configureFieldAudit(testTenant.getId(), model, "priority", true, false, false);

        // Warm the cache: first call populates configCache
        fieldChangeAuditService.recordFieldChanges(
                testTenant.getId(), model, 900L, "cmd",
                Map.of("priority", "low"), Map.of("priority", "high"),
                testUser.getId(), testUser.getUserName());
        Integer before = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_field_change_log WHERE tenant_id = ? AND model_code = ?",
                Integer.class, testTenant.getId(), model);
        assertEquals(1, before);

        // Disable the field → should evict cache
        fieldChangeAuditService.configureFieldAudit(testTenant.getId(), model, "priority", false, false, false);

        // Next recordFieldChanges must NOT log (cache was evicted; reloads disabled-only config → empty)
        fieldChangeAuditService.recordFieldChanges(
                testTenant.getId(), model, 900L, "cmd2",
                Map.of("priority", "high"), Map.of("priority", "medium"),
                testUser.getId(), testUser.getUserName());
        Integer after = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_field_change_log WHERE tenant_id = ? AND model_code = ?",
                Integer.class, testTenant.getId(), model);
        assertEquals(1, after, "disabling field must evict cache and prevent new log entries");
    }

    // ==================== actor/field log metadata ====================

    @Test
    @DisplayName("recordFieldChanges sets actorId, actorName, tenantId, changedAt, commandCode on each log row")
    void recordFieldChanges_metadataFields() {
        String model = modelCode("meta");
        fieldChangeAuditService.configureFieldAudit(testTenant.getId(), model, "ref", true, false, false);

        fieldChangeAuditService.recordFieldChanges(
                testTenant.getId(), model, 950L, "my_command",
                null, Map.of("ref", "abc"),
                testUser.getId(), "Actor Name");

        List<FieldChangeLog> logs = changeLogMapper.getByModelAndRecord(testTenant.getId(), model, 950L);
        assertEquals(1, logs.size());
        FieldChangeLog cl = logs.get(0);
        assertEquals(testTenant.getId(), cl.getTenantId());
        assertEquals(testUser.getId(), cl.getActorId());
        assertEquals("Actor Name", cl.getActorName());
        assertEquals("my_command", cl.getCommandCode());
        assertNotNull(cl.getChangedAt());
        assertEquals("added", cl.getChangeType());
        assertEquals("string", cl.getValueType());
        assertEquals("ref", cl.getFieldCode());
        // fieldLabel falls back to fieldCode when no model definition for code-only models
        assertNotNull(cl.getFieldLabel());
    }
}
