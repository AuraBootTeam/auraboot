package com.auraboot.framework.dsl.compiler.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * A single step inside a compiled execution plan.
 */
@Data
@Builder
public class CompiledStep {

    /** Human-readable step name, e.g. "explode-bom-level-2". */
    private String name;

    /** Step type used for dispatching at runtime. */
    private StepType type;

    /** Execution order (lower = earlier). */
    private int order;

    /** Arbitrary parameters consumed by the step executor. */
    private Map<String, Object> parameters;

    /** Estimated cost weight (unitless, for scheduling hints). */
    @Builder.Default
    private double costWeight = 1.0;
}
