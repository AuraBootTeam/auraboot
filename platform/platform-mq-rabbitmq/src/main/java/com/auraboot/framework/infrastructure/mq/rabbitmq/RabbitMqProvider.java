package com.auraboot.framework.infrastructure.mq.rabbitmq;

import com.auraboot.framework.infrastructure.mq.MqMessageHandler;
import com.auraboot.framework.infrastructure.mq.MqProperties;
import com.auraboot.framework.infrastructure.mq.MqProvider;
import com.rabbitmq.client.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

/**
 * RabbitMQ implementation of {@link MqProvider}.
 * <p>
 * Activated when {@code aura.mq.type=rabbitmq} is set in application properties.
 * Uses a durable topic exchange for message routing.
 * </p>
 *
 * <h3>Configuration example (application.yml):</h3>
 * <pre>
 * aura:
 *   mq:
 *     type: rabbitmq
 *     rabbitmq:
 *       host: localhost
 *       port: 5672
 *       username: guest
 *       password: guest
 *       exchange: aura-exchange
 * </pre>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "aura.mq.type", havingValue = "rabbitmq")
public class RabbitMqProvider implements MqProvider, DisposableBean {

    private final Channel channel;
    private final Connection connection;
    private final String exchange;

    /**
     * Consumer tags keyed by "topic:groupId" for tracking active subscriptions.
     */
    private final Map<String, String> consumerTags = new ConcurrentHashMap<>();

    public RabbitMqProvider(MqProperties properties) throws IOException, TimeoutException {
        MqProperties.RabbitMQ config = properties.getRabbitmq();
        this.exchange = config.getExchange();

        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(config.getHost());
        factory.setPort(config.getPort());
        factory.setUsername(config.getUsername());
        factory.setPassword(config.getPassword());

        this.connection = factory.newConnection();
        this.channel = connection.createChannel();

        // Declare a durable topic exchange
        channel.exchangeDeclare(exchange, "topic", true);

        log.info("RabbitMqProvider initialized: host={}:{}, exchange={}",
                config.getHost(), config.getPort(), exchange);
    }

    /**
     * Package-private constructor for testing with pre-built channel and connection.
     */
    RabbitMqProvider(Channel channel, Connection connection, String exchange) {
        this.channel = channel;
        this.connection = connection;
        this.exchange = exchange;
    }

    @Override
    public void send(String topic, String messageBody, Map<String, String> headers) {
        try {
            AMQP.BasicProperties.Builder propsBuilder = new AMQP.BasicProperties.Builder();
            if (headers != null && !headers.isEmpty()) {
                Map<String, Object> amqpHeaders = new HashMap<>(headers);
                propsBuilder.headers(amqpHeaders);
            }

            channel.basicPublish(
                    exchange,
                    topic,
                    propsBuilder.build(),
                    messageBody.getBytes(StandardCharsets.UTF_8)
            );
            log.debug("RabbitMQ send: exchange={}, topic={}, bodyLength={}",
                    exchange, topic, messageBody.length());
        } catch (IOException e) {
            throw new RabbitMqException("Failed to publish message to topic: " + topic, e);
        }
    }

    @Override
    public void subscribe(String topic, String groupId, MqMessageHandler handler) {
        try {
            String queueName = groupId + "." + topic;

            // Declare a durable queue
            channel.queueDeclare(queueName, true, false, false, null);

            // Bind queue to exchange with topic as routing key
            channel.queueBind(queueName, exchange, topic);

            // Start consuming with auto-ack
            String consumerTag = channel.basicConsume(queueName, true, queueName,
                    new DefaultConsumer(channel) {
                        @Override
                        public void handleDelivery(String tag, Envelope envelope,
                                                   AMQP.BasicProperties properties, byte[] body) {
                            String receivedTopic = envelope.getRoutingKey();
                            String message = new String(body, StandardCharsets.UTF_8);

                            // Convert AMQP headers to Map<String, String>
                            Map<String, String> messageHeaders = new HashMap<>();
                            if (properties.getHeaders() != null) {
                                for (Map.Entry<String, Object> entry : properties.getHeaders().entrySet()) {
                                    messageHeaders.put(entry.getKey(),
                                            entry.getValue() != null ? entry.getValue().toString() : null);
                                }
                            }

                            try {
                                handler.handle(receivedTopic, message, messageHeaders);
                            } catch (Exception e) {
                                log.error("RabbitMQ handler error: topic={}, group={}",
                                        receivedTopic, groupId, e);
                            }
                        }
                    });

            String subscriptionKey = topic + ":" + groupId;
            consumerTags.put(subscriptionKey, consumerTag);
            log.info("RabbitMQ subscribed: queue={}, exchange={}, topic={}",
                    queueName, exchange, topic);
        } catch (IOException e) {
            throw new RabbitMqException("Failed to subscribe to topic: " + topic, e);
        }
    }

    @Override
    public void unsubscribe(String topic, String groupId) {
        String subscriptionKey = topic + ":" + groupId;
        String consumerTag = consumerTags.remove(subscriptionKey);
        if (consumerTag != null) {
            try {
                channel.basicCancel(consumerTag);
                log.info("RabbitMQ unsubscribed: topic={}, group={}", topic, groupId);
            } catch (IOException e) {
                throw new RabbitMqException("Failed to unsubscribe from topic: " + topic, e);
            }
        } else {
            log.warn("RabbitMQ unsubscribe: no active subscription for topic={}, group={}",
                    topic, groupId);
        }
    }

    @Override
    public void destroy() {
        try {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
        } catch (IOException | TimeoutException e) {
            log.warn("Error closing RabbitMQ channel", e);
        }
        try {
            if (connection != null && connection.isOpen()) {
                connection.close();
            }
        } catch (IOException e) {
            log.warn("Error closing RabbitMQ connection", e);
        }
        log.info("RabbitMqProvider shut down (exchange={})", exchange);
    }

    /**
     * Runtime exception for RabbitMQ operations.
     */
    public static class RabbitMqException extends RuntimeException {
        public RabbitMqException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
