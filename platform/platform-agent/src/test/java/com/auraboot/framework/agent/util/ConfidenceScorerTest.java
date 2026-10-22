package com.auraboot.framework.agent.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ConfidenceScorer} formulas (PR-65, plan §6.1).
 */
class ConfidenceScorerTest {

    @Test
    @DisplayName("cross_user_agreement: 3 users + 0.85 similarity → 0.50")
    void crossUser_minimumInputs() {
        double s = ConfidenceScorer.forCrossUserAgreement(3, 0.85d);
        assertThat(s).isEqualTo(0.50d, org.assertj.core.data.Offset.offset(0.001d));
    }

    @Test
    @DisplayName("cross_user_agreement: 5 users + 0.90 similarity → 0.5 + 0.2 + ~0.033 ≈ 0.733")
    void crossUser_higherInputs() {
        double s = ConfidenceScorer.forCrossUserAgreement(5, 0.90d);
        // bonus = 0.1 * 2 = 0.2 ; sim = 0.2 * 0.05 * (1/0.3) ≈ 0.0333
        assertThat(s).isEqualTo(0.5d + 0.2d + 0.2d * 0.05d * (1.0d / 0.3d),
                org.assertj.core.data.Offset.offset(0.001d));
    }

    @Test
    @DisplayName("cross_user_agreement: caps at 1.0")
    void crossUser_clampsHigh() {
        double s = ConfidenceScorer.forCrossUserAgreement(100, 1.0d);
        assertThat(s).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("cross_user_agreement: below minimums → 0")
    void crossUser_belowMin() {
        assertThat(ConfidenceScorer.forCrossUserAgreement(2, 0.99d)).isZero();
        assertThat(ConfidenceScorer.forCrossUserAgreement(10, 0.80d)).isZero();
    }

    @Test
    @DisplayName("implicit_co_sign: 3 co-signers → 0.60")
    void coSign_minimum() {
        assertThat(ConfidenceScorer.forImplicitCoSign(3)).isEqualTo(0.60d);
    }

    @Test
    @DisplayName("implicit_co_sign: 7 co-signers → 0.60 + 0.4 = 1.00")
    void coSign_capped() {
        assertThat(ConfidenceScorer.forImplicitCoSign(7)).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("implicit_co_sign: 20 co-signers still clamps to 1.0")
    void coSign_veryHigh() {
        assertThat(ConfidenceScorer.forImplicitCoSign(20)).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("implicit_co_sign: below minimum → 0")
    void coSign_belowMin() {
        assertThat(ConfidenceScorer.forImplicitCoSign(2)).isZero();
    }

    @Test
    @DisplayName("importance_spike: constant 0.5")
    void importanceSpike() {
        assertThat(ConfidenceScorer.forImportanceSpike()).isEqualTo(0.5d);
    }

    @Test
    @DisplayName("clamp handles NaN, negative, >1")
    void clampEdges() {
        assertThat(ConfidenceScorer.clamp(Double.NaN)).isZero();
        assertThat(ConfidenceScorer.clamp(-0.5d)).isZero();
        assertThat(ConfidenceScorer.clamp(2.0d)).isEqualTo(1.0d);
        assertThat(ConfidenceScorer.clamp(0.42d)).isEqualTo(0.42d);
    }
}
