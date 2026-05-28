package com.auraboot.framework.infrastructure.mq.kafka;

import com.auraboot.framework.infrastructure.mq.MqMessageHandler;
import com.auraboot.framework.infrastructure.mq.MqProperties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test for {@link KafkaMqProvider} backed by a real Kafka broker
 * via TestContainers.
 * <p>
 * Verifies end-to-end produce/consume + DLQ routing (handler throws → after
 * {@code maxAttempts} the message lands on {@code <topic>.DLT}).
 * </p>
 *
 * <h3>Disabled-when-Docker-unavailable</h3>
 * <p>Each test uses {@link org.junit.jupiter.api.Assumptions#assumeTrue} on
 * {@link DockerClientFactory#isDockerAvailable()} so the suite still passes
 * cleanly in environments without Docker (CI sandboxes, build agents). The
 * tests are <strong>not</strong> deleted — when Docker becomes available they
 * exercise the real broker.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class KafkaMqProviderIntegrationTest {

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @Test
    @DisplayName("send/subscribe: round-trips 5 messages through a real Kafka broker")
    void roundTripFiveMessages() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available — skipping real-broker integration");

        String topic = "it-roundtrip-" + UUID.randomUUID();
        String group = "it-group-" + UUID.randomUUID();
        MqProperties props = buildProps(KAFKA.getBootstrapServers(), group);

        KafkaMqProvider provider = new KafkaMqProvider(props);
        AtomicInteger received = new AtomicInteger();
        CompletableFuture<Void> done = new CompletableFuture<>();
        try {
            provider.subscribe(topic, group, (t, body, headers) -> {
                if (received.incrementAndGet() == 5) {
                    done.complete(null);
                }
            });

            // Allow consumer to join the group + partition assignment
            Thread.sleep(2000);

            for (int i = 0; i < 5; i++) {
                provider.send(topic, "msg-" + i, new HashMap<>());
            }

            done.get(30, TimeUnit.SECONDS);
            assertEquals(5, received.get());
        } finally {
            provider.unsubscribe(topic, group);
            provider.destroy();
        }
    }

    @Test
    @DisplayName("DLQ: failed handler routes to <topic>.DLT after maxAttempts")
    void dlqAfterMaxAttempts() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available — skipping real-broker integration");

        String topic = "it-dlq-" + UUID.randomUUID();
        String group = "it-group-" + UUID.randomUUID();
        MqProperties props = buildProps(KAFKA.getBootstrapServers(), group);
        // maxAttempts=3, retry on the topic itself then route to DLT
        props.getKafka().getDeadLetter().setMaxAttempts(3);

        KafkaMqProvider provider = new KafkaMqProvider(props);
        AtomicInteger attempts = new AtomicInteger();
        try {
            MqMessageHandler alwaysFails = (t, body, headers) -> {
                attempts.incrementAndGet();
                throw new RuntimeException("intentional handler failure");
            };
            provider.subscribe(topic, group, alwaysFails);

            // Wait for partition assignment, then produce a single poison message
            Thread.sleep(2000);
            provider.send(topic, "poison", new HashMap<>());

            // Poll DLT directly with a plain consumer to verify routing
            ConsumerRecord<String, String> dltRecord = pollDlt(topic + ".DLT", group + "-dlt-probe",
                    Duration.ofSeconds(30));
            assertNotNull(dltRecord, "expected a record on the DLT");
            assertEquals("poison", dltRecord.value());
            assertNotNull(dltRecord.headers().lastHeader(DeadLetterQueueHandler.ORIGINAL_TOPIC_HEADER));
            assertNotNull(dltRecord.headers().lastHeader(DeadLetterQueueHandler.ERROR_CLASS_HEADER));

            // Sanity: handler was called at least maxAttempts times
            assertTrue(attempts.get() >= 3, "handler should be retried before DLT routing, got " + attempts.get());
        } finally {
            provider.unsubscribe(topic, group);
            provider.destroy();
        }
    }

    private static MqProperties buildProps(String bootstrap, String group) {
        MqProperties props = new MqProperties();
        props.setType("kafka");
        props.getKafka().setBootstrapServers(bootstrap);
        props.getKafka().setConsumerGroup(group);
        // Defaults: DLQ enabled, .DLT suffix, maxAttempts=3
        return props;
    }

    private static ConsumerRecord<String, String> pollDlt(String dltTopic, String group, Duration timeout) {
        Properties cfg = new Properties();
        cfg.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        cfg.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        cfg.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        cfg.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        cfg.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        cfg.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");

        long deadline = System.nanoTime() + timeout.toNanos();
        try (KafkaConsumer<String, String> probe = new KafkaConsumer<>(cfg)) {
            probe.subscribe(Collections.singletonList(dltTopic));
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, String> recs = probe.poll(Duration.ofMillis(500));
                if (!recs.isEmpty()) {
                    return recs.iterator().next();
                }
            }
        }
        return null;
    }
}
