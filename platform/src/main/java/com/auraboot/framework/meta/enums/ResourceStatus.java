package com.auraboot.framework.meta.enums;

/**
 * 资源状态枚举
 *
 * 适用于Model、Field、Dict、Page等运行时资源
 *
 * @author AuraBoot Framework
 * @since 3.3.0
 */
public enum ResourceStatus {
    /**
     * 草稿 - 未发布的资源
     */
    DRAFT("draft", "草稿"),

    //todo use Status enum ?
    /**
     * 已发布 - 正式生效的资源
     */
    ENABLED("enabled", "已发布"),

    /**
     * 已禁用 - 不再使用的资源
     */
    DISABLED("disabled", "已禁用");

    private final String code;
    private final String description;

    ResourceStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 从代码获取枚举值
     */
    public static ResourceStatus fromCode(String code) {
        for (ResourceStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown resource status: " + code);
    }

    /**
     * 判断是否为可用状态
     */
    public boolean isAvailable() {
        return this == ENABLED;
    }
}
