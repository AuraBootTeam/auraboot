package com.auraboot.framework.agent.service;

/**
 * ThreadLocal carrier for the current step index within a Run.
 *
 * Set by StepLoopService immediately before dispatching each step's tool calls,
 * cleared in a finally block. Read by ActionRecorder so that every Action row
 * points back to its originating step (spec §1: "step_index ↔ execution_plan[i]
 * 一一对应").
 *
 * Using ThreadLocal instead of threading the index through every
 * ToolExecutionPort signature keeps the port interface stable for callers
 * that don't care about Step structure (e.g. ad-hoc tool invocations).
 */
public final class StepContext {

    private static final ThreadLocal<Integer> CURRENT_STEP_INDEX = new ThreadLocal<>();

    private StepContext() {}

    public static void setStepIndex(int stepIndex) {
        CURRENT_STEP_INDEX.set(stepIndex);
    }

    /** Returns the current step index, or null if not inside a step loop. */
    public static Integer getStepIndex() {
        return CURRENT_STEP_INDEX.get();
    }

    public static void clear() {
        CURRENT_STEP_INDEX.remove();
    }
}
