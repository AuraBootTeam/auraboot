package com.auraboot.framework.file.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.infrastructure.storage.StorageProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Real PostgreSQL arbitration proof for the monotonic retain/delete boundary. */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("File retention/delete race IT")
class FileRetentionDeletionRaceIT {

    private static final long TENANT_ID = 991_910_001L;
    private static final long USER_ID = 991_910_002L;

    @Autowired private FileService fileService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockBean private StorageProvider storageProvider;

    private final AtomicLong sequence = new AtomicLong();

    @BeforeAll
    void ensureBranchSchema() {
        // The shared integration profile does not auto-run branch-local Flyway migrations.
        jdbcTemplate.execute("ALTER TABLE ab_file ADD COLUMN IF NOT EXISTS "
                + "retention_locked BOOLEAN NOT NULL DEFAULT FALSE");
    }

    @AfterAll
    void cleanup() {
        jdbcTemplate.update("DELETE FROM ab_file WHERE tenant_id = ?", TENANT_ID);
        MetaContext.clear();
    }

    @Test
    @DisplayName("retain and delete can never both win for the same finalized bytes")
    void concurrentRetainAndDeleteHaveExactlyOneWinner() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int attempt = 0; attempt < 8; attempt++) {
                long suffix = sequence.incrementAndGet();
                long id = 991_920_000L + suffix;
                String pid = "FILE-RACE-" + suffix;
                insertFinalizedFile(id, pid, "race-" + suffix + ".bin");
                CyclicBarrier start = new CyclicBarrier(2);

                Future<Outcome> retained = pool.submit(() -> raceCall(start, () ->
                        fileService.lockRetention(pid)));
                Future<Outcome> deleted = pool.submit(() -> raceCall(start, () ->
                        fileService.deleteFile(pid, USER_ID)));

                Outcome retainOutcome = retained.get(15, TimeUnit.SECONDS);
                Outcome deleteOutcome = deleted.get(15, TimeUnit.SECONDS);
                assertThat(retainOutcome.succeeded() ^ deleteOutcome.succeeded())
                        .as("exactly one fence wins; retain=%s delete=%s",
                                retainOutcome, deleteOutcome)
                        .isTrue();

                Map<String, Object> row = jdbcTemplate.queryForMap(
                        "SELECT retention_locked, deleted_flag, status FROM ab_file "
                                + "WHERE tenant_id = ? AND id = ?",
                        TENANT_ID, id);
                if (retainOutcome.succeeded()) {
                    assertThat(row.get("retention_locked")).isEqualTo(true);
                    assertThat(row.get("deleted_flag")).isEqualTo(false);
                    assertThat(row.get("status")).isEqualTo("success");
                } else {
                    assertThat(row.get("retention_locked")).isEqualTo(false);
                    assertThat(row.get("deleted_flag")).isEqualTo(true);
                    assertThat(row.get("status")).isEqualTo("deleted");
                }
                jdbcTemplate.update("DELETE FROM ab_file WHERE tenant_id = ? AND id = ?",
                        TENANT_ID, id);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private Outcome raceCall(CyclicBarrier start, CheckedBooleanSupplier action) {
        MetaContext.setContext(TENANT_ID, USER_ID, "race-user", "race-user");
        try {
            start.await(10, TimeUnit.SECONDS);
            return new Outcome(action.getAsBoolean(), null);
        } catch (Throwable error) {
            return new Outcome(false, error);
        } finally {
            MetaContext.clear();
        }
    }

    private void insertFinalizedFile(long id, String pid, String storageKey) {
        Instant now = Instant.now();
        Timestamp databaseNow = Timestamp.from(now);
        jdbcTemplate.update(
                "INSERT INTO ab_file "
                        + "(id, pid, tenant_id, file_name, original_name, file_size, mime_type, "
                        + "storage_type, upload_time, created_by, status, created_time, updated_time, "
                        + "deleted_flag, retention_locked) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, false, false)",
                id, pid, TENANT_ID, storageKey, storageKey, 10L,
                "application/octet-stream", "local", databaseNow, USER_ID, "success",
                databaseNow, databaseNow);
    }

    @FunctionalInterface
    private interface CheckedBooleanSupplier {
        boolean getAsBoolean() throws Exception;
    }

    private record Outcome(boolean succeeded, Throwable error) {
    }
}
