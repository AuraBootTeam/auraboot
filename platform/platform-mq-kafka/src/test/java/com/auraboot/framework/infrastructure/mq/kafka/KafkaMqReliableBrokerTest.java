package com.auraboot.framework.infrastructure.mq.kafka;

import com.auraboot.framework.infrastructure.mq.MqProperties;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real Kafka/PostgreSQL seam; run only through reliableBrokerTest in an owned runtime. */
@Tag("real-broker")
class KafkaMqReliableBrokerTest {
    private String topic;
    private String group;
    private String database;
    private String jdbcUrl;
    private Admin admin;
    private KafkaMqProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        topic = required("KAFKA_TOPIC_PREFIX") + "reliable-" + suffix;
        group = required("KAFKA_CONSUMER_GROUP_PREFIX") + "reliable-" + suffix;
        database = required("POSTGRES_DB");
        jdbcUrl = "jdbc:postgresql://" + required("POSTGRES_HOST") + ":" + required("POSTGRES_PORT") + "/" + database;
        String bootstrap = required("KAFKA_BOOTSTRAP_SERVERS");
        admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap));
        // Set admission limits at creation; altering a live topic propagates asynchronously.
        admin.createTopics(List.of(new NewTopic(topic, 3, (short) 1)
                .configs(Map.of("max.message.bytes", "512")))).all().get(15, TimeUnit.SECONDS);
        MqProperties properties = new MqProperties();
        properties.getKafka().setBootstrapServers(bootstrap);
        provider = new KafkaMqProvider(properties);
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("SELECT current_database()")) {
                assertTrue(result.next());
                assertEquals(database, result.getString(1));
            }
            statement.execute("CREATE SCHEMA IF NOT EXISTS mq_reliability_test");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS mq_reliability_test.receipt (
                      topic text NOT NULL, partition_id integer NOT NULL, offset_id bigint NOT NULL,
                      event_key text NOT NULL, body text NOT NULL,
                      PRIMARY KEY (topic, partition_id, offset_id))
                    """);
        }
        System.out.printf("runtime seam: topic=%s group=%s database=%s bootstrap=%s%n", topic, group, database, bootstrap);
    }

    @AfterEach
    void close() {
        if (provider != null) provider.destroy();
        if (admin != null) admin.close();
        // Owned topics, committed offsets and receipt rows remain as execution evidence.
    }

    @Test
    void keyedBrokerAcknowledgementAndDurableReceiptAdvanceExactPartition() throws Exception {
        var first = provider.sendConfirmed(topic, "station:target-7", "event-1", Map.of("event-id", "id-1"))
                .toCompletableFuture().get(15, TimeUnit.SECONDS);
        var second = provider.sendConfirmed(topic, "station:target-7", "event-2", Map.of("event-id", "id-2"))
                .toCompletableFuture().get(15, TimeUnit.SECONDS);
        assertEquals(first.partition(), second.partition());
        assertEquals(first.offset() + 1, second.offset());
        provider.subscribeDurably(topic, group, record -> persist(record, false));
        awaitCommitted(new TopicPartition(topic, second.partition()), second.offset() + 1);
        assertEquals(2, receiptCount());
        assertEquals(List.of("event-1", "event-2"), receiptBodies());
    }

    @Test
    void persistedReceiptBeforeOffsetFailureReplaysWithoutDuplicateReceipt() throws Exception {
        var metadata = provider.sendConfirmed(topic, "target-7", "durable-event", Map.of())
                .toCompletableFuture().get(15, TimeUnit.SECONDS);
        TopicPartition partition = new TopicPartition(topic, metadata.partition());
        AtomicInteger deliveries = new AtomicInteger();
        CompletableFuture<Void> first = provider.subscribeDurably(topic, group, record -> {
            deliveries.incrementAndGet();
            assertEquals(1, persist(record, false));
            throw new IOException("simulated receiver stop after database commit, before offset commit");
        }).toCompletableFuture();
        assertInstanceOf(IOException.class,
                assertThrows(ExecutionException.class, () -> first.get(30, TimeUnit.SECONDS)).getCause());
        assertEquals(1, receiptCount());
        assertNull(committed(partition), "receipt failure must leave the Kafka offset uncommitted");
        provider.subscribeDurably(topic, group, record -> {
            deliveries.incrementAndGet();
            assertEquals(metadata.offset(), record.offset());
            assertEquals(0, persist(record, false), "durable receipt must deduplicate the replay");
        });
        awaitCommitted(partition, metadata.offset() + 1);
        assertEquals(2, deliveries.get());
        assertEquals(1, receiptCount());
        assertEquals(List.of("durable-event"), receiptBodies());
    }

    @Test
    void rolledBackReceiptDoesNotAdvanceOffsetAndIsReceivedOnRestart() throws Exception {
        var metadata = provider.sendConfirmed(topic, "target-8", "rollback-event", Map.of())
                .toCompletableFuture().get(15, TimeUnit.SECONDS);
        TopicPartition partition = new TopicPartition(topic, metadata.partition());
        CompletableFuture<Void> first = provider.subscribeDurably(topic, group, record -> {
            persist(record, true);
            throw new IOException("receipt transaction rolled back");
        }).toCompletableFuture();
        assertInstanceOf(IOException.class,
                assertThrows(ExecutionException.class, () -> first.get(30, TimeUnit.SECONDS)).getCause());
        assertEquals(0, receiptCount());
        assertNull(committed(partition));
        provider.subscribeDurably(topic, group, record -> assertEquals(1, persist(record, false)));
        awaitCommitted(partition, metadata.offset() + 1);
        assertEquals(1, receiptCount());
        assertEquals(List.of("rollback-event"), receiptBodies());
    }

    @Test
    void brokerRejectionCompletesConfirmedSendExceptionally() throws Exception {
        CompletableFuture<?> send = provider.sendConfirmed(topic, "target-9", "x".repeat(4096), Map.of())
                .toCompletableFuture();
        assertInstanceOf(RecordTooLargeException.class,
                assertThrows(ExecutionException.class, () -> send.get(30, TimeUnit.SECONDS)).getCause());
        assertEquals(0, receiptCount());
    }

    private int persist(ConsumerRecord<String, String> record, boolean rollback) throws Exception {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO mq_reliability_test.receipt (topic, partition_id, offset_id, event_key, body)
                    VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                    """)) {
                statement.setString(1, record.topic());
                statement.setInt(2, record.partition());
                statement.setLong(3, record.offset());
                statement.setString(4, record.key());
                statement.setString(5, record.value());
                int inserted = statement.executeUpdate();
                if (rollback) connection.rollback();
                else connection.commit();
                return inserted;
            }
        }
    }

    private int receiptCount() throws Exception {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT count(*) FROM mq_reliability_test.receipt WHERE topic = ?")) {
            statement.setString(1, topic);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    private List<String> receiptBodies() throws Exception {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT body FROM mq_reliability_test.receipt WHERE topic = ? ORDER BY offset_id")) {
            statement.setString(1, topic);
            try (ResultSet result = statement.executeQuery()) {
                var bodies = new java.util.ArrayList<String>();
                while (result.next()) bodies.add(result.getString(1));
                return bodies;
            }
        }
    }

    private OffsetAndMetadata committed(TopicPartition partition) throws Exception {
        return admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
                .get(10, TimeUnit.SECONDS).get(partition);
    }

    private void awaitCommitted(TopicPartition partition, long offset) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            OffsetAndMetadata actual = committed(partition);
            if (actual != null && actual.offset() == offset) return;
            if (actual != null && actual.offset() > offset) fail("committed beyond the received record: " + actual);
        }
        fail("expected committed offset " + offset + " for " + partition);
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(jdbcUrl, required("POSTGRES_USER"), required("POSTGRES_PASSWORD"));
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Owned host-first runtime is required; missing " + name);
        }
        return value;
    }
}
