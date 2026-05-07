package com.auraboot.framework.aurabot.skill;

import java.util.Locale;

/**
 * Risk level for an {@link AuraBotSkill}.
 *
 * <p>Persisted via {@link #code()} (lowercase) and re-hydrated via
 * {@link #fromCode(String)}. Direct {@code name()} storage is forbidden by
 * project code-quality red-line: only enum.code() values may live in the DB.
 */
public enum RiskLevel {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    CRITICAL("critical");

    private final String code;

    RiskLevel(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /**
     * Resolve a stored {@code code} back to its enum constant.
     *
     * @throws IllegalArgumentException when {@code code} is null/blank or unknown.
     */
    public static RiskLevel fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("RiskLevel code must not be blank");
        }
        String norm = code.trim().toLowerCase(Locale.ROOT);
        for (RiskLevel rl : values()) {
            if (rl.code.equals(norm) || rl.name().toLowerCase(Locale.ROOT).equals(norm)) {
                return rl;
            }
        }
        throw new IllegalArgumentException("Unknown RiskLevel code: " + code);
    }

    /**
     * @return whether this risk level is at least {@code other}.
     */
    public boolean atLeast(RiskLevel other) {
        return this.ordinal() >= other.ordinal();
    }
}
