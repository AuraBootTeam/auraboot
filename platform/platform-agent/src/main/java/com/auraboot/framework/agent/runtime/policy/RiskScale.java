package com.auraboot.framework.agent.runtime.policy;

import java.util.Locale;

/**
 * Canonical, provider-neutral risk scale shared by grounding, tool metadata,
 * authorization and approval routing.
 *
 * <p>The aliases are deliberately decoded at the boundary only. Runtime and
 * audit records use {@link #code()} so a risk decision cannot change meaning
 * simply because one adapter used the legacy AuraBot names or the old R-prefix.
 */
public enum RiskScale {
    L0,
    L1,
    L2,
    L3,
    L4;

    public static final String VERSION = "risk-scale/v1";

    public String code() {
        return name();
    }

    public boolean atLeast(RiskScale other) {
        return ordinal() >= other.ordinal();
    }

    public boolean requiresConfirmation() {
        return this == L2;
    }

    public boolean requiresHumanApproval() {
        return atLeast(L3);
    }

    public static RiskScale parse(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("Risk level is required");
        }
        String normalized = String.valueOf(value).trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "L0", "R0", "LOW", "SAFE", "READ_ONLY" -> L0;
            case "L1", "R1" -> L1;
            case "L2", "R2", "MEDIUM" -> L2;
            case "L3", "R3", "HIGH" -> L3;
            case "L4", "R4", "CRITICAL", "BLOCKED" -> L4;
            default -> throw new IllegalArgumentException("Unknown risk level: " + value);
        };
    }

    public static RiskScale parseOrDefault(Object value, RiskScale fallback) {
        if (fallback == null) {
            throw new IllegalArgumentException("Fallback risk level is required");
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        try {
            return parse(value);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    public static RiskScale max(RiskScale first, RiskScale second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.ordinal() >= second.ordinal() ? first : second;
    }
}
