package com.auraboot.framework.bpm.model;

import java.util.Arrays;

/** Who can initiate a CC on a process task. All DB values are lowercase. */
public enum CcPolicy {
    INITIATOR("initiator"),
    ASSIGNEE("assignee"),
    ALL("all");

    private final String code;
    CcPolicy(String code) { this.code = code; }
    public String code() { return code; }

    public static CcPolicy fromCode(String code) {
        if (code == null || code.isBlank()) return ALL;
        return Arrays.stream(values())
                .filter(p -> p.code.equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown CcPolicy: " + code));
    }
}
