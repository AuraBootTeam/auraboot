package com.auraboot.framework.decision.model;

/** Per-evaluation options (docs/1.md §10.1). */
public record DecisionEvaluateOptions(boolean explain, boolean dryRun, boolean debug) {
    public static DecisionEvaluateOptions defaults() {
        return new DecisionEvaluateOptions(false, false, false);
    }

    public static DecisionEvaluateOptions explaining() {
        return new DecisionEvaluateOptions(true, false, false);
    }
}
