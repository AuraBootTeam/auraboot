package com.auraboot.framework.meta.dto;

/**
 * Modification type enum
 * Classifies the type of field modification
 */
public enum ModificationType {
    /**
     * Breaking change - will break existing functionality
     */
    BREAKING,
    
    /**
     * Warning - may cause issues but not guaranteed to break
     */
    WARNING,
    
    /**
     * Safe - no impact on existing functionality
     */
    SAFE
}
