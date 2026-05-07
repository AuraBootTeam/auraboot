package com.auraboot.framework.p1demo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.util.PGobject;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private static final Set<String> JSONB_COLUMNS =
            Set.of("grounding_intent", "planning_steps", "safety_triggers");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /**
     * Insert a fresh grounding annotation row. Each AI fill call creates a new
     * row with a new turn_id — there is intentionally NO upsert on
     * (target, turn_id) because every fill is its own grounding event.
     * findByTarget returns the most-recent row by created_at.
     */
    public Long insertGrounding(Long tenantId, String targetModelCode, Long targetId,
                                 String turnId, String groundingInput, Map<String, Object> intent) {
        String intentJson = writeJson(intent);
        Timestamp now = Timestamp.from(Instant.now());
        return jdbc.queryForObject("""
                INSERT INTO acp_ai_annotation
                    (tenant_id, target_model_code, target_id, turn_id,
                     grounding_input, grounding_intent, grounding_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                RETURNING id
                """,
                Long.class,
                tenantId, targetModelCode, targetId, turnId,
                groundingInput, intentJson, now, now);
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
            Map<String, Object> raw = jdbc.queryForMap("""
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
            return deserializeJsonbColumns(raw);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * JdbcTemplate returns JSONB columns as PGobject; Spring serializes those
     * via toString() which produces a doubly-escaped string for the frontend.
     * Re-parse them into real Maps / Lists so JSON serialization is correct.
     */
    private Map<String, Object> deserializeJsonbColumns(Map<String, Object> row) {
        Map<String, Object> out = new HashMap<>(row);
        for (String col : JSONB_COLUMNS) {
            Object value = out.get(col);
            if (value instanceof PGobject pg && pg.getValue() != null) {
                try {
                    out.put(col, objectMapper.readValue(pg.getValue(),
                            new TypeReference<Object>() {}));
                } catch (Exception e) {
                    log.warn("Failed to deserialize JSONB column {}: {}", col, pg.getValue());
                    out.put(col, null);
                }
            }
        }
        return out;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize annotation field", e);
        }
    }
}
