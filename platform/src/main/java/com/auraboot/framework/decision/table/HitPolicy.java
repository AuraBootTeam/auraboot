package com.auraboot.framework.decision.table;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Decision-table hit policy (docs/1.md §15.3). FIRST/UNIQUE are the legacy policies; COLLECT and
 * PRIORITY are DMN V2 policies used by the deeper decision-table workbench.
 */
public enum HitPolicy {
    /** Take the first matching row in priority/declaration order. */
    FIRST,
    /** At most one row may match; more than one is a (validate/runtime) error. */
    UNIQUE,
    /** Return all matching rows, optionally applying a DMN collect aggregation. */
    COLLECT,
    /** Return the matching row whose output value has the highest declared output priority. */
    PRIORITY;

    @JsonValue
    public String code() {
        return name();
    }

    @JsonCreator
    public static HitPolicy fromCode(String code) {
        for (HitPolicy h : values()) {
            if (h.name().equalsIgnoreCase(code)) {
                return h;
            }
        }
        throw new IllegalArgumentException("Unsupported hitPolicy: " + code
                + " (supported: FIRST, UNIQUE, COLLECT, PRIORITY)");
    }
}
