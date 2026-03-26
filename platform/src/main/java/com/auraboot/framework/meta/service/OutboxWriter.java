package com.auraboot.framework.meta.service;

/**
 * Writes events to the outbox table for reliable delivery.
 * Must be called within an existing transaction to guarantee atomicity
 * with business data changes.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
public interface OutboxWriter {

    /**
     * Write event to outbox table (must be called within existing transaction).
     *
     * @param event       the domain event POJO to persist (serialized via Jackson)
     * @param commandCode source command code (nullable)
     * @param tenantId    tenant identifier
     */
    void write(Object event, String commandCode, Long tenantId);

    /**
     * Write event to outbox table with custom max retries.
     *
     * @param event       the domain event POJO to persist (serialized via Jackson)
     * @param commandCode source command code (nullable)
     * @param tenantId    tenant identifier
     * @param maxRetries  maximum delivery retry attempts
     */
    void write(Object event, String commandCode, Long tenantId, int maxRetries);
}
