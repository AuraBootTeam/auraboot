package com.auraboot.framework.infrastructure.mq;

import java.util.Map;

/**
 * SPI for pluggable message queue backends.
 * Implementations are activated by configuration (e.g. {@code aura.mq.type=kafka}).
 */
public interface MqProvider {

    /**
     * Send a message to a topic.
     *
     * @param topic       destination topic/queue
     * @param messageBody serialized message payload
     * @param headers     message headers/properties (may be empty)
     */
    void send(String topic, String messageBody, Map<String, String> headers);

    /**
     * Subscribe to a topic.
     *
     * @param topic   topic/queue to subscribe to
     * @param groupId consumer group identifier
     * @param handler callback for incoming messages
     */
    void subscribe(String topic, String groupId, MqMessageHandler handler);

    /**
     * Unsubscribe from a topic.
     *
     * @param topic   topic/queue to unsubscribe from
     * @param groupId consumer group identifier
     */
    void unsubscribe(String topic, String groupId);
}
