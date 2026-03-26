package com.auraboot.framework.infrastructure.mq.kafka;

import com.auraboot.framework.infrastructure.mq.MqMessageHandler;
import com.auraboot.framework.infrastructure.mq.MqProperties;
import com.auraboot.framework.infrastructure.mq.MqProvider;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Apache Kafka implementation of {@link MqProvider}.
 * <p>
 * Activated when {@code aura.mq.type=kafka} is set in application properties.
 * Requires a Kafka broker accessible at the configured bootstrap servers.
 * </p>
 *
 * <h3>Configuration example (application.yml):</h3>
 * <pre>
 * aura:
 *   mq:
 *     type: kafka
 *     kafka:
 *       bootstrap-servers: localhost:9092
 *       consumer-group: aura-group
 * </pre>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "aura.mq.type", havingValue = "kafka")
public class KafkaMqProvider implements MqProvider, DisposableBean {

    private static final Duration POLL_TIMEOUT = Duration.ofMillis(100);

    private final String bootstrapServers;
    private final String defaultConsumerGroup;
    private final KafkaProducer<String, String> producer;
    private final Map<String, ConsumerEntry> consumers = new ConcurrentHashMap<>();

    public KafkaMqProvider(MqProperties properties) {
        MqProperties.Kafka kafkaConfig = properties.getKafka();
        this.bootstrapServers = kafkaConfig.getBootstrapServers();
        this.defaultConsumerGroup = kafkaConfig.getConsumerGroup();
        this.producer = createProducer(bootstrapServers);

        log.info("KafkaMqProvider initialized: bootstrapServers={}, defaultGroup={}",
                bootstrapServers, defaultConsumerGroup);
    }

    /**
     * Package-private constructor for testing with a pre-built producer.
     */
    KafkaMqProvider(KafkaProducer<String, String> producer, String bootstrapServers, String defaultConsumerGroup) {
        this.producer = producer;
        this.bootstrapServers = bootstrapServers;
        this.defaultConsumerGroup = defaultConsumerGroup;
    }

    @Override
    public void send(String topic, String messageBody, Map<String, String> headers) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, messageBody);

        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                record.headers().add(entry.getKey(), entry.getValue().getBytes(StandardCharsets.UTF_8));
            }
        }

        producer.send(record, (metadata, exception) -> {
            if (exception != null) {
                log.error("Failed to send message to Kafka topic={}: {}", topic, exception.getMessage(), exception);
            } else {
                log.debug("Message sent to Kafka: topic={}, partition={}, offset={}",
                        metadata.topic(), metadata.partition(), metadata.offset());
            }
        });
    }

    @Override
    public void subscribe(String topic, String groupId, MqMessageHandler handler) {
        String key = buildKey(topic, groupId);
        if (consumers.containsKey(key)) {
            log.warn("Already subscribed to topic={}, group={}. Ignoring duplicate subscription.", topic, groupId);
            return;
        }

        AtomicBoolean running = new AtomicBoolean(true);

        Thread consumerThread = new Thread(() -> {
            KafkaConsumer<String, String> consumer = createConsumer(bootstrapServers, groupId);
            // Store consumer reference so unsubscribe/destroy can call wakeup()
            ConsumerEntry entry = consumers.get(key);
            if (entry != null) {
                entry.setConsumer(consumer);
            }

            try {
                consumer.subscribe(Collections.singletonList(topic));
                log.info("Kafka consumer started: topic={}, group={}", topic, groupId);

                while (running.get()) {
                    ConsumerRecords<String, String> records = consumer.poll(POLL_TIMEOUT);
                    for (ConsumerRecord<String, String> record : records) {
                        try {
                            Map<String, String> headersMap = extractHeaders(record);
                            handler.handle(topic, record.value(), headersMap);
                        } catch (Exception e) {
                            log.error("Kafka handler error: topic={}, group={}, offset={}",
                                    topic, groupId, record.offset(), e);
                        }
                    }
                }
            } catch (WakeupException e) {
                // Expected during shutdown — only rethrow if still supposed to be running
                if (running.get()) {
                    throw e;
                }
            } finally {
                try {
                    consumer.close();
                } catch (Exception e) {
                    log.warn("Error closing Kafka consumer: topic={}, group={}", topic, groupId, e);
                }
                log.info("Kafka consumer stopped: topic={}, group={}", topic, groupId);
            }
        }, "kafka-consumer-" + topic + "-" + groupId);

        consumerThread.setDaemon(true);

        // Register entry before starting thread (consumer reference set by thread itself)
        consumers.put(key, new ConsumerEntry(null, consumerThread, running));
        consumerThread.start();
    }

    @Override
    public void unsubscribe(String topic, String groupId) {
        String key = buildKey(topic, groupId);
        ConsumerEntry entry = consumers.remove(key);
        if (entry == null) {
            log.warn("No subscription found for topic={}, group={}", topic, groupId);
            return;
        }

        entry.running().set(false);
        KafkaConsumer<String, String> consumer = entry.consumer();
        if (consumer != null) {
            consumer.wakeup();
        }
        log.info("Kafka consumer unsubscribed: topic={}, group={}", topic, groupId);
    }

    @Override
    public void destroy() {
        log.info("KafkaMqProvider shutting down: closing producer and {} consumer(s)", consumers.size());

        // Signal all consumers to stop
        for (Map.Entry<String, ConsumerEntry> entry : consumers.entrySet()) {
            entry.getValue().running().set(false);
            KafkaConsumer<String, String> consumer = entry.getValue().consumer();
            if (consumer != null) {
                consumer.wakeup();
            }
        }

        // Wait for consumer threads to finish
        for (Map.Entry<String, ConsumerEntry> entry : consumers.entrySet()) {
            try {
                entry.getValue().thread().join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for consumer thread: {}", entry.getKey());
            }
        }

        consumers.clear();

        // Close producer
        try {
            producer.close(Duration.ofSeconds(5));
        } catch (Exception e) {
            log.warn("Error closing Kafka producer", e);
        }

        log.info("KafkaMqProvider shutdown complete");
    }

    /**
     * Visible for testing — number of active subscriptions.
     */
    int activeSubscriptionCount() {
        return consumers.size();
    }

    /**
     * Visible for testing — check if a subscription exists.
     */
    boolean hasSubscription(String topic, String groupId) {
        return consumers.containsKey(buildKey(topic, groupId));
    }

    /**
     * Factory method for creating the Kafka producer. Package-private for test override.
     */
    static KafkaProducer<String, String> createProducer(String bootstrapServers) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        return new KafkaProducer<>(props);
    }

    /**
     * Factory method for creating a Kafka consumer. Package-private for test override.
     */
    static KafkaConsumer<String, String> createConsumer(String bootstrapServers, String groupId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        return new KafkaConsumer<>(props);
    }

    private Map<String, String> extractHeaders(ConsumerRecord<String, String> record) {
        Map<String, String> headersMap = new HashMap<>();
        for (Header header : record.headers()) {
            headersMap.put(header.key(), new String(header.value(), StandardCharsets.UTF_8));
        }
        return headersMap;
    }

    private static String buildKey(String topic, String groupId) {
        return topic + ":" + groupId;
    }

    /**
     * Tracks a consumer thread and its lifecycle state.
     * The consumer reference is set after thread start (since KafkaConsumer is created inside the thread).
     */
    static final class ConsumerEntry {
        private volatile KafkaConsumer<String, String> consumer;
        private final Thread thread;
        private final AtomicBoolean running;

        ConsumerEntry(KafkaConsumer<String, String> consumer, Thread thread, AtomicBoolean running) {
            this.consumer = consumer;
            this.thread = thread;
            this.running = running;
        }

        KafkaConsumer<String, String> consumer() { return consumer; }
        Thread thread() { return thread; }
        AtomicBoolean running() { return running; }
        void setConsumer(KafkaConsumer<String, String> consumer) { this.consumer = consumer; }
    }
}
