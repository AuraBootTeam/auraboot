package com.auraboot.framework.decision.ast;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Operand data types (docs/1.md §14.6). Used for validation and to drive comparison
 * semantics (e.g. numeric vs string compare, enum/dict compares code not label).
 */
public enum DataType {
    STRING("string"),
    TEXT("text"),
    INTEGER("integer"),
    DECIMAL("decimal"),
    BOOLEAN("boolean"),
    DATE("date"),
    TIME("time"),
    DATETIME("datetime"),
    DURATION("duration"),
    ENUM("enum"),
    DICT("dict"),
    REFERENCE("reference"),
    USER("user"),
    ROLE("role"),
    GROUP("group"),
    DEPARTMENT("department"),
    COLLECTION("collection"),
    OBJECT("object");

    private final String code;

    DataType(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }

    @JsonCreator
    public static DataType fromCode(String code) {
        for (DataType d : values()) {
            if (d.code.equalsIgnoreCase(code)) {
                return d;
            }
        }
        throw new IllegalArgumentException("Unknown decision data type: " + code);
    }

    public boolean isNumeric() {
        return this == INTEGER || this == DECIMAL;
    }

    /** enum/dict/reference/user/role/group/department compare by code, not label. */
    public boolean isCodeCompared() {
        return this == ENUM || this == DICT || this == REFERENCE || this == USER
                || this == ROLE || this == GROUP || this == DEPARTMENT;
    }
}
