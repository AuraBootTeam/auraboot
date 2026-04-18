package com.auraboot.framework.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FidelityGrader pins two invariants (specs/01 §1.3 v1.1):
 *   1. per-substrate fidelity grading is deterministic
 *   2. command_signature is canonical (key order / nested maps do not shift the hash)
 */
@DisplayName("FidelityGrader — grade + commandSignature")
class FidelityGraderTest {

    private final FidelityGrader grader = new FidelityGrader(new ObjectMapper());

    @Test
    @DisplayName("dsl_command and dsl_query are full fidelity")
    void dsl_is_full_fidelity() {
        assertThat(grader.grade("dsl_command")).isEqualTo(FidelityGrader.FIDELITY_FULL);
        assertThat(grader.grade("dsl_query")).isEqualTo(FidelityGrader.FIDELITY_FULL);
    }

    @Test
    @DisplayName("api_call and mcp are semantic fidelity")
    void api_and_mcp_are_semantic() {
        assertThat(grader.grade("api_call")).isEqualTo(FidelityGrader.FIDELITY_SEMANTIC);
        assertThat(grader.grade("mcp")).isEqualTo(FidelityGrader.FIDELITY_SEMANTIC);
    }

    @Test
    @DisplayName("code and llm_native are blackbox fidelity")
    void code_and_llm_are_blackbox() {
        assertThat(grader.grade("code")).isEqualTo(FidelityGrader.FIDELITY_BLACKBOX);
        assertThat(grader.grade("llm_native")).isEqualTo(FidelityGrader.FIDELITY_BLACKBOX);
    }

    @Test
    @DisplayName("unknown or null substrate defaults to blackbox (safe pessimistic)")
    void unknown_defaults_to_blackbox() {
        assertThat(grader.grade(null)).isEqualTo(FidelityGrader.FIDELITY_BLACKBOX);
        assertThat(grader.grade("something-new")).isEqualTo(FidelityGrader.FIDELITY_BLACKBOX);
    }

    @Test
    @DisplayName("commandSignature is stable regardless of arg-map insertion order")
    void signature_canonical_key_order() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("foo", 1);
        a.put("bar", "x");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("bar", "x");
        b.put("foo", 1);

        assertThat(grader.commandSignature("cmd_update_lead", a))
                .isEqualTo(grader.commandSignature("cmd_update_lead", b));
    }

    @Test
    @DisplayName("commandSignature recursively sorts nested map keys")
    void signature_canonical_nested() {
        Map<String, Object> nested1 = new LinkedHashMap<>();
        nested1.put("a", 1);
        nested1.put("b", 2);
        Map<String, Object> nested2 = new LinkedHashMap<>();
        nested2.put("b", 2);
        nested2.put("a", 1);

        Map<String, Object> m1 = new LinkedHashMap<>();
        m1.put("outer", nested1);
        Map<String, Object> m2 = new LinkedHashMap<>();
        m2.put("outer", nested2);

        assertThat(grader.commandSignature("cmd_X", m1))
                .isEqualTo(grader.commandSignature("cmd_X", m2));
    }

    @Test
    @DisplayName("different commandCode → different signature")
    void signature_distinguishes_command_code() {
        Map<String, Object> args = Map.of("x", 1);
        assertThat(grader.commandSignature("cmd_A", args))
                .isNotEqualTo(grader.commandSignature("cmd_B", args));
    }

    @Test
    @DisplayName("different args → different signature (same command)")
    void signature_distinguishes_args() {
        assertThat(grader.commandSignature("cmd_X", Map.of("k", 1)))
                .isNotEqualTo(grader.commandSignature("cmd_X", Map.of("k", 2)));
    }

    @Test
    @DisplayName("null or blank commandCode yields null signature (can't dedup opaque ops)")
    void signature_null_on_blank_command() {
        assertThat(grader.commandSignature(null, Map.of())).isNull();
        assertThat(grader.commandSignature("  ", Map.of())).isNull();
    }

    @Test
    @DisplayName("hashText produces hex SHA-256 of the input")
    void hash_text_shape() {
        String h = grader.hashText("hello world");
        assertThat(h).hasSize(64);
        assertThat(h).matches("[0-9a-f]{64}");
        // Known SHA-256 of "hello world"
        assertThat(h).isEqualTo("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9");
    }

    @Test
    @DisplayName("hashText null input returns null")
    void hash_text_null() {
        assertThat(grader.hashText(null)).isNull();
    }
}
