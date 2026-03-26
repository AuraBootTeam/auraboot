package com.auraboot.framework.meta.service;

import java.util.List;
import java.util.Map;

/**
 * Virtual Field Engine interface.
 * Handles computed field evaluation, materialization, and dependency graph analysis.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
public interface VirtualFieldEngine {

    /**
     * Evaluate a compute expression in-memory (for TRANSIENT fields or preview)
     *
     * @param expression SpEL expression
     * @param context variable context (field values)
     * @return computed result
     */
    Object evaluate(String expression, Map<String, Object> context);

    /**
     * Recalculate materialized fields after a record change.
     * Finds all MATERIALIZED fields whose dependencies include the changed fields,
     * and executes SQL UPDATE with their compute expressions.
     *
     * @param modelCode model code
     * @param recordId record ID (primary key value)
     * @param changedFields list of field codes that changed
     */
    void materialize(String modelCode, String recordId, List<String> changedFields);

    /**
     * Validate dependency graph for cycles.
     * Returns empty list if no cycles, or list of field codes forming a cycle.
     *
     * @param modelCode model code
     * @return list of fields forming a cycle (empty if valid)
     */
    List<String> validateDependencyGraph(String modelCode);

    /**
     * Get topological order of computed fields for safe evaluation.
     *
     * @param modelCode model code
     * @return ordered list of field codes (dependencies first)
     */
    List<String> getComputationOrder(String modelCode);
}
