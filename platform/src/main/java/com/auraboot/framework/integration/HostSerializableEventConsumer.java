package com.auraboot.framework.integration;

import com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope;

/** Host-only per-envelope opt-in; ordinary events retain the original consumer transaction. */
public interface HostSerializableEventConsumer {
    default boolean requiresSerializableTransaction(IntegrationEventEnvelope envelope) {
        return false;
    }
}
