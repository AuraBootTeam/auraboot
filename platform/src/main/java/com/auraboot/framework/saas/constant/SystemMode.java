package com.auraboot.framework.saas.constant;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum SystemMode {
    SINGLE("single", "Single tenant mode"),
    MULTI("multi", "Multi tenant mode"),
    HYBRID("hybrid", "Hybrid mode");

    @EnumValue
    @JsonValue
    private final String code;
    private final String desc;

    public static SystemMode fromCode(String code) {
        for (SystemMode mode : values()) {
            if (mode.code.equals(code)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown system mode: " + code);
    }
}
