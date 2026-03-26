package com.auraboot.framework.meta.constant;

/**
 * Version Status Enum
 * 
 * Unified status definition for all versioned entities (Model, Field, Dict, PageSchema, etc.)
 * 
 * Status Transition Flow:
 * DRAFT → PUBLISHED → DEPRECATED → ARCHIVED
 *                  ↓
 *              DISABLED (can be disabled from any status)
 */
public enum Status {
    /**
     * Draft - Being edited, not published
     * - Can be edited and deleted
     * - Not available for external use
     * - Not counted in version history
     */
    DRAFT("draft", "草稿"),
    
    /**
     * Published - Current active version
     * - Available for external use
     * - Cannot be directly modified (need to create new version)
     * - Only one PUBLISHED version per code in same tenant
     * - isCurrent = true
     */
    PUBLISHED("published", "已发布"),
    
    /**
     * Deprecated - Marked as deprecated but still accessible
     * - Not recommended but maintains compatibility
     * - Read-only, cannot be modified
     * - Can be queried and referenced
     */
    DEPRECATED("deprecated", "已废弃"),
    
    /**
     * Archived - Historical version
     * - Read-only, cannot be modified
     * - Only for audit and retrospection
     * - Not involved in business logic
     */
    ARCHIVED("archived", "已归档"),
    
    /**
     * Disabled - Temporarily disabled, not available
     * - Can be transitioned from any status
     * - Can be re-enabled (restore to previous status)
     * - Used for emergency offline or maintenance
     */
    DISABLED("disabled", "已禁用");
    
    private final String code;
    private final String name;
    
    Status(String code, String name) {
        this.code = code;
        this.name = name;
    }
    
    public String getCode() {
        return code;
    }
    
    public String getName() {
        return name;
    }
    
    /**
     * Check if status is active (can be used in business logic)
     */
    public boolean isActive() {
        return this == PUBLISHED || this == DEPRECATED;
    }
    
    /**
     * Check if status is editable
     */
    public boolean isEditable() {
        return this == DRAFT;
    }
    
    /**
     * Check if status is readonly
     */
    public boolean isReadonly() {
        return this == DEPRECATED || this == ARCHIVED;
    }
    
    /**
     * Check if status transition is allowed
     */
    public boolean canTransitionTo(Status target) {
        if (target == null) {
            return false;
        }
        
        // Can always disable from any status
        if (target == DISABLED) {
            return true;
        }
        
        // Can restore from disabled to any status
        if (this == DISABLED) {
            return true;
        }
        
        return switch (this) {
            case DRAFT -> target == PUBLISHED;
            case PUBLISHED -> target == DEPRECATED || target == ARCHIVED;
            case DEPRECATED -> target == ARCHIVED;
            case ARCHIVED -> target == PUBLISHED; // Support rollback
            default -> false;
        };
    }
    
    /**
     * Get allowed transition targets
     */
    public Status[] getAllowedTransitions() {
        return switch (this) {
            case DRAFT -> new Status[]{PUBLISHED, DISABLED};
            case PUBLISHED -> new Status[]{DEPRECATED, ARCHIVED, DISABLED};
            case DEPRECATED -> new Status[]{ARCHIVED, DISABLED};
            case ARCHIVED -> new Status[]{PUBLISHED, DISABLED}; // Support rollback
            case DISABLED -> Status.values(); // Can restore to any status
        };
    }
}