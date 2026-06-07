package com.auraboot.framework.decision.model;

import java.util.List;
import java.util.Map;

/**
 * Standard, typed result of a decision evaluation (docs/1.md §12). The runtime is a pure
 * decision service: it returns {@code outputs / violations / actionPlans} and never executes
 * side effects (those are run by Automation / BPM / SLA / EventPolicy executors).
 */
public record DecisionResult(
        String traceId,
        String decisionCode,
        Integer decisionVersion,
        DecisionKind kind,
        RuntimeAdapter engineType,
        ResultType resultType,
        DecisionStatus status,
        boolean matched,
        Map<String, Object> outputs,
        List<Violation> violations,
        List<ActionPlan> actionPlans,
        List<MatchedRule> matchedRules,
        List<String> errors,
        List<String> unknownReasons,
        DecisionMetrics metrics
) {

    /** A single field-level violation (docs/1.md §16.3 CrossField). */
    public record Violation(String fieldPath, String code, String message, String severity) {}

    /** A planned (not executed) action (docs/1.md §12.5). */
    public record ActionPlan(String planId, String type, Map<String, Object> target,
                             Map<String, Object> payload, String idempotencyKey) {}

    /** Explanation of which rule matched and why (docs/1.md §12.2). */
    public record MatchedRule(String ruleId, String ruleName, String reason) {}

    /** Evaluation metrics (docs/1.md §24.2). */
    public record DecisionMetrics(long durationMs, long adapterDurationMs, boolean cacheHit) {
        public static DecisionMetrics of(long durationMs, long adapterDurationMs) {
            return new DecisionMetrics(durationMs, adapterDurationMs, false);
        }
    }

    public static Builder builder(String decisionCode) {
        return new Builder(decisionCode);
    }

    public static final class Builder {
        private String traceId;
        private final String decisionCode;
        private Integer decisionVersion;
        private DecisionKind kind;
        private RuntimeAdapter engineType;
        private ResultType resultType = ResultType.BOOLEAN;
        private DecisionStatus status = DecisionStatus.NOT_MATCHED;
        private boolean matched;
        private Map<String, Object> outputs = Map.of();
        private List<Violation> violations = List.of();
        private List<ActionPlan> actionPlans = List.of();
        private List<MatchedRule> matchedRules = List.of();
        private List<String> errors = List.of();
        private List<String> unknownReasons = List.of();
        private DecisionMetrics metrics = DecisionMetrics.of(0, 0);

        private Builder(String decisionCode) {
            this.decisionCode = decisionCode;
        }

        public Builder traceId(String v) { this.traceId = v; return this; }
        public Builder version(Integer v) { this.decisionVersion = v; return this; }
        public Builder kind(DecisionKind v) { this.kind = v; return this; }
        public Builder engineType(RuntimeAdapter v) { this.engineType = v; return this; }
        public Builder resultType(ResultType v) { this.resultType = v; return this; }
        public Builder status(DecisionStatus v) { this.status = v; return this; }
        public Builder matched(boolean v) { this.matched = v; return this; }
        public Builder outputs(Map<String, Object> v) { this.outputs = v == null ? Map.of() : v; return this; }
        public Builder violations(List<Violation> v) { this.violations = v == null ? List.of() : v; return this; }
        public Builder actionPlans(List<ActionPlan> v) { this.actionPlans = v == null ? List.of() : v; return this; }
        public Builder matchedRules(List<MatchedRule> v) { this.matchedRules = v == null ? List.of() : v; return this; }
        public Builder errors(List<String> v) { this.errors = v == null ? List.of() : v; return this; }
        public Builder unknownReasons(List<String> v) { this.unknownReasons = v == null ? List.of() : v; return this; }
        public Builder metrics(DecisionMetrics v) { this.metrics = v; return this; }

        public DecisionResult build() {
            return new DecisionResult(traceId, decisionCode, decisionVersion, kind, engineType,
                    resultType, status, matched, outputs, violations, actionPlans, matchedRules,
                    errors, unknownReasons, metrics);
        }
    }
}
