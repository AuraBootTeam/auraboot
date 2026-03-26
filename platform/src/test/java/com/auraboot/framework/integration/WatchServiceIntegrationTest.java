package com.auraboot.framework.integration;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.entity.AbWatch;
import com.auraboot.framework.meta.mapper.AbWatchMapper;
import com.auraboot.framework.meta.service.WatchService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for WatchService (M-023).
 * Tests toggle, isWatching, getWatchers, getWatchedRecordIds.
 * Uses real PostgreSQL, no mocking.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class WatchServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private WatchService watchService;

    @Autowired
    private AbWatchMapper watchMapper;

    private final String testRunId = String.valueOf(System.currentTimeMillis());
    private final String modelCode = "test_watch_" + System.currentTimeMillis();
    private final Long recordId1 = 10001L;
    private final Long recordId2 = 10002L;

    @AfterAll
    void cleanupTestData() {
        // Restore MetaContext — @AfterEach clears it, but mapper needs tenant_id
        MetaContext.setContext(
                getTestTenant().getId(),
                getTestUser().getId(),
                getTestUser().getPid(),
                getTestUser().getUserName()
        );
        // Clean up test records to avoid polluting DB across runs
        watchMapper.delete(new LambdaQueryWrapper<AbWatch>()
                .eq(AbWatch::getModelCode, modelCode));
    }

    @Test
    @Order(1)
    void toggleWatch_shouldCreateWatch() {
        boolean result = watchService.toggleWatch(modelCode, recordId1);

        assertThat(result).isTrue();
        assertThat(watchService.isWatching(modelCode, recordId1)).isTrue();
    }

    @Test
    @Order(2)
    void toggleWatch_shouldRemoveWatchOnSecondCall() {
        // First call created it in test Order(1)
        boolean result = watchService.toggleWatch(modelCode, recordId1);

        assertThat(result).isFalse();
        assertThat(watchService.isWatching(modelCode, recordId1)).isFalse();
    }

    @Test
    @Order(3)
    void toggleWatch_reWatchAfterUnwatch() {
        boolean result = watchService.toggleWatch(modelCode, recordId1);
        assertThat(result).isTrue();
        assertThat(watchService.isWatching(modelCode, recordId1)).isTrue();
    }

    @Test
    @Order(4)
    void isWatching_returnsFalseForUnwatchedRecord() {
        assertThat(watchService.isWatching(modelCode, 99999L)).isFalse();
    }

    @Test
    @Order(5)
    void getWatchers_returnsCurrentUser() {
        // recordId1 is watched from Order(3)
        List<Long> watchers = watchService.getWatchers(modelCode, recordId1);

        assertThat(watchers).isNotEmpty();
        assertThat(watchers).contains(getTestUser().getId());
    }

    @Test
    @Order(6)
    void getWatchers_returnsEmptyForUnwatchedRecord() {
        List<Long> watchers = watchService.getWatchers(modelCode, 99999L);
        assertThat(watchers).isEmpty();
    }

    @Test
    @Order(7)
    void getWatchedRecordIds_returnsWatchedRecords() {
        // Also watch recordId2
        watchService.toggleWatch(modelCode, recordId2);

        List<Long> watched = watchService.getWatchedRecordIds(modelCode, getTestUser().getId());

        assertThat(watched).contains(recordId1, recordId2);
    }

    @Test
    @Order(8)
    void getWatchedRecordIds_emptyForDifferentModel() {
        List<Long> watched = watchService.getWatchedRecordIds("nonexistent_model", getTestUser().getId());
        assertThat(watched).isEmpty();
    }

    @Test
    @Order(9)
    void toggleWatch_idempotentWithUniqueConstraint() {
        // Toggle off and on to verify unique constraint is respected
        watchService.toggleWatch(modelCode, recordId1); // off
        assertThat(watchService.isWatching(modelCode, recordId1)).isFalse();

        watchService.toggleWatch(modelCode, recordId1); // on again
        assertThat(watchService.isWatching(modelCode, recordId1)).isTrue();

        // Verify exactly one row in DB
        List<Long> watchers = watchService.getWatchers(modelCode, recordId1);
        long count = watchers.stream().filter(id -> id.equals(getTestUser().getId())).count();
        assertThat(count).isEqualTo(1);
    }
}
