package com.auraboot.framework.meta.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a single field-level change between two record states.
 *
 * @since 5.1.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FieldChange {

    private String fieldCode;
    private String fieldLabel;
    private Object oldValue;
    private Object newValue;
}
