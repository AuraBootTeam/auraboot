package com.auraboot.framework.bpm.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

@Getter
public enum SaveStrategy {
    BUSINESS_ONLY("business_only"),
    DUAL_WRITE("dual_write"),
    VARIABLE_ONLY("variable_only");

    @JsonValue
    private final String code;

    SaveStrategy(String code) {
        this.code = code;
    }

    public static SaveStrategy fromCode(String code) {
        if (code == null) return BUSINESS_ONLY;
        for (SaveStrategy s : values()) {
            if (s.code.equals(code)) return s;
        }
        return BUSINESS_ONLY;
    }
}
