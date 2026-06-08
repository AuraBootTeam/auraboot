package com.auraboot.framework.decision.model;

import java.util.Set;
import java.util.Map;

/**
 * Lifecycle state of a DecisionVersion (docs/1.md §13.6). Published versions are immutable;
 * the only path forward is publishing a new version.
 *
 * <pre>
 *   DRAFT → VALIDATED → PUBLISHED → DEPRECATED → RETIRED
 *   DRAFT ← VALIDATED (return for edit)
 * </pre>
 */
public enum VersionStatus {
    DRAFT,
    VALIDATED,
    PENDING_APPROVAL,
    REJECTED,
    PUBLISHED,
    DEPRECATED,
    RETIRED;

    // VALIDATED → PUBLISHED is the default (no-approval) path; VALIDATED → PENDING_APPROVAL → PUBLISHED
    // is the opt-in 4-eyes governance path (M7); REJECTED returns to DRAFT for rework.
    private static final Map<VersionStatus, Set<VersionStatus>> ALLOWED = Map.of(
            DRAFT, Set.of(VALIDATED),
            VALIDATED, Set.of(PUBLISHED, DRAFT, PENDING_APPROVAL),
            PENDING_APPROVAL, Set.of(PUBLISHED, REJECTED),
            REJECTED, Set.of(DRAFT),
            PUBLISHED, Set.of(DEPRECATED),
            DEPRECATED, Set.of(RETIRED),
            RETIRED, Set.of()
    );

    public boolean canTransitionTo(VersionStatus target) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(target);
    }

    /** Published/deprecated versions are immutable (no content edits). */
    public boolean isImmutable() {
        return this == PUBLISHED || this == DEPRECATED || this == RETIRED;
    }

    /** Only published (and still-referenced deprecated) versions may be bound by consumers. */
    public boolean isBindable() {
        return this == PUBLISHED || this == DEPRECATED;
    }
}
