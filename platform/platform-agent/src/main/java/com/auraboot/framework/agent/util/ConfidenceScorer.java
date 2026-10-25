package com.auraboot.framework.agent.util;

/**
 * Confidence-score formulas for Memory Promotion proposals (PR-65).
 *
 * <p>All outputs are clamped to {@code [0.0, 1.0]}. Formulas come directly
 * from design doc §6.1; keep this file in sync with the plan when tuning.
 *
 * <p>Pure static utility — no dependencies on Spring or DB.
 */
public final class ConfidenceScorer {

    /** Minimum cluster size enforced by {@link #forCrossUserAgreement}. */
    public static final int CROSS_USER_MIN_AGREEMENT = 3;
    /** Minimum similarity threshold used in the confidence formula. */
    public static final double CROSS_USER_MIN_SIMILARITY = 0.85d;
    /** Minimum co-signer count enforced by {@link #forImplicitCoSign}. */
    public static final int CO_SIGN_MIN_COUNT = 3;
    /** Fixed confidence for single-user importance spikes. */
    public static final double IMPORTANCE_SPIKE_CONFIDENCE = 0.5d;

    private ConfidenceScorer() {}

    /**
     * Strategy A — cross-user agreement.
     *
     * <pre>
     * score = 0.5 + 0.1 × min(agreementCount - 3, 5)
     *              + 0.2 × (minSimilarity - 0.85) × (1 / 0.3)
     * </pre>
     *
     * Inputs below the hard minimums return {@code 0.0}.
     */
    public static double forCrossUserAgreement(int agreementCount, double minSimilarity) {
        if (agreementCount < CROSS_USER_MIN_AGREEMENT || minSimilarity < CROSS_USER_MIN_SIMILARITY) {
            return 0.0d;
        }
        double base = 0.5d;
        double agreementBonus = 0.1d * Math.min(agreementCount - CROSS_USER_MIN_AGREEMENT, 5);
        double similarityBonus = 0.2d * (minSimilarity - CROSS_USER_MIN_SIMILARITY) * (1.0d / 0.3d);
        return clamp(base + agreementBonus + similarityBonus);
    }

    /**
     * Strategy B — implicit co-sign via access.
     *
     * <pre>
     * score = 0.6 + 0.1 × min(coSignerCount - 3, 4)
     * </pre>
     *
     * Inputs below the hard minimum return {@code 0.0}.
     */
    public static double forImplicitCoSign(int coSignerCount) {
        if (coSignerCount < CO_SIGN_MIN_COUNT) {
            return 0.0d;
        }
        double base = 0.6d;
        double bonus = 0.1d * Math.min(coSignerCount - CO_SIGN_MIN_COUNT, 4);
        return clamp(base + bonus);
    }

    /**
     * Strategy C — single-user importance spike.
     * Moderate confidence by design: the signal is weak.
     */
    public static double forImportanceSpike() {
        return IMPORTANCE_SPIKE_CONFIDENCE;
    }

    /** Clamp to {@code [0.0, 1.0]} inclusive. */
    public static double clamp(double v) {
        if (Double.isNaN(v)) return 0.0d;
        if (v < 0.0d) return 0.0d;
        if (v > 1.0d) return 1.0d;
        return v;
    }
}
