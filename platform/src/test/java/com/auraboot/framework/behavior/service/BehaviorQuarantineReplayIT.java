package com.auraboot.framework.behavior.service;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.behavior.dto.BehaviorQuarantineReplayResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
class BehaviorQuarantineReplayIT {

    private static final long TENANT = 990_351L;
    private static final long USER = 990_352L;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private BehaviorQuarantineService service;
    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setup() {
        ensureTables();
        cleanup();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    void replayValidQuarantine_persistsOneBehaviorRowAndMarksReplayResult() throws Exception {
        String eventId = "replay-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String rawEvent = objectMapper.writeValueAsString(Map.of(
                "eventId", eventId,
                "eventName", "page_view",
                "anonId", "anon-replay-it",
                "props", Map.of("source", "quarantine-it")
        ));
        Long quarantineId = insertQuarantine(eventId, rawEvent);

        BehaviorQuarantineReplayResult first = service.replayOne(TENANT, quarantineId);
        BehaviorQuarantineReplayResult second = service.replayOne(TENANT, quarantineId);

        assertThat(first.status()).isEqualTo("replayed");
        assertThat(first.behaviorEventId()).isNotNull();
        assertThat(second.status()).isEqualTo("replayed");
        assertThat(eventCount(eventId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT replay_status FROM ab_behavior_quarantine WHERE id=?",
                String.class, quarantineId)).isEqualTo("replayed");
        assertThat(jdbc.queryForObject(
                "SELECT replayed_behavior_event_id FROM ab_behavior_quarantine WHERE id=?",
                Long.class, quarantineId)).isEqualTo(first.behaviorEventId());
        assertThat(jdbc.queryForObject(
                "SELECT props->>'source' FROM ab_behavior_event WHERE tenant_id=? AND event_id=?",
                String.class, TENANT, eventId)).isEqualTo("quarantine-it");
    }

    private Long insertQuarantine(String eventId, String rawEvent) {
        return jdbc.queryForObject("""
                INSERT INTO ab_behavior_quarantine
                    (tenant_id, user_id, anon_id, event_id, event_name, reason, detail, raw_event)
                VALUES (?, ?, 'anon-replay-it', ?, 'page_view', 'constraint_violation', 'fixed by replay test', ?::jsonb)
                RETURNING id
                """, Long.class, TENANT, USER, eventId, rawEvent);
    }

    private int eventCount(String eventId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(1) FROM ab_behavior_event WHERE tenant_id=? AND event_id=?",
                Integer.class, TENANT, eventId);
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
                replay_status VARCHAR(24) NOT NULL DEFAULT 'pending',
                replay_detail TEXT,
                replayed_behavior_event_id BIGINT,
                replayed_at TIMESTAMPTZ,
                quarantined_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
                created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
            )""");
        jdbc.execute("ALTER TABLE ab_behavior_quarantine ADD COLUMN IF NOT EXISTS replay_status VARCHAR(24) NOT NULL DEFAULT 'pending'");
        jdbc.execute("ALTER TABLE ab_behavior_quarantine ADD COLUMN IF NOT EXISTS replay_detail TEXT");
        jdbc.execute("ALTER TABLE ab_behavior_quarantine ADD COLUMN IF NOT EXISTS replayed_behavior_event_id BIGINT");
        jdbc.execute("ALTER TABLE ab_behavior_quarantine ADD COLUMN IF NOT EXISTS replayed_at TIMESTAMPTZ");
    }
}
