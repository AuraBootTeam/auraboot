package com.auraboot.framework.menu.constant;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 菜单状态枚举
 */
@Getter
public enum MenuStatus {
    ACTIVE("active", "激活"),
    INACTIVE("inactive", "未激活"),
    DRAFT("draft", "草稿"),
    HIDDEN("hidden", "隐藏");

    @EnumValue
    @JsonValue
    private final String code;
    private final String name;

    MenuStatus(String code, String name) {
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
     * 根据代码获取菜单状态
     * @param code 状态代码
     * @return 对应的菜单状态
     * @throws IllegalArgumentException 如果代码不存在
     */
    public static MenuStatus fromCode(String code) {
        for (MenuStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown menu status code: " + code);
    }

    /**
     * 检查代码是否有效
     * @param code 状态代码
     * @return 是否有效
     */
    public static boolean isValidCode(String code) {
        try {
            fromCode(code);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 检查状态是否可见（用户可以看到）
     * @return 是否可见
     */
    public boolean isVisible() {
        return this == ACTIVE || this == INACTIVE;
    }

    /**
     * 检查状态是否可用（用户可以点击）
     * @return 是否可用
     */
    public boolean isEnabled() {
        return this == ACTIVE;
    }
}