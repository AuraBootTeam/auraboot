package com.auraboot.framework.plugin.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.menu.mapper.MenuMapper;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.plugin.dao.mapper.PluginImportLogMapper;
import com.auraboot.framework.plugin.dto.ConflictItem;
import com.auraboot.framework.plugin.dto.PluginImportRequest;
import com.auraboot.framework.plugin.dto.PluginImportResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for PluginTransactionalImportService.
 * Uses real PostgreSQL database (no H2/mock).
 * Extends BaseIntegrationTest to get proper MetaContext setup (required by TenantLineInterceptor).
 */
class PluginTransactionalImportServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PluginTransactionalImportService pluginTransactionalImportService;

    @Autowired
    private PluginConflictChecker conflictChecker;

    @Autowired
    private PermissionMapper permissionMapper;

    @Autowired
    private MenuMapper menuMapper;

    @Autowired
    private PluginImportLogMapper importLogMapper;

    private String uniquePrefix() {
        return "test_" + System.currentTimeMillis() + "_";
    }

    // ---- Conflict Detection Tests ----

    @Test
    void checkConflicts_noConflicts_returnsEmptyList() {
        String prefix = uniquePrefix();
        PluginImportRequest request = buildRequest(prefix, 1, 0);

        List<ConflictItem> conflicts = conflictChecker.checkConflicts(request);

        assertTrue(conflicts.isEmpty(), "Expected no conflicts for fresh import");
    }

    @Test
    void checkConflicts_permissionCodeConflict_detected() {
        String prefix = uniquePrefix();

        // Pre-insert a permission with the same code
        Permission existing = new Permission();
        existing.setPid(UniqueIdGenerator.generate());
        existing.setCode(prefix + "perm_0");
        existing.setName("Existing Permission");
        existing.setResourceType("test");
        existing.setAction("execute");
        existing.setSource("generated");
        existing.setStatus("active");
        existing.setDeletedFlag(false);
        existing.setCreatedAt(Instant.now());
        existing.setUpdatedAt(Instant.now());
        permissionMapper.insert(existing);

        // Build request with same permission code
        PluginImportRequest request = buildRequest(prefix, 1, 0);

        List<ConflictItem> conflicts = conflictChecker.checkConflicts(request);

        assertEquals(1, conflicts.size());
        assertEquals("permission", conflicts.get(0).getType());
        assertEquals(prefix + "perm_0", conflicts.get(0).getCode());
    }

    @Test
    void checkConflicts_reimportSamePlugin_noConflict() {
        String prefix = uniquePrefix();
        String pluginCode = prefix + "plugin";

        // First import succeeds
        PluginImportRequest request = buildRequest(prefix, 1, 0);
        request.setPluginCode(pluginCode);
        PluginImportResult result = pluginTransactionalImportService.importPlugin(request, false);
        assertEquals("success", result.getStatus());

        // Second import of same plugin — same permission code should NOT conflict
        PluginImportRequest reimport = buildRequest(prefix, 1, 0);
        reimport.setPluginCode(pluginCode);

        List<ConflictItem> conflicts = conflictChecker.checkConflicts(reimport);
        assertTrue(conflicts.isEmpty(), "Reimport of same plugin should not produce conflicts");
    }

    // ---- Dry Run Tests ----

    @Test
    void importPlugin_dryRun_noSideEffects() {
        String prefix = uniquePrefix();
        PluginImportRequest request = buildRequest(prefix, 1, 0);

        PluginImportResult result = pluginTransactionalImportService.importPlugin(request, true);

        assertEquals("OK", result.getStatus());
        assertTrue(result.getConflicts().isEmpty());

        // Verify nothing was actually inserted
        Permission perm = permissionMapper.findByCode(prefix + "perm_0");
        assertNull(perm, "Dry run should not insert permissions");
    }

    @Test
    void importPlugin_dryRunWithConflict_returnsConflicts() {
        String prefix = uniquePrefix();

        // Pre-insert a permission
        Permission existing = new Permission();
        existing.setPid(UniqueIdGenerator.generate());
        existing.setCode(prefix + "perm_0");
        existing.setName("Existing");
        existing.setResourceType("test");
        existing.setAction("execute");
        existing.setSource("generated");
        existing.setStatus("active");
        existing.setDeletedFlag(false);
        existing.setCreatedAt(Instant.now());
        existing.setUpdatedAt(Instant.now());
        permissionMapper.insert(existing);

        PluginImportRequest request = buildRequest(prefix, 1, 0);
        PluginImportResult result = pluginTransactionalImportService.importPlugin(request, true);

        assertEquals("conflict", result.getStatus());
        assertFalse(result.getConflicts().isEmpty());
    }

    // ---- Import Tests ----

    @Test
    void importPlugin_success_allResourcesCreated() {
        String prefix = uniquePrefix();
        PluginImportRequest request = buildRequest(prefix, 2, 0);

        PluginImportResult result = pluginTransactionalImportService.importPlugin(request, false);

        assertEquals("success", result.getStatus());
        assertEquals(2, result.getPermissionsImported());
        assertNotNull(result.getImportLogId());

        // Verify permissions exist
        for (int i = 0; i < 2; i++) {
            Permission perm = permissionMapper.findByCode(prefix + "perm_" + i);
            assertNotNull(perm, "Permission " + i + " should exist after import");
        }
    }

    @Test
    void importPlugin_conflictBlocked_nothingImported() {
        String prefix = uniquePrefix();

        // Pre-insert conflicting permission
        Permission existing = new Permission();
        existing.setPid(UniqueIdGenerator.generate());
        existing.setCode(prefix + "perm_0");
        existing.setName("Blocker");
        existing.setResourceType("test");
        existing.setAction("execute");
        existing.setSource("generated");
        existing.setStatus("active");
        existing.setDeletedFlag(false);
        existing.setCreatedAt(Instant.now());
        existing.setUpdatedAt(Instant.now());
        permissionMapper.insert(existing);

        PluginImportRequest request = buildRequest(prefix, 1, 0);
        PluginImportResult result = pluginTransactionalImportService.importPlugin(request, false);

        assertEquals("conflict", result.getStatus());
    }

    // ---- Import History Tests ----

    @Test
    void importHistory_afterSuccessfulImport_hasRecord() {
        String prefix = uniquePrefix();
        String pluginCode = prefix + "plugin";

        PluginImportRequest request = buildRequest(prefix, 1, 0);
        request.setPluginCode(pluginCode);
        pluginTransactionalImportService.importPlugin(request, false);

        List<PluginImportResult> history = pluginTransactionalImportService.getImportHistory(pluginCode, 1, 10);

        assertFalse(history.isEmpty(), "Import history should have at least one record");
        assertEquals(pluginCode, history.get(0).getPluginCode());
        assertEquals("success", history.get(0).getStatus());
    }

    @Test
    void importHistory_filterByPluginCode_onlyMatchingRecords() {
        String prefix = uniquePrefix();
        String code1 = prefix + "plugin_a";
        String code2 = prefix + "plugin_b";

        PluginImportRequest req1 = buildRequest(prefix + "a_", 1, 0);
        req1.setPluginCode(code1);
        pluginTransactionalImportService.importPlugin(req1, false);

        PluginImportRequest req2 = buildRequest(prefix + "b_", 1, 0);
        req2.setPluginCode(code2);
        pluginTransactionalImportService.importPlugin(req2, false);

        List<PluginImportResult> history1 = pluginTransactionalImportService.getImportHistory(code1, 1, 10);
        List<PluginImportResult> history2 = pluginTransactionalImportService.getImportHistory(code2, 1, 10);

        assertEquals(1, history1.size());
        assertEquals(code1, history1.get(0).getPluginCode());
        assertEquals(1, history2.size());
        assertEquals(code2, history2.get(0).getPluginCode());
    }

    // ---- Helper ----

    private PluginImportRequest buildRequest(String prefix, int permCount, int menuCount) {
        PluginImportRequest request = new PluginImportRequest();
        request.setPluginCode(prefix + "plugin");
        request.setPluginVersion("1.0.0");

        // Permissions
        List<Map<String, Object>> perms = new ArrayList<>();
        for (int i = 0; i < permCount; i++) {
            perms.add(Map.of(
                    "code", prefix + "perm_" + i,
                    "name", "Test Permission " + i,
                    "resourceType", "plugin",
                    "action", "execute"
            ));
        }
        request.setPermissions(perms);

        // Menus (not used in most tests due to FK constraints)
        List<Map<String, Object>> menus = new ArrayList<>();
        for (int i = 0; i < menuCount; i++) {
            menus.add(Map.of(
                    "name", prefix + "menu_" + i,
                    "path", "/" + prefix + "menu/" + i,
                    "type", 1,
                    "orderNo", i
            ));
        }
        request.setMenus(menus);

        return request;
    }
}
