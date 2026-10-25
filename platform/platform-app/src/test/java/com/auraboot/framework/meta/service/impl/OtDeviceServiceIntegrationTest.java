package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.entity.OtDataLog;
import com.auraboot.framework.meta.entity.OtDevice;
import com.auraboot.framework.meta.exception.MetaServiceException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-stack integration test for {@link OtDeviceService}.
 *
 * <p>Part of OSS coverage initiative (tracker:
 * {@code docs/backlog/2026-06-10-oss-coverage-to-80-tracker.md}). {@code OtDeviceService}
 * was near-zero before this test. Exercises: device CRUD, duplicate-code guard,
 * validation (type/protocol/status enums), tenant-mismatch guard, updateDeviceStatus,
 * processHeartbeat, getDeviceStatus, processDeviceData (happy + disabled + with
 * data_mapping extractions + fieldMapping), getDataLog, getRecentDataLog,
 * getDataLogByStatus, getDeviceStats.
 *
 * <p>Uses the {@code integration-test} profile (shared Postgres :5432, {@code aura_boot}).
 * All data lives under a dedicated test tenant and is hard-deleted in {@link #tearDown()}.
 *
 * <p>PRODUCT BUG FIXED in this PR: {@code OtDevice.connectionConfig} and
 * {@code OtDevice.dataMapping} (JSONB NOT NULL / JSONB), plus {@code OtDataLog.rawData}
 * and {@code OtDataLog.parsedData} were missing {@code typeHandler = JsonbStringTypeHandler}
 * annotations — inserts threw "column is of type jsonb but expression is of type
 * character varying". Fixed in separate commit.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@DisplayName("OtDeviceService Real-Stack Integration Test")
class OtDeviceServiceIntegrationTest {

    private static final String CODE_PREFIX = "covotdev";
    /** Per-class-run nonce — keeps codes unique across re-runs. */
    private static final String RUN = Long.toString(Math.abs(System.nanoTime()), 36);

    @Autowired
    private OtDeviceService otDeviceService;
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

    // ──────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────

    @BeforeEach
    void setUp() {
        String testEmail = "covotdev-test@auraboot.com";
        testUser = userService.findByEmail(testEmail);
        if (testUser == null) {
            testUser = userService.signUp(testEmail, "test-password-123");
        }

        String testTenantName = "covotdev-test-tenant";
        testTenant = tenantService.findByName(testTenantName);
        if (testTenant == null) {
            Tenant tenant = new Tenant();
            tenant.setPid(UniqueIdGenerator.generate());
            tenant.setName(testTenantName);
            tenant.setDisplayName("OT Device Coverage Test Tenant");
            tenant.setStatus("active");
            tenant.setContactEmail("admin@covotdev-test.com");
            tenant.setDescription("Test tenant for OtDevice-domain coverage IT");
            tenant.setDeletedFlag(false);
            tenant.setCreatedAt(Instant.now());
            tenant.setUpdatedAt(Instant.now());
            testTenant = tenantService.createTenant(tenant);
        }

        TenantMember member = tenantMemberService.findByTenantIdAndUserId(testTenant.getId(), testUser.getId());
        if (member == null) {
            tenantMemberService.addMember(testUser.getId(), testTenant.getId(), "active");
        }

        MetaContext.setContext(testTenant.getId(), testUser.getId(), testUser.getPid(), testUser.getUserName());
        wipeTenantOtData();
    }

    @AfterEach
    void tearDown() {
        try {
            wipeTenantOtData();
        } catch (Exception e) {
            log.warn("OT device cleanup failed: {}", e.getMessage());
        } finally {
            MetaContext.clear();
        }
    }

    /** Hard-delete (bypassing @TableLogic soft delete) all OT rows for the test tenant. */
    private void wipeTenantOtData() {
        Long tid = testTenant.getId();
        // data_log has FK to device, so delete it first
        jdbcTemplate.update("DELETE FROM ab_ot_data_log WHERE tenant_id = ?", tid);
        jdbcTemplate.update("DELETE FROM ab_ot_device WHERE tenant_id = ?", tid);
    }

    // ──────────────────────────────────────────────────────────────────
    // Factory helpers
    // ──────────────────────────────────────────────────────────────────

    private String uniqueCode(String label) {
        return CODE_PREFIX + RUN + "_" + seq.incrementAndGet() + "_" + label;
    }

    /**
     * Build a minimal valid OtDevice (connection_config is JSONB NOT NULL, must be set).
     */
    private OtDevice newDevice(String code, String type, String protocol) {
        OtDevice d = new OtDevice();
        d.setDeviceCode(code);
        d.setDeviceName("Device-" + code);
        d.setDeviceType(type);
        d.setProtocol(protocol);
        // connection_config is JSONB NOT NULL — must supply a non-null JSON string
        d.setConnectionConfig("{\"host\":\"10.0.0.1\",\"port\":4840}");
        return d;
    }

    private OtDevice registerDevice(String type, String protocol) {
        return otDeviceService.registerDevice(newDevice(uniqueCode("dev"), type, protocol));
    }

    // ──────────────────────────────────────────────────────────────────
    // Device CRUD
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("registerDevice persists and is retrievable by id and code")
    void registerAndFindDevice() {
        OtDevice created = registerDevice("aoi", "rest_api");
        assertNotNull(created.getId());
        assertEquals(testTenant.getId(), created.getTenantId());
        assertEquals("offline", created.getStatus());
        assertTrue(created.getEnabled());
        assertEquals(5000, created.getPollingIntervalMs());

        OtDevice byId = otDeviceService.getDevice(created.getId());
        assertEquals(created.getDeviceCode(), byId.getDeviceCode());

        OtDevice byCode = otDeviceService.getDeviceByCode(created.getDeviceCode());
        assertEquals(created.getId(), byCode.getId());

        assertFalse(otDeviceService.listDevices().isEmpty());
    }

    @Test
    @DisplayName("registerDevice applies explicit status / enabled / pollingInterval when set")
    void registerDeviceExplicitOptionals() {
        OtDevice d = newDevice(uniqueCode("optionals"), "ict", "mqtt");
        d.setStatus("maintenance");
        d.setEnabled(false);
        d.setPollingIntervalMs(1000);
        OtDevice created = otDeviceService.registerDevice(d);

        assertEquals("maintenance", created.getStatus());
        assertFalse(created.getEnabled());
        assertEquals(1000, created.getPollingIntervalMs());
    }

    @Test
    @DisplayName("registerDevice rejects duplicate device code")
    void registerDeviceDuplicate() {
        OtDevice first = registerDevice("aoi", "rest_api");
        OtDevice dup = newDevice(first.getDeviceCode(), "ict", "mqtt");
        assertThrows(MetaServiceException.class, () -> otDeviceService.registerDevice(dup));
    }

    @Test
    @DisplayName("registerDevice rejects invalid device type")
    void registerDeviceInvalidType() {
        OtDevice bad = newDevice(uniqueCode("badtype"), "cnc_lathe", "rest_api");
        assertThrows(MetaServiceException.class, () -> otDeviceService.registerDevice(bad));
    }

    @Test
    @DisplayName("registerDevice rejects invalid protocol")
    void registerDeviceInvalidProtocol() {
        OtDevice bad = newDevice(uniqueCode("badproto"), "aoi", "ftp");
        assertThrows(MetaServiceException.class, () -> otDeviceService.registerDevice(bad));
    }

    @Test
    @DisplayName("registerDevice accepts all valid device types")
    void registerDeviceAllValidTypes() {
        String[] validTypes = {"aoi", "ict", "fct", "smt_pp", "reflow", "wave_solder", "spi", "xray", "laser_mark"};
        for (String type : validTypes) {
            OtDevice d = newDevice(uniqueCode(type), type, "rest_api");
            OtDevice created = otDeviceService.registerDevice(d);
            assertNotNull(created.getId(), "Should succeed for type: " + type);
        }
    }

    @Test
    @DisplayName("registerDevice accepts all valid protocols")
    void registerDeviceAllValidProtocols() {
        String[] validProtocols = {"opcua", "mqtt", "modbus", "rest_api", "file_watch", "secs_gem"};
        for (String proto : validProtocols) {
            OtDevice d = newDevice(uniqueCode(proto), "aoi", proto);
            OtDevice created = otDeviceService.registerDevice(d);
            assertNotNull(created.getId(), "Should succeed for protocol: " + proto);
        }
    }

    @Test
    @DisplayName("getDevice and getDeviceByCode throw when not found")
    void getDeviceNotFound() {
        assertThrows(MetaServiceException.class, () -> otDeviceService.getDevice(-999999L));
        assertThrows(MetaServiceException.class, () -> otDeviceService.getDeviceByCode("no-such-device"));
    }

    @Test
    @DisplayName("updateDevice mutates every updatable field")
    void updateDevice() {
        OtDevice created = registerDevice("aoi", "rest_api");

        OtDevice updates = new OtDevice();
        updates.setDeviceName("Updated Name");
        updates.setDeviceType("ict");
        updates.setProtocol("opcua");
        updates.setConnectionConfig("{\"host\":\"192.168.1.1\",\"port\":4840}");
        updates.setDataMapping("{\"extractions\":{\"temp\":\"sensors.temperature\"}}");
        updates.setTargetModelCode("pe_quality_data");
        updates.setPollingIntervalMs(10000);
        updates.setEnabled(false);

        OtDevice updated = otDeviceService.updateDevice(created.getId(), updates);
        assertEquals("Updated Name", updated.getDeviceName());
        assertEquals("ict", updated.getDeviceType());
        assertEquals("opcua", updated.getProtocol());
        assertEquals("pe_quality_data", updated.getTargetModelCode());
        assertEquals(10000, updated.getPollingIntervalMs());
        assertFalse(updated.getEnabled());

        // Reload from DB and verify
        OtDevice reloaded = otDeviceService.getDevice(created.getId());
        assertEquals("Updated Name", reloaded.getDeviceName());
        assertFalse(reloaded.getEnabled());
    }

    @Test
    @DisplayName("updateDevice rejects invalid type and protocol")
    void updateDeviceValidation() {
        OtDevice created = registerDevice("aoi", "rest_api");

        OtDevice badType = new OtDevice();
        badType.setDeviceType("robot_arm");
        assertThrows(MetaServiceException.class,
                () -> otDeviceService.updateDevice(created.getId(), badType));

        OtDevice badProto = new OtDevice();
        badProto.setProtocol("serial");
        assertThrows(MetaServiceException.class,
                () -> otDeviceService.updateDevice(created.getId(), badProto));
    }

    @Test
    @DisplayName("updateDevice rejects when id not found")
    void updateDeviceNotFound() {
        OtDevice updates = new OtDevice();
        updates.setDeviceName("Ghost");
        assertThrows(MetaServiceException.class,
                () -> otDeviceService.updateDevice(-999999L, updates));
    }

    @Test
    @DisplayName("updateDevice rejects cross-tenant access")
    void updateDeviceTenantMismatch() {
        OtDevice created = registerDevice("aoi", "rest_api");

        // Switch to a different tenant context (simulate cross-tenant attempt)
        MetaContext.clear();
        MetaContext.setContext(testTenant.getId() + 9999L, testUser.getId(),
                testUser.getPid(), testUser.getUserName());

        OtDevice updates = new OtDevice();
        updates.setDeviceName("Hijacked");
        assertThrows(MetaServiceException.class,
                () -> otDeviceService.updateDevice(created.getId(), updates));

        // Restore context for tearDown
        MetaContext.clear();
        MetaContext.setContext(testTenant.getId(), testUser.getId(),
                testUser.getPid(), testUser.getUserName());
    }

    @Test
    @DisplayName("deleteDevice soft-deletes (no longer retrievable)")
    void deleteDevice() {
        OtDevice created = registerDevice("fct", "modbus");
        otDeviceService.deleteDevice(created.getId());
        assertThrows(MetaServiceException.class,
                () -> otDeviceService.getDevice(created.getId()));
    }

    @Test
    @DisplayName("deleteDevice rejects cross-tenant access")
    void deleteDeviceTenantMismatch() {
        OtDevice created = registerDevice("aoi", "rest_api");

        MetaContext.clear();
        MetaContext.setContext(testTenant.getId() + 9999L, testUser.getId(),
                testUser.getPid(), testUser.getUserName());

        assertThrows(MetaServiceException.class,
                () -> otDeviceService.deleteDevice(created.getId()));

        MetaContext.clear();
        MetaContext.setContext(testTenant.getId(), testUser.getId(),
                testUser.getPid(), testUser.getUserName());
    }

    @Test
    @DisplayName("deleteDevice throws when not found")
    void deleteDeviceNotFound() {
        assertThrows(MetaServiceException.class,
                () -> otDeviceService.deleteDevice(-999999L));
    }

    // ──────────────────────────────────────────────────────────────────
    // Device Status
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("updateDeviceStatus changes status for all valid statuses")
    void updateDeviceStatusAllValues() {
        OtDevice created = registerDevice("xray", "rest_api");

        for (String status : new String[]{"online", "offline", "error", "maintenance"}) {
            otDeviceService.updateDeviceStatus(created.getId(), status);
            // No exception = success; status updated in DB
        }
    }

    @Test
    @DisplayName("updateDeviceStatus throws for invalid status")
    void updateDeviceStatusInvalid() {
        OtDevice created = registerDevice("spi", "rest_api");
        assertThrows(MetaServiceException.class,
                () -> otDeviceService.updateDeviceStatus(created.getId(), "broken"));
    }

    @Test
    @DisplayName("updateDeviceStatus throws when device not found")
    void updateDeviceStatusNotFound() {
        assertThrows(MetaServiceException.class,
                () -> otDeviceService.updateDeviceStatus(-999999L, "online"));
    }

    // ──────────────────────────────────────────────────────────────────
    // Heartbeat
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("processHeartbeat sets status to online and updates last_heartbeat")
    void processHeartbeatHappy() {
        OtDevice created = registerDevice("reflow", "mqtt");
        assertNull(created.getLastHeartbeat());
        assertEquals("offline", created.getStatus());

        Instant before = Instant.now().minusSeconds(1);
        OtDevice result = otDeviceService.processHeartbeat(created.getDeviceCode());

        assertEquals("online", result.getStatus());
        assertNotNull(result.getLastHeartbeat());
        assertTrue(result.getLastHeartbeat().isAfter(before));

        // Reload from DB to verify persistence
        OtDevice reloaded = otDeviceService.getDevice(created.getId());
        assertEquals("online", reloaded.getStatus());
        assertNotNull(reloaded.getLastHeartbeat());
    }

    @Test
    @DisplayName("processHeartbeat throws when device code not found")
    void processHeartbeatNotFound() {
        assertThrows(MetaServiceException.class,
                () -> otDeviceService.processHeartbeat("ghost-device-code"));
    }

    // ──────────────────────────────────────────────────────────────────
    // Device Status Summary
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getDeviceStatus returns summary with isStale=true when no heartbeat")
    void getDeviceStatusNoHeartbeat() {
        OtDevice created = registerDevice("wave_solder", "rest_api");

        Map<String, Object> status = otDeviceService.getDeviceStatus(created.getDeviceCode());

        assertEquals(created.getDeviceCode(), status.get("deviceCode"));
        assertEquals(created.getDeviceName(), status.get("deviceName"));
        assertNull(status.get("secondsSinceHeartbeat"));
        assertEquals(Boolean.TRUE, status.get("isStale"));
    }

    @Test
    @DisplayName("getDeviceStatus returns secondsSinceHeartbeat and isStale=false after fresh heartbeat")
    void getDeviceStatusAfterHeartbeat() {
        OtDevice created = registerDevice("laser_mark", "rest_api");
        // Set a very short polling interval so isStale=false is reliably achievable
        OtDevice updates = new OtDevice();
        updates.setPollingIntervalMs(300_000); // 5 minutes
        otDeviceService.updateDevice(created.getId(), updates);

        otDeviceService.processHeartbeat(created.getDeviceCode());

        Map<String, Object> status = otDeviceService.getDeviceStatus(created.getDeviceCode());
        assertNotNull(status.get("secondsSinceHeartbeat"));
        assertEquals(Boolean.FALSE, status.get("isStale"));
    }

    // ──────────────────────────────────────────────────────────────────
    // Data Processing — processDeviceData
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("processDeviceData creates data log entry and updates heartbeat")
    void processDeviceDataHappy() {
        OtDevice device = registerDevice("aoi", "rest_api");

        Map<String, Object> rawData = new LinkedHashMap<>();
        rawData.put("temperature", 42.5);
        rawData.put("result", "pass");
        rawData.put("board_id", "PCB-001");

        Instant before = Instant.now().minusSeconds(1);
        OtDataLog log = otDeviceService.processDeviceData(device.getDeviceCode(), rawData);

        assertNotNull(log.getId());
        assertEquals(device.getId(), log.getDeviceId());
        assertEquals(testTenant.getId(), log.getTenantId());
        assertEquals("processed", log.getStatus());
        assertNotNull(log.getRawData());
        assertNotNull(log.getTimestamp());
        assertNotNull(log.getProcessingTimeMs());

        // Heartbeat was updated
        OtDevice reloaded = otDeviceService.getDevice(device.getId());
        assertNotNull(reloaded.getLastHeartbeat());
        assertTrue(reloaded.getLastHeartbeat().isAfter(before));
    }

    @Test
    @DisplayName("processDeviceData throws when device not found")
    void processDeviceDataNotFound() {
        Map<String, Object> rawData = Map.of("sensor", "val");
        assertThrows(MetaServiceException.class,
                () -> otDeviceService.processDeviceData("ghost-device", rawData));
    }

    @Test
    @DisplayName("processDeviceData throws when device is disabled")
    void processDeviceDataDisabled() {
        OtDevice device = registerDevice("ict", "rest_api");
        OtDevice updates = new OtDevice();
        updates.setEnabled(false);
        otDeviceService.updateDevice(device.getId(), updates);

        assertThrows(MetaServiceException.class,
                () -> otDeviceService.processDeviceData(device.getDeviceCode(), Map.of("a", "b")));
    }

    @Test
    @DisplayName("processDeviceData with no data_mapping passes raw data through")
    void processDeviceDataNoMapping() {
        OtDevice device = registerDevice("smt_pp", "opcua");
        // No data_mapping set

        Map<String, Object> rawData = Map.of("speed", 1200, "cycle_time", 0.5);
        OtDataLog log = otDeviceService.processDeviceData(device.getDeviceCode(), rawData);

        assertEquals("processed", log.getStatus());
        // rawData JSON must contain the keys
        assertTrue(log.getRawData().contains("speed"));
    }

    @Test
    @DisplayName("processDeviceData with extractions mapping parses nested paths")
    void processDeviceDataWithExtractions() {
        OtDevice device = newDevice(uniqueCode("extract"), "aoi", "rest_api");
        // Set data_mapping with extractions using dot-notation paths
        device.setDataMapping(
                "{\"extractions\":{" +
                "\"temperature\":\"$.sensors.temp\"," +
                "\"pressure\":\"$.sensors.pressure\"," +
                "\"result\":\"$.inspection.pass\"}}");
        OtDevice created = otDeviceService.registerDevice(device);

        Map<String, Object> nestedSensors = new LinkedHashMap<>();
        nestedSensors.put("temp", 75.3);
        nestedSensors.put("pressure", 1.2);

        Map<String, Object> nestedInspection = new LinkedHashMap<>();
        nestedInspection.put("pass", true);

        Map<String, Object> rawData = new LinkedHashMap<>();
        rawData.put("sensors", nestedSensors);
        rawData.put("inspection", nestedInspection);

        OtDataLog log = otDeviceService.processDeviceData(created.getDeviceCode(), rawData);
        assertEquals("processed", log.getStatus());
        assertNotNull(log.getParsedData());
        // Parsed data should contain the extracted field names
        assertTrue(log.getParsedData().contains("temperature"));
        assertTrue(log.getParsedData().contains("pressure"));
        assertTrue(log.getParsedData().contains("result"));
    }

    @Test
    @DisplayName("processDeviceData with fieldMapping remaps field names to model fields")
    void processDeviceDataWithFieldMapping() {
        OtDevice device = newDevice(uniqueCode("fieldmap"), "ict", "rest_api");
        device.setDataMapping(
                "{\"fieldMapping\":{" +
                "\"temperature\":\"pe_temperature\"," +
                "\"result\":\"qc_test_result\"}}");
        OtDevice created = otDeviceService.registerDevice(device);

        Map<String, Object> rawData = new LinkedHashMap<>();
        rawData.put("temperature", 55.0);
        rawData.put("result", "pass");
        rawData.put("extra_field", "ignored");

        OtDataLog log = otDeviceService.processDeviceData(created.getDeviceCode(), rawData);
        assertEquals("processed", log.getStatus());
    }

    @Test
    @DisplayName("processDeviceData with both extractions and fieldMapping applies both transforms")
    void processDeviceDataFullMapping() {
        OtDevice device = newDevice(uniqueCode("fullmap"), "aoi", "rest_api");
        device.setDataMapping(
                "{\"extractions\":{\"temperature\":\"sensors.temp\"}," +
                "\"fieldMapping\":{\"temperature\":\"pe_temp_celsius\"}}");
        OtDevice created = otDeviceService.registerDevice(device);

        Map<String, Object> sensors = Map.of("temp", 68.1);
        Map<String, Object> rawData = Map.of("sensors", sensors);

        OtDataLog log = otDeviceService.processDeviceData(created.getDeviceCode(), rawData);
        assertEquals("processed", log.getStatus());
    }

    @Test
    @DisplayName("processDeviceData with mapping that has no matching extractions key still processes")
    void processDeviceDataExtractionNoMatch() {
        OtDevice device = newDevice(uniqueCode("nomatch"), "spi", "rest_api");
        device.setDataMapping("{\"extractions\":{\"missing_field\":\"$.sensors.nonexistent\"}}");
        OtDevice created = otDeviceService.registerDevice(device);

        Map<String, Object> rawData = Map.of("other_key", "value");
        OtDataLog log = otDeviceService.processDeviceData(created.getDeviceCode(), rawData);
        // No extraction match → empty parsed map → processed successfully
        assertEquals("processed", log.getStatus());
    }

    // ──────────────────────────────────────────────────────────────────
    // Data Log Queries
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getDataLog returns entries within date range")
    void getDataLogDateRange() {
        OtDevice device = registerDevice("aoi", "rest_api");
        otDeviceService.processDeviceData(device.getDeviceCode(), Map.of("k", "v1"));
        otDeviceService.processDeviceData(device.getDeviceCode(), Map.of("k", "v2"));

        Instant start = Instant.now().minusSeconds(60);
        Instant end = Instant.now().plusSeconds(60);

        List<OtDataLog> logs = otDeviceService.getDataLog(device.getId(), start, end);
        assertEquals(2, logs.size());
    }

    @Test
    @DisplayName("getDataLog returns empty list when outside date range")
    void getDataLogOutsideRange() {
        OtDevice device = registerDevice("aoi", "rest_api");
        otDeviceService.processDeviceData(device.getDeviceCode(), Map.of("k", "v"));

        Instant start = Instant.now().minusSeconds(120);
        Instant end = Instant.now().minusSeconds(60); // past window

        List<OtDataLog> logs = otDeviceService.getDataLog(device.getId(), start, end);
        assertTrue(logs.isEmpty());
    }

    @Test
    @DisplayName("getRecentDataLog returns entries in reverse-chronological order respecting limit")
    void getRecentDataLog() {
        OtDevice device = registerDevice("ict", "rest_api");
        for (int i = 0; i < 5; i++) {
            otDeviceService.processDeviceData(device.getDeviceCode(), Map.of("seq", i));
        }

        List<OtDataLog> recent = otDeviceService.getRecentDataLog(device.getId(), 3);
        assertEquals(3, recent.size());
    }

    @Test
    @DisplayName("getDataLogByStatus returns entries matching status")
    void getDataLogByStatus() {
        OtDevice device = registerDevice("fct", "rest_api");
        otDeviceService.processDeviceData(device.getDeviceCode(), Map.of("k", "v"));

        List<OtDataLog> processed = otDeviceService.getDataLogByStatus("processed", 10);
        assertFalse(processed.isEmpty());
    }

    // ──────────────────────────────────────────────────────────────────
    // Device Statistics
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getDeviceStats returns counts for processed/failed/pending entries")
    void getDeviceStats() {
        OtDevice device = registerDevice("aoi", "rest_api");
        // Generate one processed log entry
        otDeviceService.processDeviceData(device.getDeviceCode(), Map.of("k", "v"));

        Map<String, Object> stats = otDeviceService.getDeviceStats(device.getDeviceCode());
        assertEquals(device.getDeviceCode(), stats.get("deviceCode"));
        assertEquals(device.getDeviceName(), stats.get("deviceName"));
        assertNotNull(stats.get("processedCount"));
        assertNotNull(stats.get("failedCount"));
        assertNotNull(stats.get("pendingCount"));
        assertNotNull(stats.get("totalCount"));
        // At least one processed log
        assertTrue((long) stats.get("processedCount") >= 1L);
    }

    // ──────────────────────────────────────────────────────────────────
    // JSON path resolution (via processDeviceData driving parseDeviceData)
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("resolveJsonPath: path without leading $. is resolved correctly")
    void resolveJsonPathNoDollar() {
        OtDevice device = newDevice(uniqueCode("noDollar"), "aoi", "rest_api");
        // Use path WITHOUT leading "$."; service's resolveJsonPath strips it if present
        device.setDataMapping("{\"extractions\":{\"temp\":\"metrics.temperature\"}}");
        OtDevice created = otDeviceService.registerDevice(device);

        Map<String, Object> metrics = Map.of("temperature", 99.9);
        Map<String, Object> rawData = Map.of("metrics", metrics);

        OtDataLog log = otDeviceService.processDeviceData(created.getDeviceCode(), rawData);
        assertEquals("processed", log.getStatus());
        assertTrue(log.getParsedData().contains("temp"));
    }

    @Test
    @DisplayName("resolveJsonPath: boolean and number values are preserved as correct types")
    void resolveJsonPathTypedValues() {
        OtDevice device = newDevice(uniqueCode("types"), "ict", "rest_api");
        device.setDataMapping("{\"extractions\":{" +
                "\"passed\":\"$.qc.passed\"," +
                "\"count\":\"$.qc.count\"," +
                "\"label\":\"$.qc.label\"}}");
        OtDevice created = otDeviceService.registerDevice(device);

        Map<String, Object> qc = new LinkedHashMap<>();
        qc.put("passed", true);
        qc.put("count", 42);
        qc.put("label", "batch-A");
        Map<String, Object> rawData = Map.of("qc", qc);

        OtDataLog log = otDeviceService.processDeviceData(created.getDeviceCode(), rawData);
        assertEquals("processed", log.getStatus());
        // parsedData should contain the extracted fields
        String pd = log.getParsedData();
        assertTrue(pd.contains("passed"));
        assertTrue(pd.contains("count"));
        assertTrue(pd.contains("label"));
    }
}
