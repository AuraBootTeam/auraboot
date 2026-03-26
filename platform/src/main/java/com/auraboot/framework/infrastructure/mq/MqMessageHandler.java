package com.auraboot.framework.infrastructure.mq;

import java.util.Map;

/**
 * Callback interface for receiving messages from a message queue.
 */
@FunctionalInterface
public interface MqMessageHandler {

    /**
     * Handle an incoming message.
     *
     * @param topic       the topic/queue the message was received from
     * @param messageBody the message payload
     * @param headers     message headers / properties
     */
    void handle(String topic, String messageBody, Map<String, String> headers);
}
