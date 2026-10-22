package com.auraboot.framework.behavior.service;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.behavior.config.BehaviorQuarantineRetentionProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestPropertySource(properties = {
        "behavior.quarantine.retention.enabled=true",
        "behavior.quarantine.retention.days=30",
        "behavior.quarantine.retention.batch-size=10"
})
class BehaviorQuarantineRetentionJobIT {

    private static final long TENANT = 990_901L;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private BehaviorQuarantineRetentionJob job;
    @Autowired
    private BehaviorQuarantineRetentionProperties properties;

    @BeforeEach
    void setup() {
        ensureTable();
        cleanup();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    void retentionProperties_bindOperationalDefaults() {
        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getDays()).isEqualTo(30);
        assertThat(properties.getBatchSize()).isEqualTo(10);
    }

    @Test
    void cleanupExpiredQuarantineRows_deletesOnlyRowsOlderThanRetention() {
        insertQuarantine("old-retention", Instant.now().minusSeconds(45L * 24L * 3600L));
        insertQuarantine("fresh-retention", Instant.now().minusSeconds(5L * 24L * 3600L));

        int deleted = job.cleanupExpired();

        assertThat(deleted).isEqualTo(1);
        assertThat(countByAnonId("old-retention")).isZero();
        assertThat(countByAnonId("fresh-retention")).isEqualTo(1);
    }

    private void insertQuarantine(String anonId, Instant quarantinedAt) {
        jdbc.update("""
                INSERT INTO ab_behavior_quarantine
                    (tenant_id, anon_id, event_id, event_name, reason, detail, raw_event, quarantined_at)
                VALUES (?, ?, ?, 'page_view', 'constraint_violation', 'retention test', '{}'::jsonb, ?)
                """, TENANT, anonId, anonId, Timestamp.from(quarantinedAt));
    }

    private int countByAnonId(String anonId) {
        Integer n = jdbc.queryForObject("""
                SELECT count(1)
                FROM ab_behavior_quarantine
                WHERE tenant_id = ? AND anon_id = ?
                """, Integer.class, TENANT, anonId);
        return n == null ? 0 : n;
    }

    private void cleanup() {
        jdbc.update("DELETE FROM ab_behavior_quarantine WHERE tenant_id = ?", TENANT);
    }

    private void ensureTable() {
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
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_ab_behavior_quarantine_retention "
                + "ON ab_behavior_quarantine (quarantined_at, id)");
    }
}
