package com.auraboot.framework.agent.memory;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for PR-82 Phase 1: {@link MemoryTierEvaluator} scoring
 * formula and the accompanying schema (new columns on {@code ab_agent_memory}
 * + new {@code ab_agent_memory_tier_event} audit table).
 *
 * <p>Covers 6+ cases across the four formula factors plus schema round-trip.
 *
 * <p>Per project red-line: real PostgreSQL via {@link BaseIntegrationTest}, no
 * H2/embedded DB; AssertJ assertions; {@link UniqueIdGenerator} for test data
 * prefixing.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@Transactional
@Rollback(true)
class MemoryTierEvaluatorIntegrationTest extends BaseIntegrationTest {

    private static final String TEST_PREFIX = "MTE_";

    @Autowired
    private MemoryTierEvaluator evaluator;

    @Autowired
    private DynamicDataMapper dynamicDataMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ---------------------------------------------------------------
    // Case 1: high importance + recent + unique + accessed -> promote
    // ---------------------------------------------------------------
    @Test
    void score_highSignalAllFactors_exceedsThresholdAndPromotes() {
        Instant now = Instant.parse("2026-04-19T08:00:00Z");
        MemoryTierEvaluator.Candidate c = new MemoryTierEvaluator.Candidate(
                /*importance*/ 10,
                /*accessCount*/ 20,
                /*createdAt*/ now, // age = 0 -> recency = 1
                /*maxCosineToL2*/ 0.0); // fully unique

        MemoryTierEvaluator.ScoreResult r = evaluator.score(c, now);

        // All four factors at their maximum -> score == sum of weights (~1.0)
        assertThat(r.importanceFactor()).isEqualTo(1.0);
        assertThat(r.accessFactor()).isEqualTo(1.0);
        assertThat(r.recencyFactor()).isEqualTo(1.0);
        assertThat(r.uniquenessFactor()).isEqualTo(1.0);
        assertThat(r.score()).isEqualTo(1.0);
        assertThat(r.weightsVersion()).isEqualTo("v1");
        assertThat(evaluator.shouldPromote(r)).isTrue();
    }

    // ---------------------------------------------------------------
    // Case 2: low importance alone -> below threshold
    // ---------------------------------------------------------------
    @Test
    void score_lowImportanceEvenWhenOtherFactorsHigh_stillRespectsWeights() {
        Instant now = Instant.parse("2026-04-19T08:00:00Z");
        MemoryTierEvaluator.Candidate c = new MemoryTierEvaluator.Candidate(
                /*importance*/ 0,
                /*accessCount*/ 0,
                /*createdAt*/ now,
                /*maxCosineToL2*/ 0.0);

        MemoryTierEvaluator.ScoreResult r = evaluator.score(c, now);

        // imp=0, acc=0, rec=1, uni=1 -> score = 0.15 + 0.25 = 0.40
        assertThat(r.importanceFactor()).isEqualTo(0.0);
        assertThat(r.accessFactor()).isEqualTo(0.0);
        assertThat(r.recencyFactor()).isEqualTo(1.0);
        assertThat(r.uniquenessFactor()).isEqualTo(1.0);
        assertThat(r.score()).isEqualTo(0.40);
        assertThat(evaluator.shouldPromote(r)).isFalse();
    }

    // ---------------------------------------------------------------
    // Case 3: recency decays with age (72h half-life)
    // ---------------------------------------------------------------
    @Test
    void score_recencyHalvesAfter72Hours() {
        Instant now = Instant.parse("2026-04-19T08:00:00Z");
        Instant created72hAgo = now.minus(Duration.ofHours(72));

        MemoryTierEvaluator.Candidate c = new MemoryTierEvaluator.Candidate(
                5, 0, created72hAgo, 0.0);

        MemoryTierEvaluator.ScoreResult r = evaluator.score(c, now);

        // exp(-1) ≈ 0.3679
        assertThat(r.recencyFactor()).isCloseTo(Math.exp(-1.0), org.assertj.core.data.Offset.offset(0.001));

        // After 144h the recency should be much smaller (exp(-2) ≈ 0.1353)
        MemoryTierEvaluator.Candidate older = new MemoryTierEvaluator.Candidate(
                5, 0, now.minus(Duration.ofHours(144)), 0.0);
        MemoryTierEvaluator.ScoreResult rOlder = evaluator.score(older, now);
        assertThat(rOlder.recencyFactor()).isLessThan(r.recencyFactor());
        assertThat(rOlder.recencyFactor()).isCloseTo(Math.exp(-2.0), org.assertj.core.data.Offset.offset(0.001));
    }

    // ---------------------------------------------------------------
    // Case 4: access_count is log-normalised and caps at 20
    // ---------------------------------------------------------------
    @Test
    void score_accessCountUsesLogNormalisationAndCaps() {
        Instant now = Instant.parse("2026-04-19T08:00:00Z");

        MemoryTierEvaluator.Candidate atCap = new MemoryTierEvaluator.Candidate(
                5, 20, now, 0.0);
        MemoryTierEvaluator.Candidate above = new MemoryTierEvaluator.Candidate(
                5, 200, now, 0.0);

        MemoryTierEvaluator.ScoreResult rCap = evaluator.score(atCap, now);
        MemoryTierEvaluator.ScoreResult rAbove = evaluator.score(above, now);

        // At cap -> factor = 1.0; above cap -> clamped to 1.0 too
        assertThat(rCap.accessFactor()).isEqualTo(1.0);
        assertThat(rAbove.accessFactor()).isEqualTo(1.0);

        // A small access count (e.g. 2) should produce < 0.5 due to log curve.
        MemoryTierEvaluator.Candidate small = new MemoryTierEvaluator.Candidate(
                5, 2, now, 0.0);
        MemoryTierEvaluator.ScoreResult rSmall = evaluator.score(small, now);
        assertThat(rSmall.accessFactor()).isLessThan(0.5);
        assertThat(rSmall.accessFactor()).isGreaterThan(0.0);
    }

    // ---------------------------------------------------------------
    // Case 5: uniqueness penalises high-cosine duplicates
    // ---------------------------------------------------------------
    @Test
    void score_uniquenessFactorInvertsCosine() {
        Instant now = Instant.parse("2026-04-19T08:00:00Z");

        MemoryTierEvaluator.Candidate duplicate = new MemoryTierEvaluator.Candidate(
                10, 20, now, 0.95); // near-duplicate of existing L2
        MemoryTierEvaluator.Candidate novel = new MemoryTierEvaluator.Candidate(
                10, 20, now, 0.10);

        MemoryTierEvaluator.ScoreResult rDup = evaluator.score(duplicate, now);
        MemoryTierEvaluator.ScoreResult rNovel = evaluator.score(novel, now);

        assertThat(rDup.uniquenessFactor()).isCloseTo(0.05, org.assertj.core.data.Offset.offset(0.001));
        assertThat(rNovel.uniquenessFactor()).isCloseTo(0.90, org.assertj.core.data.Offset.offset(0.001));
        assertThat(rNovel.score()).isGreaterThan(rDup.score());
    }

    // ---------------------------------------------------------------
    // Case 6: malformed input throws (no fallback / ensure / retry)
    // ---------------------------------------------------------------
    @Test
    void score_rejectsMalformedInputWithoutFallback() {
        Instant now = Instant.parse("2026-04-19T08:00:00Z");

        assertThatThrownBy(() -> evaluator.score(
                new MemoryTierEvaluator.Candidate(-1, 0, now, 0.0), now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("importance");

        assertThatThrownBy(() -> evaluator.score(
                new MemoryTierEvaluator.Candidate(5, -3, now, 0.0), now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accessCount");

        assertThatThrownBy(() -> evaluator.score(
                new MemoryTierEvaluator.Candidate(5, 0, now, 1.5), now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxCosineToL2");

        assertThatThrownBy(() -> evaluator.score(
                new MemoryTierEvaluator.Candidate(5, 0, null, 0.0), now))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------
    // Case 7: custom weights are respected, version echoed
    // ---------------------------------------------------------------
    @Test
    void score_customWeightsOverrideDefaults() {
        Instant now = Instant.parse("2026-04-19T08:00:00Z");
        MemoryTierEvaluator.Weights custom = new MemoryTierEvaluator.Weights(
                1.0, 0.0, 0.0, 0.0, "v-test");

        MemoryTierEvaluator.Candidate c = new MemoryTierEvaluator.Candidate(
                10, 0, now, 0.0);

        MemoryTierEvaluator.ScoreResult r = evaluator.score(c, now, custom);

        // Only importance factor contributes, weight=1.0 -> score == 1.0
        assertThat(r.score()).isEqualTo(1.0);
        assertThat(r.weightsVersion()).isEqualTo("v-test");
    }

    // ---------------------------------------------------------------
    // Case 8: MemoryTier enum round-trips through lowercase code
    // ---------------------------------------------------------------
    @Test
    void memoryTier_roundTripThroughLowercaseCode() {
        assertThat(MemoryTier.L1.code()).isEqualTo("l1");
        assertThat(MemoryTier.L2.code()).isEqualTo("l2");

        assertThat(MemoryTier.fromCode("l1")).isEqualTo(MemoryTier.L1);
        assertThat(MemoryTier.fromCode("l2")).isEqualTo(MemoryTier.L2);

        assertThat(MemoryTier.fromCategory("session")).isEqualTo(MemoryTier.L1);
        assertThat(MemoryTier.fromCategory("user")).isEqualTo(MemoryTier.L2);
        assertThat(MemoryTier.fromCategory("agent")).isEqualTo(MemoryTier.L2);

        // No fallback: unknown values throw
        assertThatThrownBy(() -> MemoryTier.fromCode("L1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MemoryTier.fromCategory("unknown"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------
    // Case 9: new schema columns + audit table accept round-trip data
    // ---------------------------------------------------------------
    @Test
    void schema_newColumnsAndAuditTableRoundTrip() {
        Long tenantId = getTestTenant().getId();
        String memoryPid = UniqueIdGenerator.generate();
        String runId = UniqueIdGenerator.generate();
        String eventPid = UniqueIdGenerator.generate();

        // 1) Insert L1 row with new columns (content_hash, promoted_at, etc.)
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("pid", memoryPid);
        memory.put("tenant_id", tenantId);
        memory.put("memory_agent_id", TEST_PREFIX + "agent");
        memory.put("memory_type", "fact");
        memory.put("category", "session"); // L1
        memory.put("memory_title", TEST_PREFIX + "title");
        memory.put("memory_content", TEST_PREFIX + "content");
        memory.put("importance", 8);
        memory.put("source_run_id", runId);
        memory.put("access_count", 3);
        memory.put("created_at", LocalDateTime.now());
        memory.put("updated_at", LocalDateTime.now());
        memory.put("deleted_flag", false);
        memory.put("shareable", false);
        memory.put("scope", "user");
        memory.put("scope_key", String.valueOf(getTestUser().getId()));
        memory.put("content_hash",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        memory.put("score_snapshot",
                "{\"score\":0.72,\"factors\":{\"imp\":0.8,\"acc\":0.4,\"rec\":0.92,\"uni\":0.85}," +
                        "\"weights_version\":\"v1\",\"computed_at\":\"2026-04-19T08:15:00Z\"}");
        memory.put("demotion_count", 0);
        dynamicDataMapper.insertWithJsonb("ab_agent_memory", memory, Set.of("score_snapshot"));

        Integer memoryCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_memory WHERE pid = ?", Integer.class, memoryPid);
        assertThat(memoryCount).isEqualTo(1);

        String storedHash = jdbcTemplate.queryForObject(
                "SELECT content_hash FROM ab_agent_memory WHERE pid = ?", String.class, memoryPid);
        assertThat(storedHash).hasSize(64);

        Integer demotionCount = jdbcTemplate.queryForObject(
                "SELECT demotion_count FROM ab_agent_memory WHERE pid = ?",
                Integer.class, memoryPid);
        assertThat(demotionCount).isZero();

        // 2) Insert tier event row using the L1_PROMOTED event type
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("pid", eventPid);
        event.put("tenant_id", tenantId);
        event.put("memory_pid", memoryPid);
        event.put("event_type", MemoryTier.L1.eventPrefix() + "_PROMOTED");
        event.put("dedup_mode", null);
        event.put("merged_into_pid", null);
        event.put("score_snapshot",
                "{\"score\":0.72,\"weights_version\":\"v1\"}");
        event.put("source_run_id", runId);
        event.put("created_at", LocalDateTime.now());
        dynamicDataMapper.insertWithJsonb(
                "ab_agent_memory_tier_event", event, Set.of("score_snapshot"));

        String storedEventType = jdbcTemplate.queryForObject(
                "SELECT event_type FROM ab_agent_memory_tier_event WHERE pid = ?",
                String.class, eventPid);
        assertThat(storedEventType).isEqualTo("L1_PROMOTED");
    }
}
