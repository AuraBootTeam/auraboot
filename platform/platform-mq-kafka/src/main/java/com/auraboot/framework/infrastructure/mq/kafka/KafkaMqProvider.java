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
    private final java.util.concurrent.locks.ReentrantLock closeLock = new java.util.concurrent.locks.ReentrantLock();
    private final ThreadLocal<Boolean> producerCallback = new ThreadLocal<>();
    private volatile boolean destroyed;
    private volatile RuntimeException closeFailure;
    private volatile Thread producerCloser;
    private final CompletableFuture<Void> producerClosed = new CompletableFuture<>();

    /** Failure never means the SDK's resources have terminated; supervisors retain this observation. */
    public record CloseStatus(boolean requested, int liveConsumers, long producerCloserThreadId,
                              boolean producerCloserAlive, boolean producerCleanupSucceeded, boolean successful) {}

    public CloseStatus closeStatus() {
        Thread closer = producerCloser;
        int live = (int) consumers.values().stream().filter(entry -> entry.thread().isAlive()).count();
        boolean producerSucceeded = producerClosed.isDone() && !producerClosed.isCompletedExceptionally();
        return new CloseStatus(destroyed, live, closer == null ? 0 : closer.threadId(),
                closer != null && closer.isAlive(), producerSucceeded,
                destroyed && closeFailure == null && producerSucceeded && live == 0 && closer != null && !closer.isAlive());
    }

    /** Trusted, bounded producer budgets; no arbitrary producer configuration is exposed. */
    public record ProducerTimeouts(Duration maxBlockTimeout, Duration requestTimeout, Duration deliveryTimeout) {
        public ProducerTimeouts {
            long block = boundedMillis(maxBlockTimeout), request = boundedMillis(requestTimeout);
            long delivery = boundedMillis(deliveryTimeout);
            if (delivery < request || block + delivery > 60_000)
                throw new IllegalArgumentException("Delivery must cover request timeout within a 60-second total budget");
        }

        /** Split the total admission/delivery budget; delivery includes request time, with zero linger. */
        public static ProducerTimeouts within(Duration operationTimeout) {
            long total = boundedMillis(operationTimeout);
            if (total < 2) throw new IllegalArgumentException("The producer operation budget must be at least two milliseconds");
            long block = total / 2;
            return new ProducerTimeouts(Duration.ofMillis(block), Duration.ofMillis(total - block),
                    Duration.ofMillis(total - block));
        }
    }

    public KafkaMqProvider(MqProperties properties) {
        this(properties, createProducer(properties.getKafka().getBootstrapServers()));
    }

    /** Explicit bounded construction for supervised callers; ordinary host configuration stays compatible. */
    public KafkaMqProvider(MqProperties properties, ProducerTimeouts timeouts) {
        this(properties, createProducer(properties.getKafka().getBootstrapServers(), Objects.requireNonNull(timeouts)));
    }

    private KafkaMqProvider(MqProperties properties, KafkaProducer<String, String> producer) {
        MqProperties.Kafka kafkaConfig = properties.getKafka();
        this.bootstrapServers = kafkaConfig.getBootstrapServers();
        this.defaultConsumerGroup = kafkaConfig.getConsumerGroup();
        this.producer = producer;
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
                producerCallback.set(true);
                try {
                    if (error != null) {
                        result.completeExceptionally(error);
                    } else if (metadata == null) {
                        result.completeExceptionally(new IllegalStateException("Kafka acknowledgement has no metadata"));
                    } else {
                        result.complete(metadata);
                    }
                } finally { producerCallback.remove(); }
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
    public void destroy() { close(Duration.ofSeconds(5)); }

    /**
     * Stop owned subscriptions and the producer under one cleanup deadline. Failure, interruption
     * or a surviving consumer is observable; an earlier failed close never becomes a normal return.
     */
    public void close(Duration timeout) {
        boundedMillis(timeout);
        if (Boolean.TRUE.equals(producerCallback.get()))
            throw new IllegalStateException("A producer callback cannot await its own Kafka close; use the supervising thread");
        long deadline = System.nanoTime() + timeout.toNanos();
        boolean interrupted = Thread.interrupted();
        try {
            boolean acquired;
            try {
                acquired = closeLock.tryLock(Math.max(0, deadline - System.nanoTime()), java.util.concurrent.TimeUnit.NANOSECONDS);
            } catch (InterruptedException error) {
                interrupted = true;
                throw new IllegalStateException("Waiting for Kafka close was interrupted", error);
            }
            if (!acquired) throw new IllegalStateException("Another Kafka close exceeded this caller's deadline");
            try { closeOwned(deadline, interrupted); }
            finally { closeLock.unlock(); }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private void closeOwned(long deadline, boolean interrupted) {
        List<ConsumerEntry> owned;
        synchronized (this) {
            if (destroyed) {
                if (closeFailure != null) throw new IllegalStateException("An earlier Kafka close failed", closeFailure);
                if (interrupted) throw new IllegalStateException("Kafka close was interrupted", new InterruptedException());
                return;
            }
            destroyed = true;
            closeFailure = new IllegalStateException("Kafka close has not completed");
            owned = List.copyOf(consumers.values());
        }
        RuntimeException failure = interrupted
                ? new IllegalStateException("Kafka close was interrupted", new InterruptedException("Caller was interrupted")) : null;
        try {
            for (var entry : owned) {
                entry.running().set(false);
                try { if (entry.consumer() != null) entry.consumer().wakeup(); }
                catch (RuntimeException error) { failure = combineCloseFailure(failure, error); }
            }
            for (var entry : owned) {
                Thread thread = entry.thread();
                long remaining = Math.max(0, deadline - System.nanoTime());
                if (thread != Thread.currentThread() && remaining > 0) {
                    try { thread.join(remaining / 1_000_000, (int)(remaining % 1_000_000)); }
                    catch (InterruptedException error) {
                        interrupted = true;
                        failure = combineCloseFailure(failure, new IllegalStateException("Kafka consumer stop was interrupted", error));
                    }
                }
                if (thread.isAlive()) {
                    failure = combineCloseFailure(failure, new IllegalStateException("Kafka consumer survived its close deadline"));
                } else {
                    if (!entry.stopped.isDone()) {
                        failure = combineCloseFailure(failure, new IllegalStateException("Kafka consumer has no terminal lifecycle result"));
                    } else {
                        try { entry.stopped.join(); }
                        catch (java.util.concurrent.CompletionException error) { failure = combineCloseFailure(failure, error); }
                    }
                }
            }
            var producerFailure = closeProducer(deadline);
            if (producerFailure != null) failure = combineCloseFailure(failure, producerFailure);
            if (Thread.currentThread().isInterrupted()) {
                interrupted = true;
                failure = combineCloseFailure(failure,
                        new IllegalStateException("Kafka producer close was interrupted", new InterruptedException()));
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (failure != null) {
            closeFailure = failure;
            throw failure;
        }
        closeFailure = null;
        log.info("KafkaMqProvider shutdown complete");
    }

    /** Kafka's force-close contains an unbounded internal join. Never run it on the supervising caller. */
    private RuntimeException closeProducer(long deadline) {
        Duration sdkBudget = Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
        var closer = new Thread(() -> {
            try { producer.close(sdkBudget); producerClosed.complete(null); }
            catch (Throwable error) {
                // This owned thread must publish Errors as failed cleanup, never disappear as success.
                producerClosed.completeExceptionally(error);
            }
        }, "auraboot-kafka-producer-close-" + UUID.randomUUID());
        closer.setDaemon(true);
        producerCloser = closer;
        closer.start();
        long remaining = Math.max(0, deadline - System.nanoTime());
        try {
            if (remaining > 0) closer.join(remaining / 1_000_000, (int)(remaining % 1_000_000));
        } catch (InterruptedException error) {
            closer.interrupt(); Thread.currentThread().interrupt();
            return new IllegalStateException("Awaiting the owned Kafka producer closer was interrupted", error);
        }
        if (closer.isAlive()) {
            closer.interrupt();
            return new IllegalStateException("Owned Kafka producer cleanup exceeded its deadline; inspect closeStatus and retain process ownership",
                    new java.util.concurrent.TimeoutException("Kafka SDK cleanup has not terminated"));
        }
        if (!producerClosed.isDone()) return new IllegalStateException("Kafka producer closer has no terminal result");
        try { producerClosed.join(); return null; }
        catch (java.util.concurrent.CompletionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof org.apache.kafka.common.errors.InterruptException) Thread.currentThread().interrupt();
            return cause instanceof RuntimeException runtime ? runtime
                    : new IllegalStateException("Kafka producer cleanup failed", cause);
        }
    }

    private static RuntimeException combineCloseFailure(RuntimeException previous, RuntimeException next) {
        if (previous == null) return next;
        if (previous != next) previous.addSuppressed(next);
        return previous;
    }

    private static long boundedMillis(Duration value) {
        if (value == null || value.isNegative() || value.isZero() || value.compareTo(Duration.ofSeconds(60)) > 0
                || value.getNano() % 1_000_000 != 0)
            throw new IllegalArgumentException("A positive integral-millisecond duration of at most 60 seconds is required");
        return value.toMillis();
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
        return createProducer(bootstrapServers, null);
    }

    private static KafkaProducer<String, String> createProducer(String bootstrapServers, ProducerTimeouts timeouts) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        if (timeouts != null) {
            props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, timeouts.maxBlockTimeout().toMillis());
            props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, Math.toIntExact(timeouts.requestTimeout().toMillis()));
            props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, Math.toIntExact(timeouts.deliveryTimeout().toMillis()));
            props.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        }
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
