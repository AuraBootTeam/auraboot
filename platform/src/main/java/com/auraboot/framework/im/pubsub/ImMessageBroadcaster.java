package com.auraboot.framework.im.pubsub;

import com.auraboot.framework.im.dto.WsFrame;

import java.util.List;

/**
 * Abstraction over how IM messages are delivered to connected WebSocket clients.
 *
 * <p>Two implementations:
 * <ul>
 *   <li>{@link LocalBroadcaster} (default): directly pushes to locally-connected sessions,
 *       suitable for single-node deployments. No Redis required.</li>
 *   <li>{@link RedisBroadcaster}: publishes via Redis Pub/Sub so all application instances
 *       can deliver to their locally-connected sessions. Required for multi-node deployments.</li>
 * </ul>
 *
 * <p>Selected via {@code auraboot.im.broadcaster=local|redis} (default {@code local}).
 */
public interface ImMessageBroadcaster {

    /**
     * Deliver a WebSocket frame to all sessions currently associated with the given user ids.
     */
    void publish(List<Long> targetUserIds, WsFrame frame);

    /**
     * Convenience method to deliver a WebSocket frame to a single user.
     */
    default void publishToUser(Long userId, WsFrame frame) {
        publish(List.of(userId), frame);
    }
}
