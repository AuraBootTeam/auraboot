package com.auraboot.framework.dsl.compiler.model;

/**
 * Types of compiled steps.
 */
public enum StepType {

    /** BOM tree traversal / explosion. */
    BOM_EXPLODE,

    /** Material requirement calculation. */
    MRP_CALCULATE,

    /** SQL query execution. */
    QUERY_EXECUTE,

    /** Aggregation / rollup. */
    AGGREGATE,

    /** Data transformation / mapping. */
    TRANSFORM,

    /** Cache lookup. */
    CACHE_LOOKUP,

    /** Cache store. */
    CACHE_STORE
}
