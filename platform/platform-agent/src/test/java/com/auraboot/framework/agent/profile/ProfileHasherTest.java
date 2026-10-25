package com.auraboot.framework.agent.profile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link ProfileHasher} (PR-75). */
@DisplayName("ProfileHasher (PR-75)")
class ProfileHasherTest {

    @Test
    @DisplayName("same content → same hash regardless of top-level key order")
    void sameContentStableHash() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("persona", Map.of("text", "x", "confidence", 0.5));
        a.put("language", "zh-CN");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("language", "zh-CN");
        b.put("persona", Map.of("confidence", 0.5, "text", "x"));

        assertThat(ProfileHasher.hashProfile(a)).isEqualTo(ProfileHasher.hashProfile(b));
    }

    @Test
    @DisplayName("mutable meta.derivation_run_id excluded from hash")
    void metaStrippedFromHash() {
        Map<String, Object> base = Map.of(
                "persona", Map.of("text", "x"),
                "meta", Map.of("derivation_run_id", "run_A")
        );
        Map<String, Object> other = Map.of(
                "persona", Map.of("text", "x"),
                "meta", Map.of("derivation_run_id", "run_B")
        );
        assertThat(ProfileHasher.hashProfile(base)).isEqualTo(ProfileHasher.hashProfile(other));
    }

    @Test
    @DisplayName("per-field last_derived_at excluded from hash")
    void perFieldTimestampStripped() {
        Map<String, Object> a = Map.of(
                "persona", new LinkedHashMap<>(Map.of(
                        "text", "x", "last_derived_at", "2026-01-01T00:00:00Z")));
        Map<String, Object> b = Map.of(
                "persona", new LinkedHashMap<>(Map.of(
                        "text", "x", "last_derived_at", "2026-04-19T00:00:00Z")));
        assertThat(ProfileHasher.hashProfile(a)).isEqualTo(ProfileHasher.hashProfile(b));
    }

    @Test
    @DisplayName("different persona text → different hash")
    void differentContentDifferentHash() {
        Map<String, Object> a = Map.of("persona", Map.of("text", "alpha"));
        Map<String, Object> b = Map.of("persona", Map.of("text", "beta"));
        assertThat(ProfileHasher.hashProfile(a)).isNotEqualTo(ProfileHasher.hashProfile(b));
    }

    @Test
    @DisplayName("null input → null hash")
    void nullInput() {
        assertThat(ProfileHasher.hashProfile(null)).isNull();
    }
}
