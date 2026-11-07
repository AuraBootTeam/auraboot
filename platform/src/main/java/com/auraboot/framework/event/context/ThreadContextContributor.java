package com.auraboot.framework.event.context;

/** Adds an optional domain context to platform worker scopes. */
public interface ThreadContextContributor {
    Snapshot capture();
    Snapshot empty();

    /** A retained domain principal must stay bound to the identity rehydrated by a host handoff. */
    default void requireActor(Long tenantId, Long userId, Long memberId) {}

    @FunctionalInterface
    interface Snapshot {
        void restore();
    }
}
