package com.auraboot.framework.infrastructure.mq.rabbitmq;

import com.rabbitmq.client.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RabbitMqProvider}.
 * Uses Mockito to mock RabbitMQ Channel and Connection — no real broker required.
 */
@ExtendWith(MockitoExtension.class)
class RabbitMqProviderTest {

    private static final String EXCHANGE = "test-exchange";
    private static final String TOPIC = "order.created";
    private static final String GROUP_ID = "inventory-service";
    private static final String MESSAGE_BODY = "{\"orderId\":\"ORD-001\",\"amount\":99.99}";

    @Mock
    private Channel channel;

    @Mock
    private Connection connection;

    private RabbitMqProvider provider;

    @BeforeEach
    void setUp() {
        provider = new RabbitMqProvider(channel, connection, EXCHANGE);
    }

    @Test
    @DisplayName("send() publishes message with correct exchange, routing key, and body")
    void sendPublishesMessageCorrectly() throws IOException {
        provider.send(TOPIC, MESSAGE_BODY, Map.of());

        ArgumentCaptor<String> exchangeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> routingKeyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);

        verify(channel).basicPublish(
                exchangeCaptor.capture(),
                routingKeyCaptor.capture(),
                any(AMQP.BasicProperties.class),
                bodyCaptor.capture()
        );

        assertEquals(EXCHANGE, exchangeCaptor.getValue());
        assertEquals(TOPIC, routingKeyCaptor.getValue());
        assertEquals(MESSAGE_BODY, new String(bodyCaptor.getValue(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("send() with headers sets AMQP headers in BasicProperties")
    void sendWithHeadersSetsAmqpHeaders() throws IOException {
        Map<String, String> headers = Map.of(
                "traceId", "abc-123",
                "source", "web-api"
        );

        provider.send(TOPIC, MESSAGE_BODY, headers);

        ArgumentCaptor<AMQP.BasicProperties> propsCaptor =
                ArgumentCaptor.forClass(AMQP.BasicProperties.class);

        verify(channel).basicPublish(
                eq(EXCHANGE),
                eq(TOPIC),
                propsCaptor.capture(),
                any(byte[].class)
        );

        AMQP.BasicProperties props = propsCaptor.getValue();
        assertNotNull(props.getHeaders());
        assertEquals("abc-123", props.getHeaders().get("traceId"));
        assertEquals("web-api", props.getHeaders().get("source"));
    }

    @Test
    @DisplayName("subscribe() declares queue, binds to exchange, and starts consuming")
    void subscribeDeclaresQueueAndConsumes() throws IOException {
        String expectedQueue = GROUP_ID + "." + TOPIC;
        String fakeConsumerTag = "amq.ctag-test-001";

        when(channel.basicConsume(eq(expectedQueue), eq(true), eq(expectedQueue), any(Consumer.class)))
                .thenReturn(fakeConsumerTag);

        provider.subscribe(TOPIC, GROUP_ID, (t, m, h) -> {});

        // Verify queue declared (durable, non-exclusive, non-autoDelete)
        verify(channel).queueDeclare(expectedQueue, true, false, false, null);

        // Verify queue bound to exchange with topic routing key
        verify(channel).queueBind(expectedQueue, EXCHANGE, TOPIC);

        // Verify consuming started
        verify(channel).basicConsume(eq(expectedQueue), eq(true), eq(expectedQueue), any(Consumer.class));
    }

    @Test
    @DisplayName("unsubscribe() cancels consumer with correct consumer tag")
    void unsubscribeCancelsConsumer() throws IOException {
        String expectedQueue = GROUP_ID + "." + TOPIC;
        String fakeConsumerTag = "amq.ctag-test-002";

        when(channel.basicConsume(eq(expectedQueue), eq(true), eq(expectedQueue), any(Consumer.class)))
                .thenReturn(fakeConsumerTag);

        // Subscribe first to register consumer tag
        provider.subscribe(TOPIC, GROUP_ID, (t, m, h) -> {});

        // Then unsubscribe
        provider.unsubscribe(TOPIC, GROUP_ID);

        verify(channel).basicCancel(fakeConsumerTag);
    }

    @Test
    @DisplayName("destroy() closes channel and connection")
    void destroyClosesResources() throws IOException, TimeoutException {
        when(channel.isOpen()).thenReturn(true);
        when(connection.isOpen()).thenReturn(true);

        provider.destroy();

        verify(channel).close();
        verify(connection).close();
    }

    @Test
    @DisplayName("Queue name follows format: groupId.topic")
    void queueNamingFormat() throws IOException {
        String topic = "shipment.dispatched";
        String groupId = "notification-svc";
        String expectedQueue = "notification-svc.shipment.dispatched";
        String fakeConsumerTag = "amq.ctag-test-003";

        when(channel.basicConsume(eq(expectedQueue), eq(true), eq(expectedQueue), any(Consumer.class)))
                .thenReturn(fakeConsumerTag);

        provider.subscribe(topic, groupId, (t, m, h) -> {});

        // Verify queue name is groupId.topic
        verify(channel).queueDeclare(expectedQueue, true, false, false, null);
        verify(channel).queueBind(expectedQueue, EXCHANGE, topic);
    }
}
