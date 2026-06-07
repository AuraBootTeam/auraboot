package com.auraboot.framework.decision.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * Explain trace for a single condition evaluation (docs/1.md §17.2 test-run explain).
 * Captures the three-valued result of the root and a flat list of leaf steps, plus any
 * reasons a node resolved to UNKNOWN (so callers can surface why a rule did not match).
 */
public record EvalTrace(Truth result, List<Step> steps, List<String> unknownReasons) {

    /** One evaluated leaf: the rendered expression, resolved left/right, and outcome. */
    public record Step(String expr, Object left, Object right, Truth result) {}

    public boolean isMatch() {
        return result == Truth.TRUE;
    }

    public boolean hasUnknown() {
        return unknownReasons != null && !unknownReasons.isEmpty();
    }

    /** Mutable accumulator used while walking the AST. */
    public static final class Collector {
        private final List<Step> steps = new ArrayList<>();
        private final List<String> unknownReasons = new ArrayList<>();

        public void addStep(String expr, Object left, Object right, Truth result) {
            steps.add(new Step(expr, left, right, result));
        }

        public void addUnknownReason(String reason) {
            unknownReasons.add(reason);
        }

        public EvalTrace finish(Truth result) {
            return new EvalTrace(result, List.copyOf(steps), List.copyOf(unknownReasons));
        }
    }
}
