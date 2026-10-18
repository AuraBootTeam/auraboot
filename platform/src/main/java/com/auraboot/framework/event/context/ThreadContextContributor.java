package com.auraboot.framework.event.context;

/** Adds an optional domain context to platform worker scopes. */
public interface ThreadContextContributor {
    Snapshot capture();
    Snapshot empty();

    @FunctionalInterface
    interface Snapshot {
        void restore();
    }
}
