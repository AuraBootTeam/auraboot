package com.auraboot.framework.dsl.compiler.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The output of a DSL compilation — an optimized execution plan.
 */
@Data
@Builder
public class CompiledPlan {

    /** Unique plan identifier (for cache keying). */
    private String planId;

    /** The compiler that produced this plan. */
    private String compilerName;

    /** Ordered list of execution steps. */
    private List<CompiledStep> steps;

    /** Optimization hints (e.g. recommended batch size, index suggestions). */
    private Map<String, Object> optimizationHints;

    /** Overall execution strategy. */
    private ExecutionStrategy strategy;

    /** When this plan was compiled. */
    private Instant compiledAt;

    /** Whether the plan was served from cache. */
    @Builder.Default
    private boolean cached = false;

    /** Total estimated cost (sum of step weights). */
    public double estimatedCost() {
        if (steps == null) return 0;
        return steps.stream().mapToDouble(CompiledStep::getCostWeight).sum();
    }
}
