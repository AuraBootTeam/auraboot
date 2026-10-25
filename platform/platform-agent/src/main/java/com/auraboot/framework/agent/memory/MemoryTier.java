package com.auraboot.framework.agent.memory;

/**
 * Agent memory lifecycle tier.
 *
 * <p>Design: see {@code docs/plans/2026-04/2026-04-19-memory-l1-l2-promotion-design.md}.
 *
 * <p>Physically this tier dimension is stored on {@code ab_agent_memory.category}
 * — {@link #L1} corresponds to {@code category='session'} and {@link #L2}
 * corresponds to {@code category IN ('user','agent')}. No dedicated {@code tier}
 * column exists: duplicating the signal into two fields would violate the
 * project red-line against fallback / dual sources of truth.
 *
 * <p>The {@link #code()} lowercase form is the DB value to use in the
 * {@code ab_agent_memory_tier_event.event_type} prefix (e.g. {@code L1_PROMOTED}
 * is serialised via {@link #eventPrefix()}) and in any UI label round-trip.
 * Per project red-line "禁止魔术字符串", DB string values are lowercase and
 * callers must never persist {@link Enum#name()} (uppercase) or read via
 * {@link Enum#valueOf(Class, String)} on a raw DB value; always round-trip
 * through {@link #code()} / {@link #fromCode(String)}.
 */
public enum MemoryTier {

    /** Working memory — short-term, high churn, subject to promotion scoring. */
    L1("l1"),

    /** Long-term memory — survived promotion; bounded by decay + demotion. */
    L2("l2");

    private final String code;

    MemoryTier(String code) {
        this.code = code;
    }

    /** Lowercase database value. */
    public String code() {
        return code;
    }

    /** Uppercase prefix used in audit {@code event_type} (e.g. {@code L1_PROMOTED}). */
    public String eventPrefix() {
        return name();
    }

    /**
     * Parse from lowercase database code. Strict: case-sensitive match; unknown
     * values throw — no fallback.
     */
    public static MemoryTier fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("MemoryTier code must not be null");
        }
        for (MemoryTier t : values()) {
            if (t.code.equals(code)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown MemoryTier code: " + code);
    }

    /**
     * Map a raw {@code ab_agent_memory.category} value to its tier.
     * {@code session} -> L1; {@code user}/{@code agent} -> L2; anything else
     * throws (no fallback per project red-line).
     */
    public static MemoryTier fromCategory(String category) {
        if (category == null) {
            throw new IllegalArgumentException("category must not be null");
        }
        return switch (category) {
            case "session" -> L1;
            case "user", "agent" -> L2;
            default -> throw new IllegalArgumentException(
                    "Unknown memory category for tier mapping: " + category);
        };
    }
}
