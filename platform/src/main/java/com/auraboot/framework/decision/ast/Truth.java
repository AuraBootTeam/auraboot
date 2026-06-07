package com.auraboot.framework.decision.ast;

/**
 * Three-valued logic for AuraBoot Decision Runtime condition evaluation.
 *
 * <p>Defined as a platform-level semantic (docs/1.md §14.7) to avoid front-end /
 * back-end / adapter drift. A missing field or a null compared with a value yields
 * {@link #UNKNOWN} rather than silently collapsing to {@code false}, so a rule never
 * fires the wrong action on incomplete data.
 *
 * <p>Match rule: only {@link #TRUE} counts as {@code matched=true}; {@link #FALSE}
 * and {@link #UNKNOWN} are {@code matched=false}, but UNKNOWN is surfaced as
 * {@code status=UNKNOWN} with unknown reasons rather than discarded.
 */
public enum Truth {
    TRUE,
    FALSE,
    UNKNOWN;

    /** Lift a Java boolean into three-valued logic. */
    public static Truth of(boolean value) {
        return value ? TRUE : FALSE;
    }

    /**
     * Three-valued AND (Kleene). Truth table (docs/1.md §14.7):
     * <pre>
     *   TRUE  AND UNKNOWN = UNKNOWN
     *   FALSE AND UNKNOWN = FALSE
     *   any   AND FALSE   = FALSE
     * </pre>
     */
    public Truth and(Truth other) {
        if (this == FALSE || other == FALSE) {
            return FALSE;
        }
        if (this == UNKNOWN || other == UNKNOWN) {
            return UNKNOWN;
        }
        return TRUE;
    }

    /**
     * Three-valued OR (Kleene). Truth table (docs/1.md §14.7):
     * <pre>
     *   TRUE  OR UNKNOWN = TRUE
     *   FALSE OR UNKNOWN = UNKNOWN
     *   any   OR TRUE    = TRUE
     * </pre>
     */
    public Truth or(Truth other) {
        if (this == TRUE || other == TRUE) {
            return TRUE;
        }
        if (this == UNKNOWN || other == UNKNOWN) {
            return UNKNOWN;
        }
        return FALSE;
    }

    /** Three-valued NOT: NOT UNKNOWN = UNKNOWN. */
    public Truth negate() {
        return switch (this) {
            case TRUE -> FALSE;
            case FALSE -> TRUE;
            case UNKNOWN -> UNKNOWN;
        };
    }

    /** Only TRUE is a match. */
    public boolean isMatch() {
        return this == TRUE;
    }
}
