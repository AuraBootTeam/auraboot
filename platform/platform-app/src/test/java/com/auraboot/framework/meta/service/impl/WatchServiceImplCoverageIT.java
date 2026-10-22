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
class WatchServiceImplCoverageIT extends BaseIntegrationTest {

    private static final String MODEL = "watch_it_" + System.currentTimeMillis();

    @Autowired
    private WatchService watchService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;
    private Long otherUserId;
    private Long tenantId;

    @BeforeEach
    void ctx() {
        applyTestMetaContext();
        tenantId = getTestTenant().getId();
        userId = getTestUser().getId();
        otherUserId = getTestTenantMember().getId(); // any distinct Long works as a second watcher
    }

    @AfterAll
    void cleanup() {
        jdbcTemplate.update("DELETE FROM ab_watch WHERE tenant_id = ? AND model_code = ?", tenantId, MODEL);
        MetaContext.clear();
    }

    @Test
    @DisplayName("toggleWatch alternates watch state and getWatchers/getWatched reflect it")
    void toggleAndListers() {
        assertTrue(watchService.toggleWatch(MODEL, 101L), "first toggle starts watching");
        assertTrue(watchService.toggleWatch(MODEL, 102L));

        assertFalse(watchService.toggleWatch(MODEL, 101L), "second toggle stops watching");
        assertTrue(watchService.toggleWatch(MODEL, 101L));

        assertEquals(List.of(userId), watchService.getWatchers(MODEL, 101L));
        assertTrue(watchService.getWatchers(MODEL, 999L).isEmpty());
        assertEquals(List.of(101L, 102L),
                watchService.getWatchedRecordIds(MODEL, userId).stream().sorted().toList());
    }

    @Test
    @DisplayName("recordPid-based toggles and queries mirror the recordId path")
    void recordPidPaths() {
        assertTrue(watchService.toggleWatchByRecordPid(MODEL, "rec-pid-1"));
        assertTrue(watchService.isWatchingByRecordPid(MODEL, "rec-pid-1"));
        assertFalse(watchService.isWatchingByRecordPid(MODEL, "rec-pid-none"));

        assertFalse(watchService.toggleWatchByRecordPid(MODEL, "rec-pid-1"), "toggle off");
        assertFalse(watchService.isWatchingByRecordPid(MODEL, "rec-pid-1"));

        assertTrue(watchService.toggleWatchByRecordPid(MODEL, "rec-pid-2"));
        assertEquals(List.of("rec-pid-2"), watchService.getWatchedRecordPids(MODEL, userId));
        assertEquals(List.of(userId), watchService.getWatchersByRecordPid(MODEL, "rec-pid-2"));
        assertTrue(watchService.getWatchersByRecordPid(MODEL, "rec-pid-none").isEmpty());

        assertFalse(watchService.toggleWatchByRecordPid(MODEL, " "), "blank recordPid is a guarded no-op");
        assertFalse(watchService.isWatchingByRecordPid(MODEL, ""));
        assertTrue(watchService.getWatchersByRecordPid(MODEL, " ").isEmpty());
    }
}
