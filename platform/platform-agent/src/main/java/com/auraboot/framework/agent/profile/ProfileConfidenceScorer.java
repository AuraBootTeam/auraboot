package com.auraboot.framework.agent.profile;

/**
 * User Soul Profile confidence formulas (PR-75, plan §4/§5).
 *
 * <p>All outputs clamped to {@code [0.0, 1.0]}. Pure functions — no
 * dependencies beyond Math. Mirror the spirit of Memory Promotion's
 * {@code ConfidenceScorer} but specialised for the field shapes of
 * {@code ab_agent_user_soul_profile.profile}.
 */
public final class ProfileConfidenceScorer {

    private ProfileConfidenceScorer() {}

    /**
     * Persona confidence: rises with number of profile memories and their
     * average importance.
     * <pre>
     *   0.4 + 0.1 × min(memoryCount, 5) + 0.05 × avgImportance
     * </pre>
     */
    public static double forPersona(int memoryCount, double avgImportance) {
        double count = Math.max(0, memoryCount);
        double imp = Math.max(0, avgImportance);
        double raw = 0.4 + 0.1 * Math.min(count, 5) + 0.05 * imp;
        return clamp(raw);
    }

    /**
     * Preference confidence: rises with evidence; shareable memories
     * get a small bump (they survived the promotion gate).
     * <pre>
     *   0.5 + 0.1 × min(evidenceCount - 1, 4) + (shareable ? 0.1 : 0)
     * </pre>
     */
    public static double forPreference(int evidenceCount, boolean shareable) {
        double raw = 0.5 + 0.1 * Math.min(Math.max(evidenceCount - 1, 0), 4)
                + (shareable ? 0.1 : 0.0);
        return clamp(raw);
    }

    /**
     * Boundary confidence: user-pinned boundaries trust the user;
     * derived ones get a moderate floor.
     */
    public static double forBoundary(int userPinned) {
        return userPinned == 1 ? 0.95 : 0.7;
    }

    /**
     * Profile-level confidence = MIN across all field confidences. A
     * single weak field drags the whole profile down (matches the
     * "gracefully absent" principle of the plan).
     */
    public static double aggregateMin(double... fieldScores) {
        if (fieldScores == null || fieldScores.length == 0) return 0.0;
        double min = Double.POSITIVE_INFINITY;
        for (double v : fieldScores) {
            if (v < min) min = v;
        }
        return clamp(min);
    }

    private static double clamp(double v) {
        if (Double.isNaN(v)) return 0.0;
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }
}
