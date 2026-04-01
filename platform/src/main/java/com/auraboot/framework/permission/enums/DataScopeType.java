package com.auraboot.framework.permission.enums;

/**
 * Data scope types with priority for multi-role merge.
 *
 * <p>Higher priority = more permissive access.
 */
public enum DataScopeType {

    NONE("none", 1),
    SELF("self", 2),
    DEPT("dept", 3),
    DEPT_AND_SUB("dept_and_sub", 4),
    ALL("all", 5);

    private final String code;
    private final int priority;

    DataScopeType(String code, int priority) {
        this.code = code;
        this.priority = priority;
    }

    public String code() {
        return code;
    }

    public int priority() {
        return priority;
    }

    /**
     * Parse from database value (lowercase).
     *
     * @param code the scope type code from DB
     * @return the matching enum, or ALL if unknown
     */
    public static DataScopeType fromCode(String code) {
        if (code == null) {
            return ALL;
        }
        for (DataScopeType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return ALL;
    }
}
