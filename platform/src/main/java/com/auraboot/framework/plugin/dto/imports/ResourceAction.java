package com.auraboot.framework.plugin.dto.imports;

/**
 * Actions performed on resources during import.
 * Database values are lowercase.
 */
public enum ResourceAction {
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
    SKIP("skip");

    private final String code;

    ResourceAction(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public String getDisplayName() {
        return switch (this) {
            case CREATE -> "新增";
            case UPDATE -> "更新";
            case DELETE -> "删除";
            case SKIP -> "跳过";
        };
    }

    public static ResourceAction fromCode(String code) {
        if (code == null) return null;
        for (ResourceAction a : values()) {
            if (a.code.equalsIgnoreCase(code)) return a;
        }
        return valueOf(code.toUpperCase());
    }
}
