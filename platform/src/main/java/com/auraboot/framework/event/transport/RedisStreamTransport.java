package com.auraboot.framework.event.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.auraboot.framework.event.AuraEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Redis Streams-based event transport.
 * <p>
 * Uses XADD/XREADGROUP for durable, competing-consumer delivery.
 * Requires Spring Data Redis on the classpath and a running Redis instance.
 */
@Slf4j
public class RedisStreamTransport implements EventBusTransport {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ExecutorService listenerPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "redis-stream-listener");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(true);

    public RedisStreamTransport(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void send(String topic, AuraEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            StringRecord record = StringRecord.of(
                    Collections.singletonMap("payload", payload)
            ).withStreamKey(streamKey(topic));
            redisTemplate.opsForStream().add(record);
            log.debug("[RedisStreamTransport] Sent event {} to stream '{}'", event.getEventId(), topic);
        } catch (JsonProcessingException e) {
            log.error("[RedisStreamTransport] Failed to serialize event {}: {}", event.getEventId(), e.getMessage(), e);
        }
    }

    @Override
    public void subscribe(String topic, String group, Consumer<AuraEvent> consumer) {
        String stream = streamKey(topic);
        ensureConsumerGroup(stream, group);

        // consumer name = group + thread-id (unique per instance)
        String consumerName = group + "-" + Thread.currentThread().getId();

        listenerPool.submit(() -> {
            log.info("[RedisStreamTransport] Listener started for stream='{}' group='{}' consumer='{}'",
                    stream, group, consumerName);
            while (running.get()) {
                try {
                    @SuppressWarnings("unchecked")
                    List<MapRecord<String, Object, Object>> records =
                            redisTemplate.opsForStream().read(
                                    org.springframework.data.redis.connection.stream.Consumer.from(group, consumerName),
                                    StreamReadOptions.empty().count(10).block(Duration.ofSeconds(2)),
                                    StreamOffset.create(stream, ReadOffset.lastConsumed())
                            );
                    if (records != null) {
                        for (MapRecord<String, Object, Object> record : records) {
                            try {
                                Object payloadObj = record.getValue().get("payload");
                                String payload = payloadObj != null ? payloadObj.toString() : null;
                                if (payload != null) {
                                    AuraEvent event = objectMapper.readValue(payload, AuraEvent.class);
                                    consumer.accept(event);
                                }
                                redisTemplate.opsForStream().acknowledge(stream, group, record.getId());
                            } catch (Exception e) {
                                log.error("[RedisStreamTransport] Error processing record {}: {}",
                                        record.getId(), e.getMessage(), e);
                            }
                        }
                    }
                } catch (Exception e) {
                    if (running.get()) {
                        log.warn("[RedisStreamTransport] Error reading stream '{}': {}", stream, e.getMessage());
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
        });
    }

    @Override
    public TransportType getType() {
        return TransportType.REDIS;
    }

    @Override
    public void shutdown() {
        running.set(false);
        listenerPool.shutdownNow();
        log.info("[RedisStreamTransport] Shut down");
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private String streamKey(String topic) {
        return "aura:events:" + topic;
    }

    private void ensureConsumerGroup(String stream, String group) {
        try {
            redisTemplate.opsForStream().createGroup(stream, ReadOffset.from("0"), group);
        } catch (Exception e) {
            // group already exists — safe to ignore
            log.debug("[RedisStreamTransport] Consumer group '{}' on stream '{}' likely already exists", group, stream);
        }
    }
}
