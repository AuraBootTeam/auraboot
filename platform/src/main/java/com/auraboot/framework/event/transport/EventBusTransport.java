package com.auraboot.framework.event.transport;

import com.auraboot.framework.event.AuraEvent;

import java.util.function.Consumer;

/**
 * Abstraction for event transport layer.
 * <p>
 * Allows switching between local (in-process), Redis Streams, and RabbitMQ
 * transports without changing business code.
 */
public interface EventBusTransport {

    /**
     * Send an event to the given topic/channel.
     *
     * @param topic logical topic name (e.g. "user.registered")
     * @param event the event payload
     */
    void send(String topic, AuraEvent event);

    /**
     * Subscribe to a topic. The consumer is invoked for every incoming event.
     *
     * @param topic    logical topic name
     * @param group    consumer group (for competing-consumer semantics)
     * @param consumer callback
     */
    void subscribe(String topic, String group, Consumer<AuraEvent> consumer);

    /**
     * Return the transport type identifier.
     */
    TransportType getType();

    /**
     * Lifecycle hook — called on application shutdown.
     */
    default void shutdown() {
        // no-op by default
    }
}
