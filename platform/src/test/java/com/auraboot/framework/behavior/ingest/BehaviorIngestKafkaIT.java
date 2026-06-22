package com.auraboot.framework.behavior.ingest;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.behavior.dto.BehaviorEventInput;
import com.auraboot.framework.behavior.mapper.BehaviorEventMapper;
import com.auraboot.framework.behavior.mapper.BehaviorQuarantineMapper;
import com.auraboot.framework.infrastructure.mq.MqProperties;
import com.auraboot.framework.infrastructure.mq.MqProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Host-native Kafka verification for the behavior ingest wire contract. It uses the real
 * {@code platform-mq-kafka} provider when a broker is listening on localhost:9092; otherwise the
 * test is skipped cleanly so normal developer loops do not require Kafka.
 *
 * <p>The consumers use unique groups per test, but the database fixture is real Postgres from the
 * integration-test profile. Assertions therefore cover broker delivery, async consumer execution,
 * tenant-line ignores, idempotent inserts, and quarantine sink persistence together.</p>
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
class BehaviorIngestKafkaIT {

    private static final String BOOTSTRAP = "localhost:9092";
    private static final long TENANT = 990_301L;
    private static final long USER = 990_302L;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private BehaviorEventMapper behaviorEventMapper;
    @Autowired
    private BehaviorQuarantineMapper quarantineMapper;
    @Autowired
    private ObjectMapper objectMapper;

    private KafkaHarness harness;

    @BeforeEach
    void setup() throws Exception {
        assumeTrue(kafkaAvailable(), "Kafka broker not available at " + BOOTSTRAP);
        ensureTables();
        cleanup();

        MqProvider mq = newKafkaProvider();
        String suffix = UUID.randomUUID().toString();
        String eventGroup = "behavior-ingest-it-" + suffix;
        String quarantineGroup = "behavior-quarantine-it-" + suffix;
        BehaviorIngestPublisher publisher = new BehaviorIngestPublisher(mq, objectMapper);
        BehaviorEventPersister persister = new BehaviorEventPersister(behaviorEventMapper, publisher, objectMapper);
        BehaviorIngestConsumer eventConsumer = new BehaviorIngestConsumer(mq, persister, objectMapper, eventGroup);
        BehaviorQuarantineConsumer quarantineConsumer = new BehaviorQuarantineConsumer(
                mq, quarantineMapper, objectMapper, quarantineGroup);
        eventConsumer.subscribe();
        quarantineConsumer.subscribe();
        harness = new KafkaHarness(mq, publisher, eventGroup, quarantineGroup);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (harness != null) {
            harness.close();
        }
        cleanup();
    }

    @Test
    void kafkaProvider_asyncRoundTrip_persistsAndDeduplicates() {
        String eventId = "kfk-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        BehaviorEventInput event = event(eventId, "page_view", "anon-kafka");

        assertThat(harness.publisher().publish(TENANT, USER, List.of(event))).isEqualTo(1);

        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> assertThat(eventCount(eventId)).isEqualTo(1));
        assertThat(jdbc.queryForObject(
                "SELECT user_id FROM ab_behavior_event WHERE tenant_id=? AND event_id=?",
                Long.class, TENANT, eventId)).isEqualTo(USER);

        assertThat(harness.publisher().publish(TENANT, USER, List.of(event))).isEqualTo(1);

        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> assertThat(eventCount(eventId)).isEqualTo(1));
    }

    @Test
    void kafkaProvider_malformedEvent_quarantinesThroughNativeBroker() {
        String anonId = "anon-kafka-bad-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        BehaviorEventInput bad = event(null, "page_view", anonId);

        assertThat(harness.publisher().publish(TENANT, null, List.of(bad))).isEqualTo(1);

        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> {
                    assertThat(eventCountByAnon(anonId)).isZero();
                    assertThat(quarantineCountByAnon(anonId)).isEqualTo(1);
                });
        assertThat(jdbc.queryForObject(
                "SELECT reason FROM ab_behavior_quarantine WHERE tenant_id=? AND anon_id=?",
                String.class, TENANT, anonId)).isEqualTo("malformed_missing_event_id");
    }

    @Test
    void kafkaProvider_constraintViolation_quarantinesRawEventThroughNativeBroker() {
        String anonId = "anon-kafka-long-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String eventId = "kfk-overlong-" + "x".repeat(50);
        BehaviorEventInput bad = event(eventId, "page_view", anonId);

        assertThat(harness.publisher().publish(TENANT, null, List.of(bad))).isEqualTo(1);

        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> {
                    assertThat(eventCountByAnon(anonId)).isZero();
                    assertThat(quarantineCountByAnon(anonId)).isEqualTo(1);
                });
        assertThat(jdbc.queryForObject(
                "SELECT reason FROM ab_behavior_quarantine WHERE tenant_id=? AND anon_id=?",
                String.class, TENANT, anonId)).isEqualTo("constraint_violation");
        assertThat(jdbc.queryForObject(
                "SELECT event_id FROM ab_behavior_quarantine WHERE tenant_id=? AND anon_id=?",
                String.class, TENANT, anonId)).isEqualTo(eventId);
        assertThat(jdbc.queryForObject(
                "SELECT raw_event->>'eventId' FROM ab_behavior_quarantine WHERE tenant_id=? AND anon_id=?",
                String.class, TENANT, anonId)).isEqualTo(eventId);
    }

    private BehaviorEventInput event(String id, String name, String anonId) {
        BehaviorEventInput in = new BehaviorEventInput();
        in.setEventId(id);
        in.setEventName(name);
        in.setAnonId(anonId);
        return in;
    }

    private int eventCount(String eventId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(1) FROM ab_behavior_event WHERE tenant_id=? AND event_id=?",
                Integer.class, TENANT, eventId);
        return n == null ? 0 : n;
    }

    private int eventCountByAnon(String anonId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(1) FROM ab_behavior_event WHERE tenant_id=? AND anon_id=?",
                Integer.class, TENANT, anonId);
        return n == null ? 0 : n;
    }

    private int quarantineCountByAnon(String anonId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(1) FROM ab_behavior_quarantine WHERE tenant_id=? AND anon_id=?",
                Integer.class, TENANT, anonId);
        return n == null ? 0 : n;
    }

    private void cleanup() {
        jdbc.update("DELETE FROM ab_behavior_event WHERE tenant_id = ?", TENANT);
        jdbc.update("DELETE FROM ab_behavior_quarantine WHERE tenant_id = ?", TENANT);
    }

    private void ensureTables() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS ab_behavior_event (
                id BIGSERIAL PRIMARY KEY,
                event_id VARCHAR(40) NOT NULL,
                schema_version VARCHAR(16),
                event_name VARCHAR(120) NOT NULL,
                event_category VARCHAR(32),
                source VARCHAR(24),
                identity_quality VARCHAR(16),
                occurred_at TIMESTAMPTZ,
                received_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
                tenant_id BIGINT NOT NULL,
                user_id BIGINT,
                anon_id VARCHAR(64),
                client_session_id VARCHAR(64),
                interaction_id VARCHAR(64),
                caused_by_event_id VARCHAR(40),
                trace_id VARCHAR(36),
                source_span_id VARCHAR(36),
                run_id VARCHAR(64),
                ui_element_id VARCHAR(80),
                app_id VARCHAR(64),
                page_id VARCHAR(64),
                block_id VARCHAR(64),
                element_code VARCHAR(64),
                props JSONB,
                consent_state VARCHAR(24),
                consent_version VARCHAR(16),
                sampling_unit VARCHAR(16),
                sampling_probability NUMERIC(6,5),
                producer_name VARCHAR(48),
                producer_version VARCHAR(24),
                created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
            )""");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_ab_behavior_event_tenant_eventid "
                + "ON ab_behavior_event (tenant_id, event_id)");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS ab_behavior_quarantine (
                id BIGSERIAL PRIMARY KEY,
                tenant_id BIGINT NOT NULL,
                user_id BIGINT,
                anon_id TEXT,
                event_id TEXT,
                event_name TEXT,
                reason VARCHAR(64) NOT NULL,
                detail TEXT,
                raw_event JSONB,
                quarantined_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
                created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
            )""");
        jdbc.execute("ALTER TABLE ab_behavior_quarantine ALTER COLUMN anon_id TYPE TEXT");
        jdbc.execute("ALTER TABLE ab_behavior_quarantine ALTER COLUMN event_id TYPE TEXT");
        jdbc.execute("ALTER TABLE ab_behavior_quarantine ALTER COLUMN event_name TYPE TEXT");
    }

    private static boolean kafkaAvailable() {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) Duration.ofSeconds(2).toMillis());
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, (int) Duration.ofSeconds(2).toMillis());
        try (AdminClient admin = AdminClient.create(props)) {
            return !admin.describeCluster().nodes().get(2, TimeUnit.SECONDS).isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static MqProvider newKafkaProvider() throws Exception {
        MqProperties props = new MqProperties();
        props.setType("kafka");
        props.getKafka().setBootstrapServers(BOOTSTRAP);

        Class<?> providerType = Class.forName("com.auraboot.framework.infrastructure.mq.kafka.KafkaMqProvider");
        Constructor<?> ctor = providerType.getConstructor(MqProperties.class);
        return (MqProvider) ctor.newInstance(props);
    }

    private record KafkaHarness(MqProvider mq,
                                BehaviorIngestPublisher publisher,
                                String eventGroup,
                                String quarantineGroup) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            mq.unsubscribe(BehaviorIngestPublisher.TOPIC_EVENTS, eventGroup);
            mq.unsubscribe(BehaviorIngestPublisher.TOPIC_QUARANTINE, quarantineGroup);
            if (mq instanceof DisposableBean disposable) {
                disposable.destroy();
            }
        }
    }
}
