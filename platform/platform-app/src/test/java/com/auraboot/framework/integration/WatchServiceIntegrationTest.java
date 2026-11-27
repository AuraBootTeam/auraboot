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
    private Long recordId1;
    private Long recordId2;
    private String recordPid1;
    private String recordPid2;

    @Autowired private com.auraboot.framework.meta.service.MetaModelService fixtureModels;
    @Autowired private com.auraboot.framework.meta.service.SchemaManagementService fixtureSchema;
    @Autowired private com.auraboot.framework.meta.service.DynamicDataService fixtureData;

    @BeforeEach
    void prepareReadableWatchRecords() {
        grantCommittedPermissionToTestRole("model." + modelCode + ".create", "model", modelCode, "create", "Seed owned watch records");
        grantCommittedPermissionToTestRole("model." + modelCode + ".read", "model", modelCode, "read", "Read owned watch records");
        if (recordId1 != null) return;
        fixtureModels.saveDefinition(com.auraboot.framework.meta.dto.ModelDefinition.builder()
                .code(modelCode).tableName("mt_" + modelCode).modelType("entity").status("published")
                .fields(List.of(com.auraboot.framework.meta.dto.FieldDefinition.builder()
                        .code("name").columnName("name").dataType("string").build())).build());
        assertThat(fixtureSchema.createTableByModel(modelCode).isSuccess()).isTrue();
        var first = fixtureData.create(modelCode, java.util.Map.of("name", "numeric first"));
        var second = fixtureData.create(modelCode, java.util.Map.of("name", "numeric second"));
        recordId1 = ((Number) first.get("id")).longValue();
        recordId2 = ((Number) second.get("id")).longValue();
        recordPid1 = fixtureData.create(modelCode, java.util.Map.of("name", "pid first")).get("pid").toString();
        recordPid2 = fixtureData.create(modelCode, java.util.Map.of("name", "pid second")).get("pid").toString();
    }

    @AfterAll
    void cleanupTestData() {
        applyTestMetaContext();
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
    @Order(13)
    void missingNumericRecordCannotCreateSubscription() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> watchService.toggleWatch(modelCode, Long.MAX_VALUE))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(watchService.isWatching(modelCode, Long.MAX_VALUE)).isFalse();
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

    @Test
    @Order(10)
    void toggleWatchByRecordPid_pidOnlyCreatesWatchWithoutNumericRecordId() {
        boolean result = watchService.toggleWatchByRecordPid(modelCode, recordPid1);

        assertThat(result).isTrue();
        assertThat(watchService.isWatchingByRecordPid(modelCode, recordPid1)).isTrue();

        AbWatch watch = watchMapper.selectOne(new LambdaQueryWrapper<AbWatch>()
                .eq(AbWatch::getModelCode, modelCode)
                .eq(AbWatch::getRecordPid, recordPid1));
        assertThat(watch).isNotNull();
        assertThat(watch.getRecordPid()).isEqualTo(recordPid1);
        assertThat(watch.getRecordId()).isNull();
    }

    @Test
    @Order(11)
    void getWatchersByRecordPid_returnsCurrentUserForNonNumericPid() {
        List<Long> watchers = watchService.getWatchersByRecordPid(modelCode, recordPid1);

        assertThat(watchers).contains(getTestUser().getId());
    }

    @Test
    @Order(12)
    void getWatchedRecordPids_returnsPidOnlyRecords() {
        watchService.toggleWatchByRecordPid(modelCode, recordPid2);

        List<String> watched = watchService.getWatchedRecordPids(modelCode, getTestUser().getId());

        assertThat(watched).contains(recordPid1, recordPid2);
    }
}
