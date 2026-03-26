package com.auraboot.framework.event.transport;

import com.auraboot.framework.event.AuraEvent;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * In-process event transport using simple pub-sub.
 * <p>
 * This is the default for monolith deployments: zero external dependencies,
 * events are delivered synchronously in the caller's thread.
 */
@Slf4j
public class LocalTransport implements EventBusTransport {

    private final Map<String, List<Consumer<AuraEvent>>> subscriptions = new ConcurrentHashMap<>();

    @Override
    public void send(String topic, AuraEvent event) {
        log.debug("[LocalTransport] Publishing event {} to topic '{}'", event.getEventId(), topic);
        List<Consumer<AuraEvent>> consumers = subscriptions.get(topic);
        if (consumers == null || consumers.isEmpty()) {
            log.debug("[LocalTransport] No subscribers for topic '{}'", topic);
            return;
        }
        for (Consumer<AuraEvent> consumer : consumers) {
            try {
                consumer.accept(event);
            } catch (Exception e) {
                log.error("[LocalTransport] Error delivering event {} to subscriber on topic '{}': {}",
                        event.getEventId(), topic, e.getMessage(), e);
            }
        }
    }

    @Override
    public void subscribe(String topic, String group, Consumer<AuraEvent> consumer) {
        subscriptions.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(consumer);
        log.info("[LocalTransport] Subscribed to topic '{}' (group='{}')", topic, group);
    }

    @Override
    public TransportType getType() {
        return TransportType.LOCAL;
    }
}
