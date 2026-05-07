package com.auraboot.framework.integration.bitemporal;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.module.bitemporal.entity.BiTemporalRecord;
import com.auraboot.module.bitemporal.mapper.BiTemporalMapper;
import com.auraboot.module.bitemporal.service.BiTemporalService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link BiTemporalService} using real PostgreSQL.
 *
 * <p>Replaces / complements the existing {@code BiTemporalServiceTest} unit
 * test which mocks the mapper and therefore can't catch SQL or close-and-insert
 * sequencing bugs.
 *
 * <p>Coverage:
 * <ul>
 *   <li>LIFECYCLE-1 put inserts a current record</li>
 *   <li>LIFECYCLE-2 correct closes prior tx period and inserts new version</li>
 *   <li>LIFECYCLE-3 terminate closes prior and writes terminated row with new validTo</li>
 *   <li>LIFECYCLE-4 getAsOf returns historical version, getHistory returns all</li>
 *   <li>LIFECYCLE-5 correct on missing entity throws IllegalStateException</li>
 *   <li>LIFECYCLE-6 multiple corrections produce monotonically increasing versionNo</li>
 *   <li>LIFECYCLE-7 concurrent correct() on the same anchor — winner inserts
 *       v2, loser rolls back with IllegalStateException; FOR UPDATE row lock
 *       preserves the single-open-version invariant (REVIEW-BE8-002)</li>
 * </ul>
 */
@DisplayName("BiTemporalService integration tests")
class BiTemporalServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private BiTemporalService biTemporalService;

    @Autowired
    private BiTemporalMapper mapper;

    @Autowired
    private ObjectMapper objectMapper;

    // For LIFECYCLE-7 — that test must commit rows so worker threads in
    // independent transactions can see them; the class-level @Rollback would
    // otherwise hide the seed from the workers. Seed via a dedicated
    // TransactionTemplate (REQUIRES_NEW + commit) and DELETE in a finally
    // block, since @Rollback won't unwind the committed rows.
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private static final String ENTITY_TYPE = "IT_ASSET";

    private String newEntityId() {
        // UniqueIdGenerator gives a tenant-scoped ULID that won't collide across runs.
        return "it-bt-" + UniqueIdGenerator.generate();
    }

    private JsonNode payload(String name, int version) {
        return objectMapper.createObjectNode()
                .put("name", name)
                .put("ver", version);
    }

    @Test
    @DisplayName("LIFECYCLE-1: put inserts a current record visible via getCurrent")
    void put_insertsRecordVisibleViaGetCurrent() {
        String entityId = newEntityId();
        LocalDateTime validFrom = LocalDateTime.now().minusDays(1);
        LocalDateTime validTo = LocalDateTime.now().plusYears(10);

        BiTemporalRecord written = biTemporalService.put(
                ENTITY_TYPE, entityId, validFrom, validTo,
                payload("v1", 1), getTestUser().getId());

        assertThat(written).isNotNull();
        assertThat(written.getVersionNo()).isEqualTo(1);
        assertThat(written.getTxTo()).isEqualTo(BiTemporalRecord.INFINITY);

        BiTemporalRecord current = biTemporalService.getCurrent(ENTITY_TYPE, entityId);
        assertThat(current).isNotNull();
        assertThat(current.getVersionNo()).isEqualTo(1);
        assertThat(current.getPayload().get("name").asText()).isEqualTo("v1");
        assertThat(current.getTxTo()).isEqualTo(BiTemporalRecord.INFINITY);
    }

    @Test
    @DisplayName("LIFECYCLE-2: correct closes prior tx period and inserts new version")
    void correct_closesPriorAndInsertsNewVersion() {
        String entityId = newEntityId();
        LocalDateTime validFrom = LocalDateTime.now().minusDays(1);
        LocalDateTime validTo = LocalDateTime.now().plusYears(10);
        Long userId = getTestUser().getId();

        biTemporalService.put(ENTITY_TYPE, entityId, validFrom, validTo, payload("v1", 1), userId);
        BiTemporalRecord corrected = biTemporalService.correct(
                ENTITY_TYPE, entityId, validFrom, validTo, payload("v2", 2), userId);

        assertThat(corrected.getVersionNo()).isEqualTo(2);
        assertThat(corrected.getPayload().get("name").asText()).isEqualTo("v2");
        assertThat(corrected.getTxTo()).isEqualTo(BiTemporalRecord.INFINITY);

        // getCurrent should now return v2
        BiTemporalRecord current = biTemporalService.getCurrent(ENTITY_TYPE, entityId);
        assertThat(current.getVersionNo()).isEqualTo(2);

        // History contains both rows; v1's tx_to is closed (NOT infinity)
        List<BiTemporalRecord> history = biTemporalService.getHistory(ENTITY_TYPE, entityId);
        assertThat(history).hasSize(2);
        BiTemporalRecord v1 = history.stream()
                .filter(r -> r.getVersionNo() == 1).findFirst().orElseThrow();
        assertThat(v1.getTxTo()).isNotEqualTo(BiTemporalRecord.INFINITY);
    }

    @Test
    @DisplayName("LIFECYCLE-3: terminate closes prior tx period and writes terminated row")
    void terminate_closesPriorAndWritesTerminatedRow() {
        String entityId = newEntityId();
        LocalDateTime validFrom = LocalDateTime.now().minusDays(1);
        LocalDateTime validTo = LocalDateTime.now().plusYears(10);
        Long userId = getTestUser().getId();

        biTemporalService.put(ENTITY_TYPE, entityId, validFrom, validTo, payload("v1", 1), userId);

        LocalDateTime terminationTime = LocalDateTime.now();
        biTemporalService.terminate(ENTITY_TYPE, entityId, terminationTime);

        // Latest version (v2) carries the original payload but a closed valid_to
        List<BiTemporalRecord> history = biTemporalService.getHistory(ENTITY_TYPE, entityId);
        assertThat(history).hasSize(2);
        BiTemporalRecord terminated = history.stream()
                .filter(r -> r.getVersionNo() == 2).findFirst().orElseThrow();
        assertThat(terminated.getValidTo()).isEqualToIgnoringNanos(terminationTime);
        assertThat(terminated.getTxTo()).isEqualTo(BiTemporalRecord.INFINITY);
        // Old v1 has its tx_to closed
        BiTemporalRecord v1 = history.stream()
                .filter(r -> r.getVersionNo() == 1).findFirst().orElseThrow();
        assertThat(v1.getTxTo()).isNotEqualTo(BiTemporalRecord.INFINITY);
    }

    @Test
    @DisplayName("LIFECYCLE-4: getAsOf returns the version valid at the given system time")
    void getAsOf_returnsHistoricalVersion() {
        String entityId = newEntityId();
        LocalDateTime validFrom = LocalDateTime.now().minusDays(1);
        LocalDateTime validTo = LocalDateTime.now().plusYears(10);
        Long userId = getTestUser().getId();

        BiTemporalRecord v1 = biTemporalService.put(
                ENTITY_TYPE, entityId, validFrom, validTo, payload("v1", 1), userId);

        // Capture v1's UTC tx_from from the persisted row (service uses
        // LocalDateTime.ofInstant(Instant.now(), UTC), so we cannot use the
        // local-clock LocalDateTime.now() — see HANDOVER 2026-05-05 timezone
        // discussion. Use the read-back tx_from as the stable anchor.
        BiTemporalRecord v1ReadBack = biTemporalService.getCurrent(ENTITY_TYPE, entityId);
        LocalDateTime asOfV1 = v1ReadBack.getTxFrom();
        // valid_time must fall inside [validFrom, validTo) — pick same instant
        LocalDateTime asOfValid = v1ReadBack.getTxFrom();

        // Pause so the correction's tx_from is strictly later
        sleepMillis(50);
        biTemporalService.correct(ENTITY_TYPE, entityId, validFrom, validTo, payload("v2", 2), userId);

        // Querying with txTime=v1's tx_from should still return v1 payload
        BiTemporalRecord historical = biTemporalService.getAsOf(
                ENTITY_TYPE, entityId, asOfValid, asOfV1);
        assertThat(historical).isNotNull();
        assertThat(historical.getId()).isEqualTo(v1.getId());
        assertThat(historical.getPayload().get("name").asText()).isEqualTo("v1");

        // Querying with the current row's tx_from should return v2
        BiTemporalRecord v2ReadBack = biTemporalService.getCurrent(ENTITY_TYPE, entityId);
        BiTemporalRecord nowCurrent = biTemporalService.getAsOf(
                ENTITY_TYPE, entityId, v2ReadBack.getTxFrom(), v2ReadBack.getTxFrom());
        assertThat(nowCurrent).isNotNull();
        assertThat(nowCurrent.getPayload().get("name").asText()).isEqualTo("v2");
    }

    @Test
    @DisplayName("LIFECYCLE-5: correct on missing entity throws IllegalStateException")
    void correct_onMissingEntity_throws() {
        String entityId = newEntityId();
        LocalDateTime validFrom = LocalDateTime.now().minusDays(1);
        LocalDateTime validTo = LocalDateTime.now().plusYears(10);

        assertThatThrownBy(() -> biTemporalService.correct(
                ENTITY_TYPE, entityId, validFrom, validTo, payload("v1", 1),
                getTestUser().getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No current record found for correction")
                .hasMessageContaining(entityId);
    }

    @Test
    @DisplayName("LIFECYCLE-6: repeated corrections produce monotonically increasing versionNo")
    void multipleCorrections_versionNoIsMonotonic() {
        String entityId = newEntityId();
        LocalDateTime validFrom = LocalDateTime.now().minusDays(1);
        LocalDateTime validTo = LocalDateTime.now().plusYears(10);
        Long userId = getTestUser().getId();

        biTemporalService.put(ENTITY_TYPE, entityId, validFrom, validTo, payload("v1", 1), userId);
        biTemporalService.correct(ENTITY_TYPE, entityId, validFrom, validTo, payload("v2", 2), userId);
        biTemporalService.correct(ENTITY_TYPE, entityId, validFrom, validTo, payload("v3", 3), userId);
        biTemporalService.correct(ENTITY_TYPE, entityId, validFrom, validTo, payload("v4", 4), userId);

        List<BiTemporalRecord> history = biTemporalService.getHistory(ENTITY_TYPE, entityId);
        assertThat(history).hasSize(4);
        assertThat(history)
                .extracting(BiTemporalRecord::getVersionNo)
                .containsExactlyInAnyOrder(1, 2, 3, 4);

        BiTemporalRecord current = biTemporalService.getCurrent(ENTITY_TYPE, entityId);
        assertThat(current.getVersionNo()).isEqualTo(4);
        assertThat(current.getPayload().get("name").asText()).isEqualTo("v4");

        // All non-current versions must have a closed tx_to
        long openCount = history.stream()
                .filter(r -> r.getTxTo().equals(BiTemporalRecord.INFINITY))
                .count();
        assertThat(openCount).isEqualTo(1L);
    }

    /**
     * LIFECYCLE-7: concurrent {@code correct()} on the same anchor preserves
     * the bi-temporal single-open-version invariant.
     *
     * <p><b>Race the fix targets (REVIEW-BE8-002):</b> without {@code FOR UPDATE},
     * two writers each call {@code findCurrent}, both read the same v1, both
     * call {@code closeTxPeriod(v1.id)}, and both insert a new row with
     * {@code tx_to=INFINITY} and {@code version_no=2}. The result is two open
     * versions — a corrupt bi-temporal table.
     *
     * <p><b>Chosen semantics with {@code FOR UPDATE LIMIT 1}: rollback for the
     * loser.</b> The query
     * {@code SELECT ... WHERE tx_to = INFINITY ... LIMIT 1 FOR UPDATE} locks
     * the single matching row, but PostgreSQL's EvalPlanQual mechanism only
     * re-checks the qualifier on the already-locked row after its holder
     * commits — it does <em>not</em> restart the query to find a different
     * matching row. So when the winner commits and the locked v1 row no
     * longer matches {@code tx_to = INFINITY}, the loser's query returns
     * <em>zero</em> rows, {@code findCurrentForUpdate} returns {@code null},
     * and {@code correct()} throws {@code IllegalStateException}. Spring's
     * transaction interceptor then rolls back the loser's transaction.
     *
     * <p>This is the "rollback" path mentioned in the REVIEW-BE8-002 backlog
     * item ("loser must observe rollback or retry semantics") — explicit and
     * easy to surface to callers, who can choose to retry application-side
     * if they wish.
     *
     * <p>Final state asserted below:
     * <ul>
     *   <li>history has exactly 2 rows (v1 closed, v2 open) — NOT 3</li>
     *   <li>exactly 1 row with {@code tx_to=INFINITY} (the invariant)</li>
     *   <li>no duplicate {@code version_no} — the smoking gun of the unfixed
     *       race would be a {@code {1, 2, 2}} history</li>
     * </ul>
     *
     * <p><b>Transaction semantics:</b> the class-level {@code @Transactional}
     * + {@code @Rollback} wraps the test thread's transaction; worker threads
     * spawned via {@code ExecutorService} do NOT inherit it (transactions are
     * thread-local). To make the seed v1 visible to worker threads we suspend
     * the test transaction via {@code TransactionTemplate(NOT_SUPPORTED)} —
     * the same pattern {@code BaseIntegrationTest.ensureTestDataExists} uses
     * — so the {@code @Transactional} on {@code service.put()} starts a fresh
     * transaction that commits on return. Worker threads then create their
     * own (REQUIRED) transactions inside {@code service.correct()} and the
     * FOR UPDATE row lock serialises them at the database level. The
     * committed rows are deleted in a {@code finally} block (also via
     * NOT_SUPPORTED so the DELETE auto-commits) so they don't leak.
     */
    @Test
    @DisplayName("LIFECYCLE-7: concurrent correct preserves single-open-version invariant under FOR UPDATE")
    void concurrentCorrect_preservesSingleOpenVersionInvariant() throws Exception {
        // Capture the test thread's tenant context so the worker threads
        // can re-establish it (MetaContext is ThreadLocal and the multi-tenant
        // interceptor demands it).
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();
        String userPid = getTestUser().getPid();
        String userName = getTestUser().getUserName();
        Long memberId = getTestTenantMember().getId();

        String entityId = newEntityId();
        LocalDateTime validFrom = LocalDateTime.now().minusDays(1);
        LocalDateTime validTo = LocalDateTime.now().plusYears(10);

        // Suspend the test-method transaction for seed and cleanup so the
        // worker threads' independent transactions can observe (and clean up
        // after) committed rows. PROPAGATION_NOT_SUPPORTED is the same
        // pattern BaseIntegrationTest.ensureTestDataExists uses for seeding;
        // it sidesteps the @Rollback the class-level @Transactional carries.
        TransactionTemplate seedTx = new TransactionTemplate(transactionManager);
        seedTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);

        try {
            // Seed v1 (committed in its own tx so worker threads can see it).
            seedTx.executeWithoutResult(status ->
                    biTemporalService.put(ENTITY_TYPE, entityId, validFrom, validTo,
                            payload("v1", 1), userId));

            // Sanity check the seed is actually visible from a fresh connection,
            // bypassing the multi-tenant interceptor — confirms the row really
            // committed before we race the workers.
            Long seedRowCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ab_bitemporal_record "
                            + " WHERE tenant_id = ? AND entity_type = ? AND entity_id = ?",
                    Long.class, tenantId, ENTITY_TYPE, entityId);
            assertThat(seedRowCount).as("seed v1 must be committed before race").isEqualTo(1L);

            // Two-thread race against correct() on the same (entity_type, entity_id).
            // Each worker opens its own transaction inside service.correct() (the
            // service method is @Transactional, REQUIRED, no enclosing tx in the
            // worker thread), so the FOR UPDATE row lock is released only when
            // each worker commits.
            CountDownLatch barrier = new CountDownLatch(1);
            Callable<BiTemporalRecord> correctA = () -> {
                MetaContext.setContext(tenantId, userId, userPid, userName);
                MetaContext.setMemberId(memberId);
                try {
                    barrier.await();
                    return biTemporalService.correct(ENTITY_TYPE, entityId,
                            validFrom, validTo, payload("vA", 99), userId);
                } finally {
                    MetaContext.clear();
                }
            };
            Callable<BiTemporalRecord> correctB = () -> {
                MetaContext.setContext(tenantId, userId, userPid, userName);
                MetaContext.setMemberId(memberId);
                try {
                    barrier.await();
                    return biTemporalService.correct(ENTITY_TYPE, entityId,
                            validFrom, validTo, payload("vB", 99), userId);
                } finally {
                    MetaContext.clear();
                }
            };

            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Future<BiTemporalRecord>> results;
            try {
                List<Callable<BiTemporalRecord>> tasks = new ArrayList<>();
                tasks.add(correctA);
                tasks.add(correctB);

                // Release both threads at once so they race into the close-and-insert.
                barrier.countDown();
                results = pool.invokeAll(tasks, 30, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }

            // Exactly one thread succeeded; the other rolled back with an
            // ExecutionException wrapping the IllegalStateException the
            // service throws when findCurrentForUpdate returns null (after
            // PG's EvalPlanQual sees the locked row no longer matches the
            // qualifier).
            BiTemporalRecord winnerResult = null;
            int winnerIndex = -1;
            int loserIndex = -1;
            Throwable loserCause = null;
            for (int i = 0; i < results.size(); i++) {
                Future<BiTemporalRecord> f = results.get(i);
                try {
                    BiTemporalRecord r = f.get();
                    assertThat(r).as("non-null result for thread " + i).isNotNull();
                    assertThat(winnerResult)
                            .as("only one thread may win the FOR UPDATE race; "
                                    + "thread " + i + " also produced a result")
                            .isNull();
                    winnerResult = r;
                    winnerIndex = i;
                } catch (java.util.concurrent.ExecutionException ee) {
                    assertThat(loserCause)
                            .as("only one thread may lose; thread " + i
                                    + " also threw " + ee.getCause())
                            .isNull();
                    loserCause = ee.getCause();
                    loserIndex = i;
                }
            }
            assertThat(winnerResult)
                    .as("exactly one thread must succeed; both lost: " + loserCause)
                    .isNotNull();
            assertThat(loserCause)
                    .as("loser thread must surface IllegalStateException — proof of rollback semantics")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No current record found for correction");

            // The winner's result must be v2 — close-and-insert was successful.
            assertThat(winnerResult.getVersionNo())
                    .as("winner (thread " + winnerIndex + ") must have inserted version 2")
                    .isEqualTo(2);
            assertThat(winnerResult.getTxTo()).isEqualTo(BiTemporalRecord.INFINITY);

            // ---------- core invariant assertions on persisted state ----------
            // Read straight from the DB via JdbcTemplate so we bypass the
            // multi-tenant interceptor's session caching and see the committed
            // state written by the winner thread (the loser rolled back).
            List<java.util.Map<String, Object>> rawRows = jdbcTemplate.queryForList(
                    "SELECT version_no, tx_to FROM ab_bitemporal_record "
                            + " WHERE tenant_id = ? AND entity_type = ? AND entity_id = ?"
                            + " AND deleted_flag = FALSE "
                            + " ORDER BY version_no ASC",
                    tenantId, ENTITY_TYPE, entityId);

            // 2 rows total: seed v1 (now closed) + winner's v2 (open).
            // Loser rolled back so produced no row. The 2-row count is the
            // primary proof the loser did NOT silently double-write — without
            // FOR UPDATE this assertion would observe 3 rows.
            assertThat(rawRows)
                    .as("history must have exactly 2 rows: seed v1 + winner v2 "
                            + "(loser thread " + loserIndex + " rolled back, no row written)")
                    .hasSize(2);

            // Exactly ONE row carries tx_to = INFINITY — the invariant.
            LocalDateTime infinity = BiTemporalRecord.INFINITY;
            long openCount = rawRows.stream()
                    .map(r -> ((java.sql.Timestamp) r.get("tx_to")).toLocalDateTime())
                    .filter(infinity::equals)
                    .count();
            assertThat(openCount)
                    .as("exactly one row must remain with tx_to=INFINITY after concurrent corrections")
                    .isEqualTo(1L);

            // version_no values are exactly {1, 2} — no duplicates.
            // A duplicate (e.g. {1, 2, 2}) would be the smoking gun of the
            // race firing without FOR UPDATE.
            assertThat(rawRows)
                    .extracting(r -> ((Number) r.get("version_no")).intValue())
                    .as("version_no must be monotonic without duplicates — duplicates prove the race fired")
                    .containsExactlyInAnyOrder(1, 2);

            // The single open row must be version_no = 2.
            int openVersion = rawRows.stream()
                    .filter(r -> ((java.sql.Timestamp) r.get("tx_to"))
                            .toLocalDateTime().equals(infinity))
                    .map(r -> ((Number) r.get("version_no")).intValue())
                    .findFirst().orElseThrow();
            assertThat(openVersion)
                    .as("the surviving open version must be v2 (winner's insert)")
                    .isEqualTo(2);

            // v1 must have a closed tx_to (closed by the winner's correct()).
            int v1TxToOpen = (int) rawRows.stream()
                    .filter(r -> ((Number) r.get("version_no")).intValue() == 1)
                    .filter(r -> ((java.sql.Timestamp) r.get("tx_to"))
                            .toLocalDateTime().equals(infinity))
                    .count();
            assertThat(v1TxToOpen)
                    .as("seed v1 must have its tx_to closed by the winner")
                    .isEqualTo(0);
        } finally {
            // Manual cleanup — the seed and worker writes committed in their
            // own transactions so @Rollback on the test method won't undo them.
            // Run the DELETE outside the test transaction (NOT_SUPPORTED
            // suspends it; the JdbcTemplate.update then auto-commits).
            seedTx.executeWithoutResult(status ->
                    jdbcTemplate.update(
                            "DELETE FROM ab_bitemporal_record "
                                    + " WHERE tenant_id = ? AND entity_type = ? AND entity_id = ?",
                            tenantId, ENTITY_TYPE, entityId));
        }
    }

    private static void sleepMillis(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Sleep interrupted in test", e);
        }
    }
}
