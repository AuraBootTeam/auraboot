package com.auraboot.framework.p1demo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.util.PGobject;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * P1 vertical-slice repository for acp_ai_annotation. Direct JdbcTemplate
 * access — bypasses DynamicDataService because (a) the table is not yet
 * registered as a meta model, (b) P2 platformization will move this to
 * a proper governed service. Do NOT extend this class — replace it.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class AcpAiAnnotationRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public Long upsertGrounding(Long tenantId, String targetModelCode, Long targetId,
                                 String turnId, String groundingInput, Map<String, Object> intent) {
        String intentJson = writeJson(intent);
        return jdbc.queryForObject("""
                INSERT INTO acp_ai_annotation
                    (tenant_id, target_model_code, target_id, turn_id,
                     grounding_input, grounding_intent, grounding_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (tenant_id, target_model_code, target_id, turn_id) DO UPDATE
                SET grounding_input = EXCLUDED.grounding_input,
                    grounding_intent = EXCLUDED.grounding_intent,
                    grounding_at = EXCLUDED.grounding_at,
                    updated_at = EXCLUDED.updated_at
                RETURNING id
                """,
                Long.class,
                tenantId, targetModelCode, targetId, turnId,
                groundingInput, intentJson,
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
    }

    public void recordSafetyTrigger(Long annotationId, List<String> triggerCodes) {
        String triggersJson = writeJson(triggerCodes);
        jdbc.update("""
                UPDATE acp_ai_annotation
                SET safety_triggers = ?::jsonb,
                    updated_at = ?
                WHERE id = ?
                """,
                triggersJson, Timestamp.from(Instant.now()), annotationId);
    }

    public Map<String, Object> findByTarget(Long tenantId, String targetModelCode, Long targetId) {
        try {
            return jdbc.queryForMap("""
                    SELECT id, tenant_id, target_model_code, target_id, turn_id,
                           grounding_input, grounding_intent, grounding_at,
                           planning_steps, planning_recommendation, planning_at,
                           total_tokens, total_dollars, safety_triggers,
                           final_status, created_at, updated_at
                    FROM acp_ai_annotation
                    WHERE tenant_id = ?
                      AND target_model_code = ?
                      AND target_id = ?
                    ORDER BY created_at DESC
                    LIMIT 1
                    """,
                    tenantId, targetModelCode, targetId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize annotation field", e);
        }
    }
}
