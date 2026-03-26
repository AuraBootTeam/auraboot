package com.auraboot.framework.meta.entity;

import java.util.Map;
import java.util.Set;

/**
 * Named Query lifecycle status enum.
 *
 * State machine:
 *   DRAFT → TESTING → PUBLISHED → DEPRECATED → ARCHIVED
 *                  ↘               ↗
 *                   PUBLISHED → DEPRECATED
 *
 * Allowed transitions:
 *   DRAFT      → TESTING, ARCHIVED
 *   TESTING    → DRAFT, PUBLISHED, ARCHIVED
 *   PUBLISHED  → DEPRECATED
 *   DEPRECATED → PUBLISHED (re-activate), ARCHIVED
 *   ARCHIVED   → DRAFT (re-open)
 */
public enum NamedQueryStatus {

    DRAFT,
    TESTING,
    PUBLISHED,
    DEPRECATED,
    ARCHIVED;

    private static final Map<NamedQueryStatus, Set<NamedQueryStatus>> TRANSITIONS = Map.of(
            DRAFT,      Set.of(TESTING, ARCHIVED),
            TESTING,    Set.of(DRAFT, PUBLISHED, ARCHIVED),
            PUBLISHED,  Set.of(DEPRECATED),
            DEPRECATED, Set.of(PUBLISHED, ARCHIVED),
            ARCHIVED,   Set.of(DRAFT)
    );

    /**
     * Whether this status allows query execution.
     * DRAFT is sandbox-only (limited rows), TESTING and PUBLISHED allow full execution.
     */
    public boolean isExecutable() {
        return this == DRAFT || this == TESTING || this == PUBLISHED;
    }

    /**
     * Whether this status allows editing from_sql / fields.
     * PUBLISHED and DEPRECATED are frozen.
     */
    public boolean isEditable() {
        return this == DRAFT || this == TESTING;
    }

    /**
     * Whether the query definition (from_sql, fields) is frozen.
     */
    public boolean isFrozen() {
        return !isEditable();
    }

    /**
     * Whether DRAFT sandbox limit should apply (max 100 rows).
     */
    public boolean isSandbox() {
        return this == DRAFT;
    }

    /**
     * Check if transition to the target status is allowed.
     */
    public boolean canTransitionTo(NamedQueryStatus target) {
        Set<NamedQueryStatus> allowed = TRANSITIONS.get(this);
        return allowed != null && allowed.contains(target);
    }

    /**
     * Parse status string, with backward compatibility for ENABLED/DISABLED.
     */
    public static NamedQueryStatus fromString(String value) {
        if (value == null || value.isBlank()) {
            return DRAFT;
        }
        // Backward compatibility
        if ("enabled".equalsIgnoreCase(value)) return PUBLISHED;
        if ("disabled".equalsIgnoreCase(value)) return ARCHIVED;
        return valueOf(value.toUpperCase());
    }
}
