package com.auraboot.framework.p1demo;

import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the P1 vertical-slice annotation repository.
 *
 * The acp_ai_annotation table is intentionally NOT in platform/schema.sql
 * (per design v2 §6.2 — P2' will add it through the proper governed model).
 * This test recreates the table once per run via @BeforeAll. Drop this hook
 * when the table moves into schema.sql.
 */
class AcpAiAnnotationRepositoryIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AcpAiAnnotationRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    private static final String TARGET_MODEL = "wd_leave_request";

    /** Random Long id generator for test isolation; mirrors the BIGINT FK columns. */
    private static long randomId() {
        return ThreadLocalRandom.current().nextLong(1_000_000L, Long.MAX_VALUE / 2);
    }

    @BeforeAll
    static void ensureTableExists() {
        // BaseIntegrationTest sets up Spring context including the JdbcTemplate
        // bean that is reused below; the table itself is created lazily via
        // @BeforeEach to avoid coupling to context-init order.
    }

    @BeforeEach
    void createTableIfMissing() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS acp_ai_annotation (
                    id                       BIGSERIAL PRIMARY KEY,
                    tenant_id                BIGINT NOT NULL,
                    target_model_code        VARCHAR(64) NOT NULL,
                    target_id                BIGINT NOT NULL,
                    turn_id                  VARCHAR(64) NOT NULL,
                    grounding_input          TEXT,
                    grounding_intent         JSONB,
                    grounding_at             TIMESTAMP,
                    planning_steps           JSONB,
                    planning_recommendation  TEXT,
                    planning_at              TIMESTAMP,
                    executing_started_at     TIMESTAMP,
                    completed_at             TIMESTAMP,
                    total_tokens             BIGINT NOT NULL DEFAULT 0,
                    total_dollars            DECIMAL(12,6) NOT NULL DEFAULT 0,
                    safety_triggers          JSONB NOT NULL DEFAULT '[]'::jsonb,
                    final_status             VARCHAR(32),
                    created_at               TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at               TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        // Each test starts with a clean tenant slice for the target model.
    }

    @Test
    void insertGrounding_persistsAllFields_andFindByTargetDeserializesJsonb() {
        Long tenantId = randomId();
        Long targetId = randomId();
        String turnId = "turn-" + randomId();
        Map<String, Object> intent = Map.of(
                "wd_req_type", "annual",
                "wd_req_days", 2,
                "wd_req_reason", "family matter");

        Long annotationId = repository.insertGrounding(
                tenantId, TARGET_MODEL, targetId, turnId, "下周请 2 天年假", intent);

        assertThat(annotationId).isNotNull().isPositive();

        Map<String, Object> found = repository.findByTarget(tenantId, TARGET_MODEL, targetId);
        assertThat(found).isNotNull();
        assertThat(found.get("turn_id")).isEqualTo(turnId);
        assertThat(found.get("grounding_input")).isEqualTo("下周请 2 天年假");

        // JSONB column must come back as a real Map, NOT a PGobject toString.
        Object groundingIntent = found.get("grounding_intent");
        assertThat(groundingIntent).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> intentMap = (Map<String, Object>) groundingIntent;
        assertThat(intentMap)
                .containsEntry("wd_req_type", "annual")
                .containsEntry("wd_req_days", 2);

        // safety_triggers default JSONB should also deserialize to a List.
        assertThat(found.get("safety_triggers")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Object> triggers = (List<Object>) found.get("safety_triggers");
        assertThat(triggers).isEmpty();
    }

    @Test
    void multipleInsertsForSameTarget_findReturnsMostRecent() throws Exception {
        Long tenantId = randomId();
        Long targetId = randomId();

        repository.insertGrounding(tenantId, TARGET_MODEL, targetId, "turn-1",
                "first attempt", Map.of("wd_req_days", 1));
        Thread.sleep(20); // ensure distinct created_at on fast PG
        Long second = repository.insertGrounding(tenantId, TARGET_MODEL, targetId, "turn-2",
                "second attempt", Map.of("wd_req_days", 5));

        Map<String, Object> found = repository.findByTarget(tenantId, TARGET_MODEL, targetId);
        assertThat(found.get("id")).isEqualTo(second);
        assertThat(found.get("turn_id")).isEqualTo("turn-2");
    }

    @Test
    void recordSafetyTrigger_overwritesExistingTriggers_andSurvivesJsonbRoundtrip() {
        Long tenantId = randomId();
        Long targetId = randomId();
        Long annotationId = repository.insertGrounding(tenantId, TARGET_MODEL, targetId,
                "turn-1", "input", Map.of("wd_req_days", 7));

        repository.recordSafetyTrigger(annotationId, List.of("wd_days_over_5"));

        Map<String, Object> found = repository.findByTarget(tenantId, TARGET_MODEL, targetId);
        @SuppressWarnings("unchecked")
        List<String> triggers = (List<String>) found.get("safety_triggers");
        assertThat(triggers).containsExactly("wd_days_over_5");
    }

    @Test
    void findByTarget_unknownTarget_returnsNull() {
        Long tenantId = randomId();
        Map<String, Object> found = repository.findByTarget(tenantId, TARGET_MODEL, 999_999L);
        assertThat(found).isNull();
    }

    @Test
    void findByTarget_isTenantScoped() {
        Long tenantA = randomId();
        Long tenantB = randomId();
        Long targetId = randomId();
        repository.insertGrounding(tenantA, TARGET_MODEL, targetId, "turn-A",
                "tenant A input", Map.of("wd_req_days", 1));

        assertThat(repository.findByTarget(tenantA, TARGET_MODEL, targetId)).isNotNull();
        assertThat(repository.findByTarget(tenantB, TARGET_MODEL, targetId)).isNull();
    }
}
