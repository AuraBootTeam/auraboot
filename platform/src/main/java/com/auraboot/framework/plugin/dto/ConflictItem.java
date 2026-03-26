package com.auraboot.framework.plugin.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a single resource conflict detected during plugin import pre-check.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConflictItem {

    /**
     * Resource type: SCHEMA, PERMISSION, MENU
     */
    private String type;

    /**
     * The conflicting resource code / key
     */
    private String code;

    /**
     * Which existing plugin (or "system") owns this resource.
     * null if the resource was created manually.
     */
    private String existingPlugin;
}
