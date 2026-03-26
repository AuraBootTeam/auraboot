package com.auraboot.framework.event.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.auraboot.framework.event.AuraEvent;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Consumer;

/**
 * RabbitMQ (AMQP) event transport.
 * <p>
 * Uses topic exchange with routing keys derived from the event topic.
 * Requires spring-boot-starter-amqp on the classpath.
 * <p>
 * <b>Note:</b> This implementation is a structural placeholder. Full AMQP
 * wiring (exchange/queue declaration, MessageListenerContainer setup) will
 * be completed when RabbitMQ is adopted in production.
 */
@Slf4j
public class RabbitMqTransport implements EventBusTransport {

    private static final String EXCHANGE_NAME = "aura.events";

    private final Object /* AmqpTemplate */ amqpTemplate;
    private final ObjectMapper objectMapper;

    /**
     * @param amqpTemplate injected Spring AmqpTemplate (typed as Object to
     *                     avoid hard compile-time dependency on spring-amqp)
     * @param objectMapper Jackson mapper
     */
    public RabbitMqTransport(Object amqpTemplate, ObjectMapper objectMapper) {
        this.amqpTemplate = amqpTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void send(String topic, AuraEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            // amqpTemplate.convertAndSend(EXCHANGE_NAME, routingKey(topic), payload);
            log.info("[RabbitMqTransport] (stub) Would send event {} to exchange='{}' routingKey='{}'",
                    event.getEventId(), EXCHANGE_NAME, routingKey(topic));
            log.debug("[RabbitMqTransport] Payload: {}", payload);
        } catch (JsonProcessingException e) {
            log.error("[RabbitMqTransport] Failed to serialize event {}: {}", event.getEventId(), e.getMessage(), e);
        }
    }

    @Override
    public void subscribe(String topic, String group, Consumer<AuraEvent> consumer) {
        log.info("[RabbitMqTransport] (stub) Would subscribe to exchange='{}' routingKey='{}' queue='{}'",
                EXCHANGE_NAME, routingKey(topic), group);
        // Full implementation:
        // 1. Declare topic exchange EXCHANGE_NAME
        // 2. Declare queue = group, bind with routingKey
        // 3. Create SimpleMessageListenerContainer → deserialize → consumer.accept()
    }

    @Override
    public TransportType getType() {
        return TransportType.RABBITMQ;
    }

    private String routingKey(String topic) {
        return topic.replace("/", ".");
    }
}
