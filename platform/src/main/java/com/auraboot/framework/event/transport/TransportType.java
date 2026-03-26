package com.auraboot.framework.event.transport;

/**
 * Supported event transport types.
 */
public enum TransportType {

    /** In-process Spring ApplicationEvent — zero external dependencies. */
    LOCAL,

    /** Redis Streams — lightweight distributed transport. */
    REDIS,

    /** RabbitMQ / AMQP — full-featured message broker. */
    RABBITMQ
}
