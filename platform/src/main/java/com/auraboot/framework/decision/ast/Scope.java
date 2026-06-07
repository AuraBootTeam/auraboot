package com.auraboot.framework.decision.ast;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Whitelisted DecisionContext scopes (docs/1.md §11, §14.2). A path operand must name
 * one of these scopes explicitly so {@code amount} is never ambiguous about its source.
 */
public enum Scope {
    META("meta"),
    EVENT("event"),
    RECORD("record"),
    BEFORE("before"),
    AFTER("after"),
    PROCESS("process"),
    TASK("task"),
    SLA("sla"),
    ACTOR("actor"),
    TENANT("tenant"),
    TIME("time"),
    ENV("env");

    private final String code;

    Scope(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }

    @JsonCreator
    public static Scope fromCode(String code) {
        for (Scope s : values()) {
            if (s.code.equalsIgnoreCase(code)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown decision context scope: " + code);
    }
}
