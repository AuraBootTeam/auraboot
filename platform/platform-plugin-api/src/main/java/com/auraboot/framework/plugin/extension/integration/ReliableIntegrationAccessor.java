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
}
