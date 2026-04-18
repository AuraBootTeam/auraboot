package com.auraboot.framework.agent.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OutputSignatureProjector} (PR-60).
 *
 * These tests encode the deliberate trade-off made in PR-60: Shadow Mode's
 * {@code output_match} measures "same outcome at the semantic level", not
 * byte-equality. See the class javadoc for why.
 */
@DisplayName("OutputSignatureProjector (PR-60)")
class OutputSignatureProjectorTest {

    // ========================================================================
    // Query projection
    // ========================================================================

    @Test
    @DisplayName("query projection ignores rows order")
    void query_projection_ignores_rows_order() {
        Map<String, Object> a = Map.of("total", 3L, "rows", List.of(
                Map.of("id", 3), Map.of("id", 1), Map.of("id", 2)));
        Map<String, Object> b = Map.of("total", 3L, "rows", List.of(
                Map.of("id", 1), Map.of("id", 2), Map.of("id", 3)));

        String hashA = OutputSignatureProjector.computeMatchHash(
                OutputSignatureProjector.projectShadow("nq_leads", a));
        String hashB = OutputSignatureProjector.computeMatchHash(
                OutputSignatureProjector.projectShadow("nq_leads", b));

        assertThat(hashA).isNotNull().hasSize(64);
        assertThat(hashA).isEqualTo(hashB);
    }

    @Test
    @DisplayName("query projection is record-count-based — different row content, same count → same hash")
    void query_projection_ignores_row_content() {
        // Deliberate: Shadow Mode asks "did shadow touch the same *number* of
        // records as the original?" Original read actions persist only
        // affected_count (ActionRecorder#recordReadAction writes no rows),
        // so comparing row content asymmetrically is impossible. See
        // OutputSignatureProjector javadoc.
        Map<String, Object> a = Map.of("total", 3L, "rows", List.of(
                Map.of("id", 1, "name", "Alice"),
                Map.of("id", 2, "name", "Bob"),
                Map.of("id", 3, "name", "Carol")));
        Map<String, Object> b = Map.of("total", 3L, "rows", List.of(
                Map.of("id", 7, "name", "Zed"),
                Map.of("id", 8, "name", "Yvette"),
                Map.of("id", 9, "name", "Xi")));

        String hashA = OutputSignatureProjector.computeMatchHash(
                OutputSignatureProjector.projectShadow("nq_leads", a));
        String hashB = OutputSignatureProjector.computeMatchHash(
                OutputSignatureProjector.projectShadow("nq_leads", b));

        assertThat(hashA).isEqualTo(hashB);
    }

    @Test
    @DisplayName("query projection: shadow total=N matches original affected_count=N")
    void query_projection_matches_shadow_to_original() {
        Map<String, Object> shadow = Map.of("total", 5L, "rows", List.of());
        Map<String, Object> projShadow = OutputSignatureProjector.projectShadow("nq_leads", shadow);
        Map<String, Object> projOrig = OutputSignatureProjector.projectOriginal(
                "nq_leads", "success", null, 5, null);

        assertThat(OutputSignatureProjector.computeMatchHash(projShadow))
                .isEqualTo(OutputSignatureProjector.computeMatchHash(projOrig));
    }

    @Test
    @DisplayName("query projection: different record_counts produce different hashes")
    void query_projection_different_counts_differ() {
        Map<String, Object> projShadow = OutputSignatureProjector.projectShadow(
                "nq_leads", Map.of("total", 3L));
        Map<String, Object> projOrig = OutputSignatureProjector.projectOriginal(
                "nq_leads", "success", null, 5, null);

        assertThat(OutputSignatureProjector.computeMatchHash(projShadow))
                .isNotEqualTo(OutputSignatureProjector.computeMatchHash(projOrig));
    }

    // ========================================================================
    // Command projection
    // ========================================================================

    @Test
    @DisplayName("command projection: same tool_ref + target_record_id + success across phases → same hash")
    void command_projection_equals_across_phases() {
        // Shadow-side returns phase_reached="COMPLETE"; original side carries
        // action_status="success". Different shape but same semantic outcome
        // (success on the same record) → same hash.
        Map<String, Object> shadow = Map.of(
                "command_code", "update_lead",
                "phase_reached", "COMPLETE",
                "data", Map.of("recordId", "LEAD-42"));
        Map<String, Object> projShadow = OutputSignatureProjector.projectShadow("cmd_update_lead", shadow);
        Map<String, Object> projOrig = OutputSignatureProjector.projectOriginal(
                "cmd_update_lead", "success", "LEAD-42", null, null);

        assertThat(OutputSignatureProjector.computeMatchHash(projShadow))
                .isEqualTo(OutputSignatureProjector.computeMatchHash(projOrig));
    }

    @Test
    @DisplayName("command projection: different target_record_id → different hash")
    void command_projection_record_id_matters() {
        Map<String, Object> projA = OutputSignatureProjector.projectOriginal(
                "cmd_update_lead", "success", "LEAD-42", null, null);
        Map<String, Object> projB = OutputSignatureProjector.projectOriginal(
                "cmd_update_lead", "success", "LEAD-99", null, null);

        assertThat(OutputSignatureProjector.computeMatchHash(projA))
                .isNotEqualTo(OutputSignatureProjector.computeMatchHash(projB));
    }

    @Test
    @DisplayName("command projection: null target_record_id matches null on both sides")
    void command_projection_null_record_id_matches() {
        Map<String, Object> shadow = new LinkedHashMap<>();
        shadow.put("command_code", "no_op");
        shadow.put("phase_reached", "COMPLETE");
        shadow.put("data", Map.of());  // no recordId

        Map<String, Object> projShadow = OutputSignatureProjector.projectShadow("cmd_no_op", shadow);
        Map<String, Object> projOrig = OutputSignatureProjector.projectOriginal(
                "cmd_no_op", "success", null, null, null);

        assertThat(OutputSignatureProjector.computeMatchHash(projShadow))
                .isEqualTo(OutputSignatureProjector.computeMatchHash(projOrig));
    }

    @Test
    @DisplayName("N-R3-1: explicit success=false wins over heuristic (non-terminal phase)")
    void command_success_from_explicit_key_wins_over_heuristic() {
        // phase_reached is non-null (heuristic would say success=true), but
        // the explicit success=false key marks this as a partial failure.
        Map<String, Object> shadow = new LinkedHashMap<>();
        shadow.put("command_code", "cmd_x");
        shadow.put("phase_reached", "validation");
        shadow.put("success", false);
        shadow.put("data", Map.of("recordId", "LEAD-42"));

        Map<String, Object> projExplicit = OutputSignatureProjector.projectShadow("cmd_x", shadow);
        assertThat(projExplicit.get("success")).isEqualTo(false);

        // Without the explicit key, the heuristic falls back to "phase_reached non-null".
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("command_code", "cmd_x");
        legacy.put("phase_reached", "validation");
        legacy.put("data", Map.of("recordId", "LEAD-42"));
        Map<String, Object> projLegacy = OutputSignatureProjector.projectShadow("cmd_x", legacy);
        assertThat(projLegacy.get("success")).isEqualTo(true);
    }

    @Test
    @DisplayName("N-R3-1: command_success defaults to false when no explicit key and no phase_reached")
    void command_success_defaults_false_when_no_explicit_key_and_no_phase_reached() {
        Map<String, Object> shadow = new LinkedHashMap<>();
        shadow.put("command_code", "cmd_x");
        shadow.put("data", Map.of());
        Map<String, Object> proj = OutputSignatureProjector.projectShadow("cmd_x", shadow);
        assertThat(proj.get("success")).isEqualTo(false);
    }

    // ========================================================================
    // Unknown tool_ref fallback
    // ========================================================================

    @Test
    @DisplayName("unknown tool_ref falls back to full canonical hash of raw payload")
    void unknown_tool_ref_falls_back_to_full_hash() {
        Map<String, Object> raw = Map.of("alpha", 1, "beta", "two");
        Map<String, Object> proj = OutputSignatureProjector.projectShadow("mcp_something", raw);

        assertThat(proj.get("type")).isEqualTo(OutputSignatureProjector.TYPE_UNKNOWN);
        assertThat(proj.get("tool_ref")).isEqualTo("mcp_something");
        assertThat(proj.get("raw")).isEqualTo(raw);

        // Stable + hashable
        String hash = OutputSignatureProjector.computeMatchHash(proj);
        assertThat(hash).isNotNull().hasSize(64);
    }

    @Test
    @DisplayName("unknown tool_ref: shadow/original with same raw map produce same hash")
    void unknown_tool_ref_same_raw_matches() {
        Map<String, Object> raw = Map.of("foo", "bar");
        Map<String, Object> projShadow = OutputSignatureProjector.projectShadow("mystery", raw);
        // Original side parses from snapshot JSON
        Map<String, Object> projOrig = OutputSignatureProjector.projectOriginal(
                "mystery", "success", null, null, "{\"foo\":\"bar\"}");

        assertThat(OutputSignatureProjector.computeMatchHash(projShadow))
                .isEqualTo(OutputSignatureProjector.computeMatchHash(projOrig));
    }

    // ========================================================================
    // Tool-family isolation
    // ========================================================================

    @Test
    @DisplayName("type tag prevents cross-family collision: query vs command vs unknown → different hashes")
    void type_tag_prevents_cross_family_collision() {
        // All three carry tool_ref=x and would otherwise hash to an empty-ish map.
        Map<String, Object> q = OutputSignatureProjector.projectShadow("nq_x", Map.of("total", 0L));
        Map<String, Object> c = OutputSignatureProjector.projectShadow(
                "cmd_x", Map.of("phase_reached", "COMPLETE", "data", Map.of()));
        Map<String, Object> u = OutputSignatureProjector.projectShadow("mystery_x", Map.of());

        String hq = OutputSignatureProjector.computeMatchHash(q);
        String hc = OutputSignatureProjector.computeMatchHash(c);
        String hu = OutputSignatureProjector.computeMatchHash(u);
        assertThat(hq).isNotEqualTo(hc);
        assertThat(hq).isNotEqualTo(hu);
        assertThat(hc).isNotEqualTo(hu);
    }
}
