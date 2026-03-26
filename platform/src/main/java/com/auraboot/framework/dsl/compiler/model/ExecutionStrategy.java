package com.auraboot.framework.dsl.compiler.model;

/**
 * Execution strategy for compiled plans.
 */
public enum ExecutionStrategy {

    /** Steps execute one after another. */
    SEQUENTIAL,

    /** Independent steps execute concurrently. */
    PARALLEL,

    /** Steps are batched for bulk processing. */
    BATCH
}
