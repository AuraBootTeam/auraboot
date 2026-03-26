package com.auraboot.framework.infrastructure.mq.redis;

import com.auraboot.framework.infrastructure.mq.MqMessageHandler;
import com.auraboot.framework.infrastructure.mq.MqProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamMessageListenerContainerOptions;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis Streams implementation of {@link MqProvider}.
 * <p>
 * Uses Redis Streams (XADD/XREADGROUP/XACK) for persistent message delivery
 * with consumer groups. Since Redis is already a core dependency, this provider
 * requires no additional infrastructure.
 * <p>
 * Activated when {@code aura.mq.type=redis} is set.
 *
 * <h3>Redis Streams mapping:</h3>
 * <ul>
 *   <li>topic → Redis Stream key (prefixed: {@code aura:mq:{topic}})</li>
 *   <li>groupId → Consumer Group</li>
 *   <li>message → Stream entry with {@code body} + {@code headers} fields</li>
 * </ul>
 */
@Slf4j
public class RedisMqProvider implements MqProvider, DisposableBean {

    private static final String STREAM_PREFIX = "aura:mq:";
    private static final String FIELD_BODY = "_body";
    private static final String FIELD_HEADER_PREFIX = "h:";
    private static final String CONSUMER_NAME = "aura-worker";

    private final StringRedisTemplate redis;
    private final Map<String, StreamMessageListenerContainer<String, MapRecord<String, String, String>>> containers
            = new ConcurrentHashMap<>();

    public RedisMqProvider(StringRedisTemplate redis) {
        this.redis = redis;
        log.info("RedisMqProvider initialized — using Redis Streams for message delivery");
    }

    @Override
    public void send(String topic, String messageBody, Map<String, String> headers) {
        String streamKey = streamKey(topic);

        // Build stream entry: _body + h:key1, h:key2, ...
        Map<String, String> fields = new HashMap<>();
        fields.put(FIELD_BODY, messageBody);
        if (headers != null) {
            headers.forEach((k, v) -> fields.put(FIELD_HEADER_PREFIX + k, v));
        }

        RecordId recordId = redis.opsForStream().add(StreamRecords.string(fields).withStreamKey(streamKey));
        log.debug("Redis MQ sent: stream={}, id={}, bodyLength={}", streamKey, recordId, messageBody.length());
    }

    @Override
    public void subscribe(String topic, String groupId, MqMessageHandler handler) {
        String streamKey = streamKey(topic);
        String containerKey = topic + ":" + groupId;

        // Ensure stream and consumer group exist
        ensureStreamAndGroup(streamKey, groupId);

        // Create listener container
        var options = StreamMessageListenerContainerOptions
                .<String, MapRecord<String, String, String>>builder()
                .pollTimeout(Duration.ofSeconds(2))
                .batchSize(10)
                .build();

        StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
                StreamMessageListenerContainer.create(redis.getConnectionFactory(), options);

        // Register listener with consumer group
        container.receive(
                Consumer.from(groupId, CONSUMER_NAME),
                StreamOffset.create(streamKey, ReadOffset.lastConsumed()),
                message -> {
                    try {
                        Map<String, String> value = message.getValue();
                        String body = value.getOrDefault(FIELD_BODY, "");

                        // Extract headers
                        Map<String, String> headers = new HashMap<>();
                        value.forEach((k, v) -> {
                            if (k.startsWith(FIELD_HEADER_PREFIX)) {
                                headers.put(k.substring(FIELD_HEADER_PREFIX.length()), v);
                            }
                        });

                        handler.handle(topic, body, headers);

                        // ACK after successful processing
                        redis.opsForStream().acknowledge(streamKey, groupId, message.getId());
                    } catch (Exception e) {
                        log.error("Redis MQ handler error: stream={}, group={}, id={}",
                                streamKey, groupId, message.getId(), e);
                        // Message stays pending — will be re-delivered on next read
                    }
                });

        container.start();
        containers.put(containerKey, container);
        log.info("Redis MQ subscribed: stream={}, group={}", streamKey, groupId);
    }

    @Override
    public void unsubscribe(String topic, String groupId) {
        String containerKey = topic + ":" + groupId;
        var container = containers.remove(containerKey);
        if (container != null) {
            container.stop();
            log.info("Redis MQ unsubscribed: topic={}, group={}", topic, groupId);
        }
    }

    @Override
    public void destroy() {
        log.info("Shutting down RedisMqProvider — stopping {} listener containers", containers.size());
        containers.values().forEach(c -> {
            try {
                c.stop();
            } catch (Exception e) {
                log.warn("Error stopping Redis Stream listener: {}", e.getMessage());
            }
        });
        containers.clear();
    }

    /**
     * Ensure the stream and consumer group exist.
     * XGROUP CREATE creates the stream implicitly with MKSTREAM.
     */
    private void ensureStreamAndGroup(String streamKey, String groupId) {
        try {
            redis.opsForStream().createGroup(streamKey, ReadOffset.from("0"), groupId);
            log.debug("Created consumer group: stream={}, group={}", streamKey, groupId);
        } catch (Exception e) {
            // Group already exists — this is expected on reconnect
            if (e.getMessage() != null && e.getMessage().contains("busygroup")) {
                log.debug("Consumer group already exists: stream={}, group={}", streamKey, groupId);
            } else {
                log.warn("Failed to create consumer group: stream={}, group={}: {}",
                        streamKey, groupId, e.getMessage());
            }
        }
    }

    private String streamKey(String topic) {
        return STREAM_PREFIX + topic;
    }
}
