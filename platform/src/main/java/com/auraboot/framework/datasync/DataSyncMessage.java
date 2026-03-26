package com.auraboot.framework.datasync;

import java.io.Serializable;

/**
 * Message published to Redis Pub/Sub on data changes.
 * Consumed by DataSyncRedisSubscriber to push SSE events.
 */
public record DataSyncMessage(
    Long tenantId,
    String modelCode,
    String operationType,  // CREATE, UPDATE, DELETE, STATE_TRANSITION
    String recordId,       // For future record-level push
    Long userId            // For self-change suppression
) implements Serializable {}
