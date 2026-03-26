package com.auraboot.framework.saas.constant;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum BootstrapStatus {
    PENDING("pending", "Pending execution"),
    RUNNING("running", "Bootstrap in progress"),
    COMPLETED("completed", "Bootstrap completed"),
    FAILED("failed", "Bootstrap failed");

    @EnumValue
    @JsonValue
    private final String code;
    private final String desc;

    public static BootstrapStatus fromCode(String code) {
        for (BootstrapStatus s : values()) {
            if (s.code.equals(code)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown bootstrap status: " + code);
    }
}
