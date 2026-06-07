package com.auraboot.framework.eventpolicy.model;

/** How many rules in a policy may match (docs/2.md §5). */
public enum MatchMode {
    /** Stop after the first matching rule (BPM routing / mutually-exclusive). */
    FIRST_MATCH,
    /** Collect every matching rule (form submit → multiple business actions). */
    COLLECT_ALL,
    /** At most one may match; more than one is an error (exclusive classification). */
    UNIQUE,
    /** Multiple may match but take only the highest-priority one. */
    PRIORITY_FIRST
}
