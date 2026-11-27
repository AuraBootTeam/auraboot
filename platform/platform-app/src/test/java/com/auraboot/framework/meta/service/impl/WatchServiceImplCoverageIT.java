package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.service.WatchService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-stack coverage IT for {@link WatchServiceImpl} — watch toggle semantics
 * (idempotent pairs), recordId- and recordPid-based lookups, watcher fan-out, and
 * the blank-recordPid guards. Rows are keyed by a run-unique model code on the
 * shared test tenant and removed by that code on teardown.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("WatchServiceImpl Coverage IT — toggles, watchers, guards")
@org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
class WatchServiceImplCoverageIT extends BaseIntegrationTest {

    private static final String MODEL = "watch_it_" + System.currentTimeMillis();

    @Autowired
    private WatchService watchService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired private com.auraboot.framework.meta.service.MetaModelService fixtureModels;
    @Autowired private com.auraboot.framework.meta.service.SchemaManagementService fixtureSchema;
    @Autowired private com.auraboot.framework.meta.service.DynamicDataService fixtureData;
    @Autowired private com.auraboot.framework.permission.service.UserPermissionService fixturePermissions;
    private com.auraboot.framework.integration.NativePermissionFixture nativeAuthority;
    private Long recordId1, recordId2;
    private String recordPid1, recordPid2;
    private Long userId;
    private Long tenantId;

    @BeforeEach
    void ctx() {
        applyTestMetaContext();
        tenantId = getTestTenant().getId();
        userId = getTestUser().getId();
        fixtureModels.saveDefinition(com.auraboot.framework.meta.dto.ModelDefinition.builder()
                .code(MODEL).modelType("entity").modelCategory("master")
                .fields(List.of(com.auraboot.framework.meta.dto.FieldDefinition.builder()
                        .code("watch_label_" + MODEL).columnName("watch_label_" + MODEL).dataType("string").build())).build());
        var schema = fixtureSchema.createTableByModel(MODEL);
        assertTrue(schema.isSuccess(), schema.getErrorMessage());
        nativeAuthority = new com.auraboot.framework.integration.NativePermissionFixture(jdbcTemplate, fixturePermissions);
        nativeAuthority.grantModel(MODEL, "create", "read");
        var first = fixtureData.create(MODEL, java.util.Map.of("watch_label_" + MODEL, "first"));
        var second = fixtureData.create(MODEL, java.util.Map.of("watch_label_" + MODEL, "second"));
        recordId1 = ((Number) first.get("id")).longValue();
        recordId2 = ((Number) second.get("id")).longValue();
        recordPid1 = first.get("pid").toString();
        recordPid2 = second.get("pid").toString();
    }

    @org.junit.jupiter.api.AfterEach
    void releaseOwnedAuthority() {
        if (nativeAuthority != null) nativeAuthority.close();
        if (tenantId != null) jdbcTemplate.update("DELETE FROM ab_watch WHERE tenant_id=? AND model_code=?", tenantId, MODEL);
    }

    @AfterAll
    void cleanup() {
        jdbcTemplate.update("DELETE FROM ab_watch WHERE tenant_id = ? AND model_code = ?", tenantId, MODEL);
        MetaContext.clear();
    }

    @Test
    @DisplayName("toggleWatch alternates watch state and getWatchers/getWatched reflect it")
    void toggleAndListers() {
        assertTrue(watchService.toggleWatch(MODEL, recordId1), "first toggle starts watching");
        assertTrue(watchService.toggleWatch(MODEL, recordId2));

        assertFalse(watchService.toggleWatch(MODEL, recordId1), "second toggle stops watching");
        assertTrue(watchService.toggleWatch(MODEL, recordId1));

        assertEquals(List.of(userId), watchService.getWatchers(MODEL, recordId1));
        assertTrue(watchService.getWatchers(MODEL, 999L).isEmpty());
        assertEquals(List.of(recordId1, recordId2),
                watchService.getWatchedRecordIds(MODEL, userId).stream().sorted().toList());
    }

    @Test
    @DisplayName("recordPid-based toggles and queries mirror the recordId path")
    void recordPidPaths() {
        assertTrue(watchService.toggleWatchByRecordPid(MODEL, recordPid1));
        assertTrue(watchService.isWatchingByRecordPid(MODEL, recordPid1));
        assertFalse(watchService.isWatchingByRecordPid(MODEL, "rec-pid-none"));

        assertFalse(watchService.toggleWatchByRecordPid(MODEL, recordPid1), "toggle off");
        assertFalse(watchService.isWatchingByRecordPid(MODEL, recordPid1));

        assertTrue(watchService.toggleWatchByRecordPid(MODEL, recordPid2));
        assertEquals(List.of(recordPid2), watchService.getWatchedRecordPids(MODEL, userId));
        assertEquals(List.of(userId), watchService.getWatchersByRecordPid(MODEL, recordPid2));
        assertTrue(watchService.getWatchersByRecordPid(MODEL, "rec-pid-none").isEmpty());

        assertFalse(watchService.toggleWatchByRecordPid(MODEL, " "), "blank recordPid is a guarded no-op");
        assertFalse(watchService.isWatchingByRecordPid(MODEL, ""));
        assertTrue(watchService.getWatchersByRecordPid(MODEL, " ").isEmpty());
    }
}
