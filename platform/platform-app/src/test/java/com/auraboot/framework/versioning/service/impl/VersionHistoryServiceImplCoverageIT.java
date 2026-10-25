package com.auraboot.framework.versioning.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.versioning.dto.DesignVersionDTO;
import com.auraboot.framework.versioning.service.VersionHistoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-stack coverage IT for {@link VersionHistoryServiceImpl} — the unified version
 * history: auto-labelled versions with parent links, snapshot round-trips through the
 * real {@code dashboard} VersionableResource strategy, rollback with auto-backup,
 * counting and old-version cleanup. Tables: {@code ab_design_version_history},
 * {@code ab_dashboard}, {@code ab_tenant} (synthetic tenant, cleaned by raw SQL).
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("VersionHistoryServiceImpl Coverage IT — versions, rollback, cleanup")
class VersionHistoryServiceImplCoverageIT {

    private static final long TENANT_ID = 991_400_001L;
    private static final String DASH_PID = "vh-it-dashboard-1";
    private static final String RESOURCE_TYPE = "dashboard";

    @Autowired
    private VersionHistoryService versionHistoryService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    @BeforeAll
    void seed() {
        jdbcTemplate.update(
                "INSERT INTO ab_tenant (id, pid, name, status) VALUES (?, ?, ?, 'active') ON CONFLICT (id) DO NOTHING",
                TENANT_ID, "vh-tenant-pid", "version-history-it-tenant");
        jdbcTemplate.update(
                "INSERT INTO ab_dashboard (id, pid, tenant_id, title, scope, status) "
                        + "VALUES (?, ?, ?, ?, 'personal', 'draft') ON CONFLICT (id) DO NOTHING",
                991_400_010L, DASH_PID, TENANT_ID, "VH IT Dashboard");
    }

    @BeforeEach
    void setUp() {
        MetaContext.setContext(TENANT_ID, 991_400_002L, "vh-test-pid", "vh-it-user");
        jdbcTemplate.update("DELETE FROM ab_design_version_history WHERE tenant_id = ?", TENANT_ID);
        jdbcTemplate.update("UPDATE ab_dashboard SET title = 'VH IT Dashboard', widgets = '[]'::jsonb WHERE id = ?", 991_400_010L);
    }

    @AfterAll
    void cleanup() {
        try {
            jdbcTemplate.update("DELETE FROM ab_design_version_history WHERE tenant_id = ?", TENANT_ID);
            jdbcTemplate.update("DELETE FROM ab_dashboard WHERE id = ?", 991_400_010L);
            jdbcTemplate.update("DELETE FROM ab_tenant WHERE id = ?", TENANT_ID);
        } finally {
            MetaContext.clear();
        }
    }

    private DesignVersionDTO record(String operation, String widgetsJson) throws Exception {
        return versionHistoryService.recordVersionWithSnapshot(
                RESOURCE_TYPE, DASH_PID,
                objectMapper.readTree("{\"widgets\": " + widgetsJson + ", \"title\": \"VH IT Dashboard\"}"),
                operation, "desc for " + operation);
    }

    @Test
    @DisplayName("recordVersion labels versions 1..n through the dashboard strategy and links parents")
    void recordVersionsViaStrategy() {
        DesignVersionDTO first = versionHistoryService.recordVersion(RESOURCE_TYPE, DASH_PID, "update", "first");
        assertEquals("1", first.getVersion());
        assertEquals(RESOURCE_TYPE, first.getResourceType());
        assertEquals(DASH_PID, first.getResourceId());
        assertEquals("vh-test-pid", first.getOperationBy()); // operationBy = MetaContext user pid
        assertNull(first.getParentVersionId());
        assertNull(first.getSchemaSnapshot()); // snapshot omitted in list DTOs

        DesignVersionDTO second = versionHistoryService.recordVersion(RESOURCE_TYPE, DASH_PID, "update", "second");
        assertEquals("2", second.getVersion());
        assertEquals(first.getPid(), second.getParentVersionId());

        assertEquals(2, versionHistoryService.countVersions(RESOURCE_TYPE, DASH_PID));
    }

    @Test
    @DisplayName("getHistory omits snapshots; getVersion includes them and returns null when missing")
    void readPaths() throws Exception {
        DesignVersionDTO recorded = record("create", "[{\"i\": \"a\"}]");

        List<DesignVersionDTO> history = versionHistoryService.getHistory(RESOURCE_TYPE, DASH_PID);
        assertEquals(1, history.size());
        assertNull(history.get(0).getSchemaSnapshot());

        DesignVersionDTO full = versionHistoryService.getVersion(recorded.getPid());
        assertNotNull(full);
        assertNotNull(full.getSchemaSnapshot());
        assertEquals("a", full.getSchemaSnapshot().get("widgets").get(0).get("i").asText());

        assertNull(versionHistoryService.getVersion("no-such-version-pid"));
    }

    @Test
    @DisplayName("rollback backs up the current state, applies the target snapshot, and records the restore")
    void rollbackRestoresTargetSnapshot() throws Exception {
        DesignVersionDTO v1 = record("create", "[{\"i\": \"widget-v1\"}]");
        record("update", "[{\"i\": \"widget-v2\"}]");

        jdbcTemplate.update("UPDATE ab_dashboard SET widgets = ?::jsonb WHERE pid = ?",
                "[{\"i\": \"widget-v2\"}]", DASH_PID);

        DesignVersionDTO rollbackEntry = versionHistoryService.rollback(RESOURCE_TYPE, DASH_PID, v1.getPid());

        assertEquals("rollback", rollbackEntry.getOperation());
        assertTrue(rollbackEntry.getDescription().contains("Rolled back to version"));

        List<Map<String, Object>> operations = jdbcTemplate.queryForList(
                "SELECT operation FROM ab_design_version_history WHERE tenant_id = ? ORDER BY created_at, id",
                TENANT_ID);
        List<String> ops = operations.stream().map(m -> (String) m.get("operation")).toList();
        assertTrue(ops.contains("backup_before_rollback"));
        assertEquals("rollback", ops.get(ops.size() - 1));

        String widgets = jdbcTemplate.queryForObject(
                "SELECT widgets::text FROM ab_dashboard WHERE pid = ?", String.class, DASH_PID);
        assertTrue(widgets.contains("widget-v1"), "dashboard widgets should be restored to v1: " + widgets);
    }

    @Test
    @DisplayName("rollback rejects missing versions and versions of a different resource")
    void rollbackGuards() throws Exception {
        DesignVersionDTO other = record("create", "[]");

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> versionHistoryService.rollback(RESOURCE_TYPE, DASH_PID, "no-such-pid"));
        assertTrue(missing.getMessage().contains("Version not found"));

        IllegalArgumentException foreign = assertThrows(IllegalArgumentException.class,
                () -> versionHistoryService.rollback(RESOURCE_TYPE, "another-resource", other.getPid()));
        assertTrue(foreign.getMessage().contains("does not belong"));
    }

    @Test
    @DisplayName("unknown resource types fail with a strategy lookup error")
    void unknownStrategy() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> versionHistoryService.recordVersion("no-such-type", "r1", "update", "d"));
        assertTrue(missing.getMessage().contains("No VersionableResource strategy"));
    }

    @Test
    @DisplayName("cleanupOldVersions keeps the newest N and removes the rest")
    void cleanupOldVersions() throws Exception {
        for (int i = 1; i <= 4; i++) {
            record("update", "[{\"seq\": " + i + "}]");
        }
        int deleted = versionHistoryService.cleanupOldVersions(RESOURCE_TYPE, DASH_PID, 2);
        assertEquals(2, deleted);
        assertEquals(2, versionHistoryService.countVersions(RESOURCE_TYPE, DASH_PID));
    }
}
