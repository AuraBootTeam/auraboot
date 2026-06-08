package com.auraboot.framework.eventpolicy.service;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * EventPolicy transactional outbox (docs/2.md §9): {@link #enqueue} writes a PENDING event inside the
 * caller's save transaction; {@link #processPending} (driven by a worker/scheduler — a later wiring)
 * runs each event's bound policy via run-and-execute and marks it PROCESSED/FAILED.
 */
public interface OutboxService {

    /** Enqueue an event to be processed after commit. Idempotent on (tenant, eventId). */
    void enqueue(String eventId, String eventType, String targetType, String targetKey, JsonNode context);

    /** Process up to {@code limit} PENDING events for the current tenant. Returns the count processed. */
    int processPending(int limit);
}
