package com.auraboot.framework.promotion.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Pure-logic guard over {@link PromotionStatus} transitions. No persistence, no Spring —
 * trivially unit-testable. Service layer calls {@link #assertCanTransition} before each write.
 *
 * <p>Transition table:
 * <pre>
 *  from        | allowed targets
 *  ────────────┼─────────────────────────────
 *  DRAFT       | VALIDATED
 *  VALIDATED   | DRAFT (re-edit invalidates dry-run), APPLIED (#9), REJECTED (UX phase 2)
 *  APPLIED     | (terminal)
 *  REJECTED    | (terminal)
 *  FAILED      | DRAFT (retry)
 * </pre>
 */
public final class PromotionStateMachine {

    private static final Map<PromotionStatus, Set<PromotionStatus>> ALLOWED = new EnumMap<>(PromotionStatus.class);

    static {
        ALLOWED.put(PromotionStatus.DRAFT,     EnumSet.of(PromotionStatus.VALIDATED));
        ALLOWED.put(PromotionStatus.VALIDATED, EnumSet.of(PromotionStatus.DRAFT, PromotionStatus.APPLIED, PromotionStatus.REJECTED, PromotionStatus.FAILED));
        ALLOWED.put(PromotionStatus.APPLIED,   EnumSet.noneOf(PromotionStatus.class));
        ALLOWED.put(PromotionStatus.REJECTED,  EnumSet.noneOf(PromotionStatus.class));
        ALLOWED.put(PromotionStatus.FAILED,    EnumSet.of(PromotionStatus.DRAFT));
    }

    private PromotionStateMachine() {
    }

    /**
     * @return true if the transition is permitted; never throws.
     */
    public static boolean canTransition(PromotionStatus from, PromotionStatus to) {
        if (from == null || to == null) {
            return false;
        }
        if (from == to) {
            // Idempotent re-validation on VALIDATED is allowed; everything else self-loop is a no-op.
            return from == PromotionStatus.VALIDATED;
        }
        return ALLOWED.getOrDefault(from, EnumSet.noneOf(PromotionStatus.class)).contains(to);
    }

    /**
     * @throws IllegalStateException with a precise message naming the bad transition.
     */
    public static void assertCanTransition(PromotionStatus from, PromotionStatus to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateException(
                    "Illegal promotion state transition: " + from + " → " + to
                            + ". Allowed from " + from + ": " + ALLOWED.getOrDefault(from, EnumSet.noneOf(PromotionStatus.class)));
        }
    }
}
