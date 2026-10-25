package com.auraboot.framework.agent.profile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link ProfileConfidenceScorer} (PR-75). */
@DisplayName("ProfileConfidenceScorer (PR-75)")
class ProfileConfidenceScorerTest {

    private static final double EPS = 1e-9;

    @Test
    @DisplayName("persona: base 0.4 + 0.1×min(count,5) + 0.05×avgImportance")
    void persona_formula() {
        assertThat(ProfileConfidenceScorer.forPersona(0, 0)).isCloseTo(0.4, within());
        assertThat(ProfileConfidenceScorer.forPersona(3, 6)).isCloseTo(0.4 + 0.3 + 0.3, within());
        // 0.4 + 0.5 + 0.5 = 1.4 → clamped to 1.0
        assertThat(ProfileConfidenceScorer.forPersona(5, 10)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("persona: count cap at 5")
    void persona_countCapped() {
        double five = ProfileConfidenceScorer.forPersona(5, 0);
        double fifty = ProfileConfidenceScorer.forPersona(50, 0);
        assertThat(five).isCloseTo(0.9, within());
        assertThat(fifty).isCloseTo(0.9, within());
    }

    @Test
    @DisplayName("persona: clamps to [0,1]")
    void persona_clamp() {
        assertThat(ProfileConfidenceScorer.forPersona(5, 1000)).isEqualTo(1.0);
        assertThat(ProfileConfidenceScorer.forPersona(-5, -5)).isEqualTo(0.4);
    }

    @Test
    @DisplayName("preference: base 0.5 + 0.1×min(ev-1,4) + shareable bump")
    void preference_formula() {
        assertThat(ProfileConfidenceScorer.forPreference(1, false)).isCloseTo(0.5, within());
        assertThat(ProfileConfidenceScorer.forPreference(3, false)).isCloseTo(0.7, within());
        assertThat(ProfileConfidenceScorer.forPreference(5, true)).isCloseTo(0.5 + 0.4 + 0.1, within());
        assertThat(ProfileConfidenceScorer.forPreference(99, true)).isCloseTo(1.0, within());
    }

    @Test
    @DisplayName("preference: evidence<=0 treated as 0 extra")
    void preference_zeroEvidence() {
        assertThat(ProfileConfidenceScorer.forPreference(0, false)).isCloseTo(0.5, within());
    }

    @Test
    @DisplayName("boundary: userPinned=1 ⇒ 0.95; else 0.7")
    void boundary_formula() {
        assertThat(ProfileConfidenceScorer.forBoundary(1)).isEqualTo(0.95);
        assertThat(ProfileConfidenceScorer.forBoundary(0)).isEqualTo(0.7);
    }

    @Test
    @DisplayName("aggregateMin: minimum of non-empty scores")
    void aggregate_min() {
        assertThat(ProfileConfidenceScorer.aggregateMin(0.9, 0.7, 0.85)).isCloseTo(0.7, within());
        assertThat(ProfileConfidenceScorer.aggregateMin(0.5)).isCloseTo(0.5, within());
        assertThat(ProfileConfidenceScorer.aggregateMin()).isEqualTo(0.0);
        assertThat(ProfileConfidenceScorer.aggregateMin(1.5, 0.9)).isEqualTo(0.9);
    }

    @Test
    @DisplayName("aggregateMin: null / negative clamps")
    void aggregate_clamp() {
        assertThat(ProfileConfidenceScorer.aggregateMin((double[]) null)).isEqualTo(0.0);
        assertThat(ProfileConfidenceScorer.aggregateMin(-0.5, 0.8)).isEqualTo(0.0);
    }

    private static org.assertj.core.data.Offset<Double> within() {
        return org.assertj.core.data.Offset.offset(EPS);
    }
}
