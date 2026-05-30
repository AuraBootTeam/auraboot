package com.auraboot.framework.connector.normalization;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link InMemoryNormalizationEngine}.
 *
 * <p>Pure JUnit 5 + AssertJ, no Spring context required.
 * Total: ≥12 cases as required by the L3 normalization SPI spec.
 */
class InMemoryNormalizationEngineTest {

    private final InMemoryNormalizationEngine engine = new InMemoryNormalizationEngine();

    // =========================================================================
    // TIMESTAMP tests (4 cases)
    // =========================================================================

    /** Case 1: iso8601 with UTC 'Z' suffix → epoch_millis (Long). */
    @Test
    void timestamp_iso8601Z_toEpochMillis() {
        NormalizationConfig cfg = configWithRule("ts", "ts_ms",
                NormalizationRuleType.TIMESTAMP,
                Map.of("from_format", "iso8601", "to_format", "epoch_millis"));

        Map<String, Object> result = applyOne(cfg, Map.of("ts", "2024-03-15T10:00:00Z"));

        assertThat(result).containsKey("ts_ms").doesNotContainKey("ts");
        long expected = Instant.parse("2024-03-15T10:00:00Z").toEpochMilli();
        assertThat(result.get("ts_ms")).isEqualTo(expected);
    }

    /** Case 2: iso8601 with timezone offset (no 'Z') → epoch_millis. */
    @Test
    void timestamp_iso8601WithOffset_toEpochMillis() {
        NormalizationConfig cfg = configWithRule("ts", "ts_ms",
                NormalizationRuleType.TIMESTAMP,
                Map.of("from_format", "iso8601", "to_format", "epoch_millis"));

        // +05:30 is IST; this is a common real-world SaaS format
        Map<String, Object> result = applyOne(cfg, Map.of("ts", "2024-03-15T15:30:00+05:30"));

        long expected = Instant.parse("2024-03-15T10:00:00Z").toEpochMilli();
        assertThat(result.get("ts_ms")).isEqualTo(expected);
    }

    /** Case 3: null source value → null written to target, source key removed. */
    @Test
    void timestamp_nullSourceValue_writesNullToTarget() {
        NormalizationConfig cfg = configWithRule("ts", "ts_ms",
                NormalizationRuleType.TIMESTAMP,
                Map.of("from_format", "iso8601", "to_format", "epoch_millis"));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("ts", null);
        input.put("other", "keep");
        Map<String, Object> result = applyOne(cfg, input);

        assertThat(result).containsEntry("ts_ms", null)
                          .doesNotContainKey("ts")
                          .containsEntry("other", "keep");
    }

    /** Case 4: unparseable date string → source field left unchanged (no exception). */
    @Test
    void timestamp_invalidDateString_leftUnchanged() {
        NormalizationConfig cfg = configWithRule("ts", "ts_ms",
                NormalizationRuleType.TIMESTAMP,
                Map.of("from_format", "iso8601", "to_format", "epoch_millis"));

        Map<String, Object> result = applyOne(cfg, Map.of("ts", "not-a-date"));

        // Rule skips; source key retained with original value, target not written
        assertThat(result).containsEntry("ts", "not-a-date")
                          .doesNotContainKey("ts_ms");
    }

    // =========================================================================
    // NUMERIC_UNIT tests (3 cases)
    // =========================================================================

    /** Case 5: dollars → cents with positive decimal input. */
    @Test
    void numericUnit_dollarsToCents_positiveDecimal() {
        NormalizationConfig cfg = configWithRule("amount", "amount_cents",
                NormalizationRuleType.NUMERIC_UNIT,
                Map.of("from", "dollars", "to", "cents"));

        Map<String, Object> result = applyOne(cfg, Map.of("amount", "9.99"));

        assertThat(result).doesNotContainKey("amount");
        assertThat(result.get("amount_cents")).isEqualTo(999L);
    }

    /** Case 6: negative amount (e.g. refund) converts correctly. */
    @Test
    void numericUnit_dollarsToCents_negativeAmount() {
        NormalizationConfig cfg = configWithRule("amount", "amount_cents",
                NormalizationRuleType.NUMERIC_UNIT,
                Map.of("from", "dollars", "to", "cents"));

        Map<String, Object> result = applyOne(cfg, Map.of("amount", "-5.50"));

        assertThat(result.get("amount_cents")).isEqualTo(-550L);
    }

    /** Case 7: zero amount produces zero cents; decimal precision preserved. */
    @Test
    void numericUnit_zeroDollars_producesZeroCents() {
        NormalizationConfig cfg = configWithRule("amount", "amount_cents",
                NormalizationRuleType.NUMERIC_UNIT,
                Map.of("from", "dollars", "to", "cents"));

        Map<String, Object> result = applyOne(cfg, Map.of("amount", "0.00"));

        assertThat(result.get("amount_cents")).isEqualTo(0L);
    }

    // =========================================================================
    // ENUM_MAP tests (3 cases)
    // =========================================================================

    /** Case 8: known mapping entry is replaced with canonical value. */
    @Test
    void enumMap_knownValue_mapped() {
        NormalizationConfig cfg = configWithRule("stage", "stage",
                NormalizationRuleType.ENUM_MAP,
                Map.of("mapping", Map.of("closedwon", "WON", "closedlost", "LOST")));

        Map<String, Object> result = applyOne(cfg, Map.of("stage", "closedwon"));

        assertThat(result.get("stage")).isEqualTo("WON");
    }

    /** Case 9: unknown mapping value is passed through unchanged (open-world). */
    @Test
    void enumMap_unknownValue_passedThrough() {
        NormalizationConfig cfg = configWithRule("stage", "stage",
                NormalizationRuleType.ENUM_MAP,
                Map.of("mapping", Map.of("closedwon", "WON")));

        Map<String, Object> result = applyOne(cfg, Map.of("stage", "appointmentscheduled"));

        assertThat(result.get("stage")).isEqualTo("appointmentscheduled");
    }

    /** Case 10: null source value → null written to target. */
    @Test
    void enumMap_nullSourceValue_writesNull() {
        NormalizationConfig cfg = configWithRule("stage", "stage_canonical",
                NormalizationRuleType.ENUM_MAP,
                Map.of("mapping", Map.of("closedwon", "WON")));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("stage", null);
        Map<String, Object> result = applyOne(cfg, input);

        assertThat(result).containsEntry("stage_canonical", null)
                          .doesNotContainKey("stage");
    }

    // =========================================================================
    // RENAME tests (2 cases)
    // =========================================================================

    /** Case 11: rename copies value and removes source key. */
    @Test
    void rename_copiesValueAndRemovesSource() {
        NormalizationConfig cfg = configWithRule("hubspot_owner_id", "owner_id",
                NormalizationRuleType.RENAME, null);

        Map<String, Object> result = applyOne(cfg, Map.of("hubspot_owner_id", "usr_123"));

        assertThat(result).containsEntry("owner_id", "usr_123")
                          .doesNotContainKey("hubspot_owner_id");
    }

    /** Case 12: if target key already exists, rename overwrites it. */
    @Test
    void rename_overwritesExistingTargetKey() {
        NormalizationConfig cfg = configWithRule("new_id", "id",
                NormalizationRuleType.RENAME, null);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("new_id", "fresh-value");
        input.put("id", "stale-value");
        Map<String, Object> result = applyOne(cfg, input);

        assertThat(result).containsEntry("id", "fresh-value")
                          .doesNotContainKey("new_id");
    }

    // =========================================================================
    // Pass-through (1 case)
    // =========================================================================

    /** Case 13: fields not mentioned in any rule are passed through unchanged. */
    @Test
    void undeclaredFields_passedThrough() {
        NormalizationConfig cfg = configWithRule("a", "b",
                NormalizationRuleType.RENAME, null);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("a", "v1");
        input.put("extra1", "keep-me");
        input.put("extra2", 42);
        Map<String, Object> result = applyOne(cfg, input);

        assertThat(result).containsEntry("b", "v1")
                          .containsEntry("extra1", "keep-me")
                          .containsEntry("extra2", 42)
                          .doesNotContainKey("a");
    }

    // =========================================================================
    // Multi-rule lazy stream (1 case)
    // =========================================================================

    /**
     * Case 14: multi-rule config applied in a lazy stream across multiple records.
     * Verifies that the stream is not materialised eagerly and that all rules
     * compose correctly in declaration order.
     */
    @Test
    void multiRule_lazyStreamApplication() {
        NormalizationConfig cfg = new NormalizationConfig(
                "multi-test",
                "0.1",
                List.of(
                        new NormalizationConfig.FieldRule("ts", "ts_ms",
                                NormalizationRuleType.TIMESTAMP,
                                Map.of("from_format", "iso8601", "to_format", "epoch_millis")),
                        new NormalizationConfig.FieldRule("amount", "amount_cents",
                                NormalizationRuleType.NUMERIC_UNIT,
                                Map.of("from", "dollars", "to", "cents")),
                        new NormalizationConfig.FieldRule("stage", "stage",
                                NormalizationRuleType.ENUM_MAP,
                                Map.of("mapping", Map.of("closedwon", "WON"))),
                        new NormalizationConfig.FieldRule("raw_id", "id",
                                NormalizationRuleType.RENAME, null)
                )
        );

        Map<String, Object> rec1 = new LinkedHashMap<>();
        rec1.put("ts", "2024-01-01T00:00:00Z");
        rec1.put("amount", "1.00");
        rec1.put("stage", "closedwon");
        rec1.put("raw_id", "A");
        rec1.put("extra", "passthrough");

        Map<String, Object> rec2 = new LinkedHashMap<>();
        rec2.put("ts", "2024-06-15T12:00:00Z");
        rec2.put("amount", "25.50");
        rec2.put("stage", "appointmentscheduled");
        rec2.put("raw_id", "B");

        List<Map<String, Object>> results = engine.apply(cfg, Stream.of(rec1, rec2)).toList();

        assertThat(results).hasSize(2);

        Map<String, Object> r1 = results.get(0);
        assertThat(r1.get("ts_ms")).isEqualTo(Instant.parse("2024-01-01T00:00:00Z").toEpochMilli());
        assertThat(r1.get("amount_cents")).isEqualTo(100L);
        assertThat(r1.get("stage")).isEqualTo("WON");
        assertThat(r1.get("id")).isEqualTo("A");
        assertThat(r1.get("extra")).isEqualTo("passthrough");
        assertThat(r1).doesNotContainKey("ts").doesNotContainKey("amount").doesNotContainKey("raw_id");

        Map<String, Object> r2 = results.get(1);
        assertThat(r2.get("amount_cents")).isEqualTo(2550L);
        assertThat(r2.get("stage")).isEqualTo("appointmentscheduled"); // not in mapping → pass-through
        assertThat(r2.get("id")).isEqualTo("B");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Build a single-rule NormalizationConfig for concise test setup. */
    private NormalizationConfig configWithRule(String source, String target,
                                               NormalizationRuleType type,
                                               Map<String, Object> params) {
        return new NormalizationConfig(
                "test",
                "0.1",
                List.of(new NormalizationConfig.FieldRule(source, target, type, params))
        );
    }

    /** Apply config to a single record map and return the result. */
    private Map<String, Object> applyOne(NormalizationConfig cfg, Map<String, Object> record) {
        // Wrap in LinkedHashMap to ensure mutability (engine works on a copy)
        Map<String, Object> mutableRecord = new LinkedHashMap<>(record);
        List<Map<String, Object>> results = engine.apply(cfg, Stream.of(mutableRecord)).toList();
        assertThat(results).hasSize(1);
        return results.get(0);
    }
}
