package com.auraboot.framework.agent.memory.audit;

/**
 * Conflict taxonomy for Spike-2 prompt-segment annotation. Mirrors
 * {@code platform/src/test/resources/memory-audit/annotation.schema.json}.
 *
 * <p>See {@code docs/backlog/2026-05-27-spike-2-memory-prompt-audit-design.md} §2.2.
 */
public enum ConflictTag {
    NO_CONFLICT("no-conflict"),
    TEMPORAL_CONFLICT("temporal-conflict"),
    FACTUAL_CONFLICT("factual-conflict"),
    GRANULARITY_CONFLICT("granularity-conflict"),
    UNCLEAR("unclear");

    private final String wire;

    ConflictTag(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /**
     * @return true iff this tag represents an actual conflict (not no-conflict, not unclear).
     *         Used to compute "矛盾召回率" (conflict-recall rate).
     */
    public boolean isConflict() {
        return this == TEMPORAL_CONFLICT
                || this == FACTUAL_CONFLICT
                || this == GRANULARITY_CONFLICT;
    }

    public static ConflictTag fromWire(String wire) {
        for (ConflictTag t : values()) {
            if (t.wire.equals(wire)) return t;
        }
        throw new IllegalArgumentException("unknown conflict tag wire form: " + wire);
    }
}
