package com.auraboot.framework.decision.model;

/** How a consumer binds to a decision version (docs/1.md §13.7). */
public enum VersionBinding {
    /** Always the latest published version (low-risk automation / config). */
    LATEST,
    /** A pinned version (SLA records, critical approval rules). */
    FIXED_VERSION,
    /** A version tag (grey release / environment sync). */
    VERSION_TAG,
    /** The version deployed with a process definition (BPM). */
    DEPLOYMENT_VERSION,
    /** Selected by effective time (policy / SLA effective date). */
    EFFECTIVE_TIME,
    /** Selected by the originating event time (event replay / audit). */
    AS_OF_EVENT_TIME
}
