package com.auraboot.framework.plugin.dto.uninstall;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a difference between original and current value of a field.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResourceDiff {

    /**
     * Field name that was modified.
     */
    private String field;

    /**
     * Original value at import time.
     */
    private Object original;

    /**
     * Current value in the database.
     */
    private Object current;

    /**
     * Human-readable description of the change.
     */
    private String description;
}
