package com.auraboot.framework.plugin.extension.integration;

/**
 * Plugin-safe transactional entry point for the platform-owned reliable integration runtime.
 *
 * <p>Injected by type into plugin Spring/PF4J components. Calling {@link #enqueue} without an
 * active business transaction fails closed so an event can never commit independently of its
 * source fact.
 */
public interface ReliableIntegrationAccessor {

    void enqueue(IntegrationEventEnvelope envelope);

    /**
     * Resolve an optional integration from the current tenant's installation record, never
     * from globally loaded JARs. Only an absent installation returns false. Disabled, failed,
     * unknown states and lookup failures throw, so a selected integration cannot lose facts.
     * Must be called inside the event's transaction with the matching tenant context.
     */
    default boolean isPluginInstalled(long tenantId, String pluginId) {
        throw new UnsupportedOperationException("Tenant installation lookup is unavailable");
    }

    /** Insert once, or verify an exact immutable-envelope replay. Returns true only for a new row. */
    default boolean enqueueIdempotent(IntegrationEventEnvelope envelope) {
        throw new UnsupportedOperationException("Idempotent reliable enqueue is unavailable");
    }
}
