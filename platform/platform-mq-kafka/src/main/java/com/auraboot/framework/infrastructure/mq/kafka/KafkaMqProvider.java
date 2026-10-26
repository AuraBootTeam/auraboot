package com.auraboot.framework.infrastructure.mq.kafka;

import com.auraboot.framework.infrastructure.mq.MqMessageHandler;
import com.auraboot.framework.infrastructure.mq.MqProperties;
import com.auraboot.framework.infrastructure.mq.MqProvider;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
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
    private final DeadLetterQueueHandler deadLetterQueueHandler;
    private final Map<String, ConsumerEntry> consumers = new ConcurrentHashMap<>();
    private boolean destroyed;

    public KafkaMqProvider(MqProperties properties) {
        MqProperties.Kafka kafkaConfig = properties.getKafka();
        this.bootstrapServers = kafkaConfig.getBootstrapServers();
        this.defaultConsumerGroup = kafkaConfig.getConsumerGroup();
        this.producer = createProducer(bootstrapServers);
        MqProperties.Kafka.DeadLetter dl = kafkaConfig.getDeadLetter();
        this.deadLetterQueueHandler = new DeadLetterQueueHandler(
                this.producer, dl.getTopicSuffix(), dl.getMaxAttempts(), dl.isEnabled());

        log.info("KafkaMqProvider initialized: bootstrapServers={}, defaultGroup={}, dlqEnabled={}, dlqSuffix={}, maxAttempts={}",
                bootstrapServers, defaultConsumerGroup,
                dl.isEnabled(), dl.getTopicSuffix(), dl.getMaxAttempts());
    }

    /**
     * Package-private constructor for testing with a pre-built producer.
     */
    KafkaMqProvider(KafkaProducer<String, String> producer, String bootstrapServers, String defaultConsumerGroup) {
        this(producer, bootstrapServers, defaultConsumerGroup,
                new DeadLetterQueueHandler(producer, ".DLT", 3, true));
    }

    /**
     * Package-private constructor for tests that need to inject a custom DLQ handler.
     */
    KafkaMqProvider(KafkaProducer<String, String> producer,
                    String bootstrapServers,
                    String defaultConsumerGroup,
                    DeadLetterQueueHandler deadLetterQueueHandler) {
        this.producer = producer;
        this.bootstrapServers = bootstrapServers;
        this.defaultConsumerGroup = defaultConsumerGroup;
        this.deadLetterQueueHandler = deadLetterQueueHandler;
    }

    @Override
    public void send(String topic, String messageBody, Map<String, String> headers) {
        ProducerRecord<String, String> record = buildRecord(topic, null, messageBody, headers);

        producer.send(record, (metadata, exception) -> {
            if (exception != null) {
                log.error("Failed to send message to Kafka topic={}: {}", topic, exception.getMessage(), exception);
            } else {
                log.debug("Message sent to Kafka: topic={}, partition={}, offset={}",
                        metadata.topic(), metadata.partition(), metadata.offset());
            }
        });
    }

    /**
     * Send using a stable caller-owned partition key and expose the broker result.
     * Completion means Kafka acknowledged the record under {@code acks=all}, not
     * that a downstream service persisted or processed it. A failed/uncertain send
     * may have reached Kafka; retries must retain the application's event identity.
     * Key ordering requires a stable partition count and partitioning policy.
     */
    public CompletionStage<RecordMetadata> sendConfirmed(String topic, String partitionKey,
                                                        String messageBody, Map<String, String> headers) {
        if (partitionKey == null || partitionKey.isBlank()) {
            throw new IllegalArgumentException("A nonblank partition key is required");
        }
        ProducerRecord<String, String> record = buildRecord(topic, partitionKey, messageBody, headers);
        CompletableFuture<RecordMetadata> result = new CompletableFuture<>();
        try {
            producer.send(record, (metadata, error) -> {
                if (error != null) {
                    result.completeExceptionally(error);
                } else if (metadata == null) {
                    result.completeExceptionally(new IllegalStateException("Kafka acknowledgement has no metadata"));
                } else {
                    result.complete(metadata);
                }
            });
        } catch (RuntimeException error) {
            result.completeExceptionally(error);
        }
        return result.minimalCompletionStage();
    }

    private static ProducerRecord<String, String> buildRecord(String topic, String key,
                                                              String body, Map<String, String> headers) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, body);
        if (headers != null) {
            headers.forEach((name, value) -> record.headers().add(name, value.getBytes(StandardCharsets.UTF_8)));
        }
        return record;
    }

    @Override
    public void subscribe(String topic, String groupId, MqMessageHandler handler) {
        Objects.requireNonNull(handler, "handler");
        startSubscription(topic, groupId,
                record -> handler.handle(record.topic(), record.value(), extractHeaders(record)), false);
    }

    /**
     * Consume with automatic commits disabled. The callback must synchronously
     * commit the caller's durable receipt before returning (within Kafka's poll
     * interval). Only then is this record's exact partition offset + 1 committed.
     * The record exposes source topic/partition/offset/key for receipt identity.
     *
     * <p>A callback or commit failure stops this subscription and completes the
     * returned stage exceptionally. No retry topic or DLT bypasses the failed
     * receipt. After fixing the cause, subscribe again using the same group to
     * replay uncommitted records. A crash/rebalance/uncertain commit can redeliver
     * an already persisted receipt, so the receiver must be idempotent. Never mix
     * legacy auto-commit consumers with this mode in the same group. This is not
     * an atomic Kafka/database transaction or a domain command bridge.</p>
     *
     * @return lifecycle stage: normal completion after stop/close, exceptional
     *         completion on receive, commit or close failure
     */
    public CompletionStage<Void> subscribeDurably(String topic, String groupId, DurableMessageHandler handler) {
        return startSubscription(topic, groupId, Objects.requireNonNull(handler, "handler"), true);
    }

    @FunctionalInterface
    public interface DurableMessageHandler {
        void handle(ConsumerRecord<String, String> record) throws Exception;
    }

    private synchronized CompletionStage<Void> startSubscription(String topic, String groupId,
                                                     DurableMessageHandler handler, boolean durable) {
        if (destroyed) {
            throw new IllegalStateException("Kafka provider is closed");
        }
        if (topic == null || topic.isBlank() || groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("Topic and groupId must be nonblank");
        }
        String key = buildKey(topic, groupId);
        ConsumerEntry entry = new ConsumerEntry();
        Thread thread = new Thread(() -> runConsumer(key, entry, topic, groupId, handler, durable),
                "kafka-consumer-" + topic + "-" + groupId);
        thread.setDaemon(true);
        entry.thread = thread;
        ConsumerEntry previous = consumers.putIfAbsent(key, entry);
        if (previous != null) {
            if (durable) {
                throw new IllegalStateException("Subscription is already active or stopping: " + key);
            }
            log.warn("Already subscribed to topic={}, group={}. Ignoring duplicate subscription.", topic, groupId);
            return previous.stopped.minimalCompletionStage();
        }
        thread.start();
        return entry.stopped.minimalCompletionStage();
    }

    private void runConsumer(String key, ConsumerEntry entry, String topic, String groupId,
                             DurableMessageHandler handler, boolean durable) {
        Throwable failure = null;
        try (Consumer<String, String> consumer = createSubscriptionConsumer(groupId, durable)) {
            entry.consumer = consumer;
            consumer.subscribe(Collections.singletonList(topic));
            while (entry.running.get()) {
                ConsumerRecords<String, String> records = consumer.poll(POLL_TIMEOUT);
                for (ConsumerRecord<String, String> record : records) {
                    if (!entry.running.get()) {
                        break;
                    }
                    if (durable) {
                        handler.handle(record);
                        if (!entry.running.get()) {
                            break;
                        }
                        consumer.commitSync(Map.of(new TopicPartition(record.topic(), record.partition()),
                                new OffsetAndMetadata(record.offset() + 1)));
                    } else {
                        try {
                            handler.handle(record);
                        } catch (Exception error) {
                            handleConsumerFailure(record, error, topic, groupId);
                        }
                    }
                }
            }
        } catch (WakeupException error) {
            if (entry.running.get()) {
                failure = error;
            }
        } catch (Throwable error) {
            // This thread owns the lifecycle stage: Errors must not become normal completion either.
            failure = error;
        } finally {
            entry.running.set(false);
            consumers.remove(key, entry);
            if (failure == null) {
                entry.stopped.complete(null);
            } else {
                log.error("Kafka subscription stopped after failure: topic={}, group={}, durable={}",
                        topic, groupId, durable, failure);
                entry.stopped.completeExceptionally(failure);
            }
        }
    }

    @Override
    public void unsubscribe(String topic, String groupId) {
        String key = buildKey(topic, groupId);
        ConsumerEntry entry = consumers.get(key);
        if (entry == null) {
            log.warn("No subscription found for topic={}, group={}", topic, groupId);
            return;
        }

        entry.running().set(false);
        Consumer<String, String> consumer = entry.consumer();
        if (consumer != null) {
            consumer.wakeup();
        }
        log.info("Kafka consumer unsubscribed: topic={}, group={}", topic, groupId);
    }

    @Override
    public synchronized void destroy() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        log.info("KafkaMqProvider shutting down: closing producer and {} consumer(s)", consumers.size());

        // Signal all consumers to stop
        for (Map.Entry<String, ConsumerEntry> entry : consumers.entrySet()) {
            entry.getValue().running().set(false);
            Consumer<String, String> consumer = entry.getValue().consumer();
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
        return (int) consumers.values().stream().filter(entry -> entry.running.get()).count();
    }

    /**
     * Visible for testing — check if a subscription exists.
     */
    boolean hasSubscription(String topic, String groupId) {
        ConsumerEntry entry = consumers.get(buildKey(topic, groupId));
        return entry != null && entry.running.get();
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
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        return new KafkaProducer<>(props);
    }

    /**
     * Factory method for creating a Kafka consumer. Package-private for test override.
     */
    static KafkaConsumer<String, String> createConsumer(String bootstrapServers, String groupId) {
        return new KafkaConsumer<>(consumerProperties(bootstrapServers, groupId, false));
    }

    Consumer<String, String> createSubscriptionConsumer(String groupId, boolean durable) {
        return new KafkaConsumer<>(consumerProperties(bootstrapServers, groupId, durable));
    }

    static Properties consumerProperties(String bootstrapServers, String groupId, boolean durable) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, Boolean.toString(!durable));
        return props;
    }

    /**
     * Visible for testing — the configured DLQ handler.
     */
    DeadLetterQueueHandler deadLetterQueueHandler() {
        return deadLetterQueueHandler;
    }

    /**
     * Handle a consumer-side processing failure.
     * <p>
     * Increment the {@code x-retry-count} header. If attempts have reached
     * {@link DeadLetterQueueHandler#getMaxAttempts()} the record is routed to the DLT;
     * otherwise it is re-produced to the original topic for another delivery attempt.
     * When DLQ is disabled, the failure is just logged (legacy behaviour).
     * </p>
     */
    private void handleConsumerFailure(ConsumerRecord<String, String> record,
                                       Throwable error,
                                       String topic,
                                       String groupId) {
        int previousAttempts = DeadLetterQueueHandler.currentRetryCount(record);
        int newAttempts = previousAttempts + 1;

        if (!deadLetterQueueHandler.isEnabled()) {
            log.error("Kafka handler error (DLQ disabled): topic={}, group={}, offset={}",
                    topic, groupId, record.offset(), error);
            return;
        }

        if (deadLetterQueueHandler.shouldRouteToDlt(newAttempts)) {
            log.error("Kafka handler exhausted retries ({}/{}): topic={}, group={}, offset={} -> DLT",
                    newAttempts, deadLetterQueueHandler.getMaxAttempts(), topic, groupId, record.offset(), error);
            deadLetterQueueHandler.routeToDeadLetter(record, error, newAttempts);
            return;
        }

        log.warn("Kafka handler error (attempt {}/{}): topic={}, group={}, offset={} — requeueing",
                newAttempts, deadLetterQueueHandler.getMaxAttempts(), topic, groupId, record.offset(), error);

        ProducerRecord<String, String> retry = new ProducerRecord<>(record.topic(), record.key(), record.value());
        for (Header h : record.headers()) {
            if (!DeadLetterQueueHandler.RETRY_COUNT_HEADER.equals(h.key())) {
                retry.headers().add(h.key(), h.value());
            }
        }
        retry.headers().add(DeadLetterQueueHandler.RETRY_COUNT_HEADER,
                Integer.toString(newAttempts).getBytes(StandardCharsets.UTF_8));
        producer.send(retry, (md, ex) -> {
            if (ex != null) {
                log.error("Failed to requeue record after handler error: topic={}, offset={}",
                        record.topic(), record.offset(), ex);
            }
        });
    }

    private static Map<String, String> extractHeaders(ConsumerRecord<String, String> record) {
        Map<String, String> headersMap = new HashMap<>();
        for (Header header : record.headers()) {
            headersMap.put(header.key(), header.value() == null ? null
                    : new String(header.value(), StandardCharsets.UTF_8));
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
        private volatile Consumer<String, String> consumer;
        private Thread thread;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final CompletableFuture<Void> stopped = new CompletableFuture<>();

        Consumer<String, String> consumer() { return consumer; }
        Thread thread() { return thread; }
        AtomicBoolean running() { return running; }
    }
}
