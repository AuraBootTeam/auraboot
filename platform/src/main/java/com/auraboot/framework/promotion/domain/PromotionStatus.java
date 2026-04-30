package com.auraboot.framework.promotion.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle states for a {@code Promotion}. Transitions are policed by
 * {@link PromotionStateMachine}.
 *
 * <pre>
 *  DRAFT      ──validate──▶  VALIDATED
 *  VALIDATED  ──edit────▶    DRAFT       (any plan change invalidates dry-run)
 *  VALIDATED  ──apply───▶    APPLIED     (terminal; #9)
 *  VALIDATED  ──reject──▶    REJECTED    (terminal; UX phase 2)
 *  *          ──fail────▶    FAILED      (during apply; #9)
 *  FAILED     ──retry───▶    DRAFT
 * </pre>
 */
public enum PromotionStatus {
    DRAFT,
    VALIDATED,
    APPLIED,
    REJECTED,
    FAILED;

    public static final Set<PromotionStatus> TERMINAL = EnumSet.of(APPLIED, REJECTED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}
