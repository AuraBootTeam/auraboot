package com.auraboot.framework.behavior.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.behavior.dto.BehaviorQuarantineReplayResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BehaviorQuarantineReplayIT extends BaseIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private BehaviorQuarantineService service;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void replayValidQuarantine_persistsOneBehaviorRowAndMarksReplayResult() throws Exception {
        long tenantId = getTestTenant().getId();
        long userId = getTestUser().getId();
        String eventId = "replay-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String rawEvent = objectMapper.writeValueAsString(Map.of(
                "eventId", eventId,
                "eventName", "page_view",
                "anonId", "anon-replay-it",
                "props", Map.of("source", "quarantine-it")
        ));
        Long quarantineId = insertQuarantine(eventId, rawEvent, tenantId, userId);

        BehaviorQuarantineReplayResult first = service.replayOne(tenantId, quarantineId);
        BehaviorQuarantineReplayResult second = service.replayOne(tenantId, quarantineId);

        assertThat(first.status()).isEqualTo("replayed");
        assertThat(first.behaviorEventId()).isNotNull();
        assertThat(second.status()).isEqualTo("replayed");
        assertThat(eventCount(eventId, tenantId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT replay_status FROM ab_behavior_quarantine WHERE id=?",
                String.class, quarantineId)).isEqualTo("replayed");
        assertThat(jdbc.queryForObject(
                "SELECT replayed_behavior_event_id FROM ab_behavior_quarantine WHERE id=?",
                Long.class, quarantineId)).isEqualTo(first.behaviorEventId());
        assertThat(jdbc.queryForObject(
                "SELECT props->>'source' FROM ab_behavior_event WHERE tenant_id=? AND event_id=?",
                String.class, tenantId, eventId)).isEqualTo("quarantine-it");
    }

    private Long insertQuarantine(String eventId, String rawEvent, long tenantId, long userId) {
        return jdbc.queryForObject("""
                INSERT INTO ab_behavior_quarantine
                    (tenant_id, user_id, anon_id, event_id, event_name, reason, detail, raw_event)
                VALUES (?, ?, 'anon-replay-it', ?, 'page_view', 'constraint_violation', 'fixed by replay test', ?::jsonb)
                RETURNING id
                """, Long.class, tenantId, userId, eventId, rawEvent);
    }

    private int eventCount(String eventId, long tenantId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(1) FROM ab_behavior_event WHERE tenant_id=? AND event_id=?",
                Integer.class, tenantId, eventId);
        return n == null ? 0 : n;
    }

}
