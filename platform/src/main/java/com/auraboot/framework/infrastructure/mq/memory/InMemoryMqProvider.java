package com.auraboot.framework.infrastructure.mq.memory;

import com.auraboot.framework.infrastructure.mq.MqMessageHandler;
import com.auraboot.framework.infrastructure.mq.MqProvider;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory message queue provider for development and testing.
 * Messages are delivered synchronously to subscribers in the same JVM.
 * <p>
 * Default fallback when no external MQ broker is configured.
 */
@Slf4j
public class InMemoryMqProvider implements MqProvider {

    private final Map<String, List<SubscriberEntry>> subscribers = new ConcurrentHashMap<>();

    public InMemoryMqProvider() {
        log.warn("InMemoryMqProvider initialized — FOR DEVELOPMENT/TEST ONLY. "
                + "Messages are NOT persisted and delivered synchronously. "
                + "Configure Kafka or RabbitMQ for production use.");
    }

    @Override
    public void send(String topic, String messageBody, Map<String, String> headers) {
        log.debug("InMemory MQ send: topic={}, bodyLength={}", topic, messageBody.length());
        List<SubscriberEntry> entries = subscribers.get(topic);
        if (entries != null) {
            for (SubscriberEntry entry : entries) {
                try {
                    entry.handler.handle(topic, messageBody, headers);
                } catch (Exception e) {
                    log.error("InMemory MQ handler error: topic={}, group={}", topic, entry.groupId, e);
                }
            }
        }
    }

    @Override
    public void subscribe(String topic, String groupId, MqMessageHandler handler) {
        subscribers.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>())
                .add(new SubscriberEntry(groupId, handler));
        log.info("InMemory MQ subscribed: topic={}, group={}", topic, groupId);
    }

    @Override
    public void unsubscribe(String topic, String groupId) {
        List<SubscriberEntry> entries = subscribers.get(topic);
        if (entries != null) {
            entries.removeIf(e -> e.groupId.equals(groupId));
            log.info("InMemory MQ unsubscribed: topic={}, group={}", topic, groupId);
        }
    }

    private record SubscriberEntry(String groupId, MqMessageHandler handler) {}
}
