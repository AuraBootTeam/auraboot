package com.auraboot.framework.behavior.outcome;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.behavior.ingest.BehaviorIngestPublisher;
import com.auraboot.framework.infrastructure.mq.MqProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
class BehaviorOutcomeOutboxIT {

    private static final long TENANT = 990_701L;
    private static final long USER = 990_702L;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private BehaviorOutcomePublisher publisher;
    @Autowired
    private BehaviorOutcomeRelay relay;
    @Autowired
    private MqProvider mqProvider;
    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setup() {
        ensureTables();
        cleanup();
    }

    @Test
    void outcomeOutboxCommitsAtomicallyWithBusinessState() {
        String rolledBackEvent = eventId("rollback");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO ab_behavior_outcome_business_it (tenant_id, business_key, state) VALUES (?, ?, ?)",
                    TENANT, "order-rollback", "COMPLETED");
            publisher.publish(outcome(rolledBackEvent, "order-rollback"));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(count("ab_behavior_outcome_business_it", rolledBackEvent, "order-rollback")).isZero();
        assertThat(outboxCount(rolledBackEvent)).isZero();

        String committedEvent = eventId("commit");
        tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO ab_behavior_outcome_business_it (tenant_id, business_key, state) VALUES (?, ?, ?)",
                    TENANT, "order-commit", "COMPLETED");
            publisher.publish(outcome(committedEvent, "order-commit"));
        });

        assertThat(count("ab_behavior_outcome_business_it", committedEvent, "order-commit")).isEqualTo(1);
        assertThat(outboxCount(committedEvent)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM ab_behavior_outcome_outbox WHERE tenant_id=? AND event_id=?",
                String.class, TENANT, committedEvent)).isEqualTo("pending");
    }

    @Test
    void relayPublishesPendingOutcomeOnce_andPersistsSingleBehaviorEvent() throws Exception {
        String eventId = eventId("relay");
        List<String> published = new CopyOnWriteArrayList<>();
        mqProvider.subscribe(BehaviorIngestPublisher.TOPIC_EVENTS, "bkf7-it-" + eventId,
                (topic, body, headers) -> published.add(body));

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                publisher.publish(outcome(eventId, "task-123")));

        int first = relay.publishPending(10);
        int second = relay.publishPending(10);

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(published).hasSize(1);

        JsonNode envelope = objectMapper.readTree(published.get(0));
        JsonNode event = envelope.get("events").get(0);
        assertThat(envelope.get("tenantId").asLong()).isEqualTo(TENANT);
        assertThat(envelope.get("userId").asLong()).isEqualTo(USER);
        assertThat(event.get("eventId").asText()).isEqualTo(eventId);
        assertThat(event.get("eventName").asText()).isEqualTo("agent.task.completed");
        assertThat(event.get("eventCategory").asText()).isEqualTo("business_outcome");
        assertThat(event.get("source").asText()).isEqualTo("server");

        assertThat(jdbc.queryForObject(
                "SELECT status FROM ab_behavior_outcome_outbox WHERE tenant_id=? AND event_id=?",
                String.class, TENANT, eventId)).isEqualTo("published");
        assertThat(jdbc.queryForObject(
                "SELECT count(1) FROM ab_behavior_event WHERE tenant_id=? AND event_id=?",
                Integer.class, TENANT, eventId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT props->>'status' FROM ab_behavior_event WHERE tenant_id=? AND event_id=?",
                String.class, TENANT, eventId)).isEqualTo("completed");
    }

    private BehaviorOutcomeEvent outcome(String eventId, String businessKey) {
        return BehaviorOutcomeEvent.builder()
                .tenantId(TENANT)
                .userId(USER)
                .eventId(eventId)
                .eventName("agent.task.completed")
                .occurredAt(Instant.now())
                .targetType("agent_task")
                .targetKey(businessKey)
                .traceId("trace-bkf7")
                .sourceSpanId("span-bkf7")
                .runId("run-bkf7")
                .props(Map.of("status", "completed", "businessKey", businessKey))
                .build();
    }

    private String eventId(String prefix) {
        return "bkf7-" + prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 18);
    }

    private int count(String table, String eventId, String businessKey) {
        Integer value = jdbc.queryForObject(
                "SELECT count(1) FROM " + table + " WHERE tenant_id=? AND business_key=?",
                Integer.class, TENANT, businessKey);
        return value == null ? 0 : value;
    }

    private int outboxCount(String eventId) {
        Integer value = jdbc.queryForObject(
                "SELECT count(1) FROM ab_behavior_outcome_outbox WHERE tenant_id=? AND event_id=?",
                Integer.class, TENANT, eventId);
        return value == null ? 0 : value;
    }

    private void cleanup() {
        jdbc.update("DELETE FROM ab_behavior_event WHERE tenant_id = ?", TENANT);
        jdbc.update("DELETE FROM ab_behavior_outcome_outbox WHERE tenant_id = ?", TENANT);
        jdbc.update("DELETE FROM ab_behavior_outcome_business_it WHERE tenant_id = ?", TENANT);
    }

    private void ensureTables() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS ab_behavior_outcome_business_it (
                id BIGSERIAL PRIMARY KEY,
                tenant_id BIGINT NOT NULL,
                business_key VARCHAR(80) NOT NULL,
                state VARCHAR(32) NOT NULL,
                created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
            )""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS ab_behavior_outcome_outbox (
                id BIGSERIAL PRIMARY KEY,
                tenant_id BIGINT NOT NULL,
                event_id VARCHAR(40) NOT NULL,
                user_id BIGINT,
                event_name VARCHAR(120) NOT NULL,
                target_type VARCHAR(64),
                target_key VARCHAR(120),
                payload JSONB NOT NULL,
                trace_id VARCHAR(36),
                source_span_id VARCHAR(36),
                run_id VARCHAR(64),
                occurred_at TIMESTAMPTZ NOT NULL,
                status VARCHAR(24) NOT NULL DEFAULT 'pending',
                attempts INTEGER NOT NULL DEFAULT 0,
                next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                last_error TEXT,
                published_at TIMESTAMPTZ,
                created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
            )""");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_ab_behavior_outcome_outbox_tenant_event "
                + "ON ab_behavior_outcome_outbox (tenant_id, event_id)");
        jdbc.execute("ALTER TABLE ab_behavior_outcome_outbox ADD COLUMN IF NOT EXISTS interaction_id VARCHAR(64)");
        jdbc.execute("ALTER TABLE ab_behavior_outcome_outbox ADD COLUMN IF NOT EXISTS caused_by_event_id VARCHAR(40)");
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
    }
}
