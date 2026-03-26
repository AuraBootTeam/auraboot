package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.dto.ChangeRecord;
import com.auraboot.framework.meta.dto.FieldChange;

import java.util.List;
import java.util.Map;

/**
 * Change tracking engine.
 * Computes field-level diffs and records change logs.
 *
 * @since 5.1.0
 */
public interface ChangeTracker {

    /**
     * Compute field-level differences between two record states.
     *
     * @param before    record state before the change (null for CREATE)
     * @param after     record state after the change (null for DELETE)
     * @param modelCode model code for resolving field labels
     * @return list of field changes
     */
    List<FieldChange> diff(Map<String, Object> before, Map<String, Object> after, String modelCode);

    /**
     * Record a data change log entry.
     *
     * @param record change record with all context
     */
    void recordChange(ChangeRecord record);
}
