package com.auraboot.framework.bpm.model;

import java.util.Arrays;

/** Who/when a process instance can be withdrawn. All DB values are lowercase. */
public enum WithdrawPolicy {
    STRICT("strict"),   // Initiator only, before any approve
    LOOSE("loose"),     // Initiator only, anytime while running
    NONE("none");       // Disabled

    private final String code;
    WithdrawPolicy(String code) { this.code = code; }
    public String code() { return code; }

    public static WithdrawPolicy fromCode(String code) {
        if (code == null || code.isBlank()) return STRICT;
        return Arrays.stream(values())
                .filter(p -> p.code.equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown WithdrawPolicy: " + code));
    }
}
