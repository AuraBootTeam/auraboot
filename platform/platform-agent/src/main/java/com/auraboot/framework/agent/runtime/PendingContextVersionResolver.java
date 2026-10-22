package com.auraboot.framework.agent.runtime;

/**
 * Resolves record version metadata before a pending tool snapshot is stored.
 */
@FunctionalInterface
public interface PendingContextVersionResolver {

    PendingContextVersion resolve(PendingContextVersionRequest request);
}
