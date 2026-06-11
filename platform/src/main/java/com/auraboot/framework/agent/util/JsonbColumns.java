package com.auraboot.framework.agent.util;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * Canonical extractor for a JSONB column value read via a <em>generic</em> query
 * ({@code DynamicDataMapper.selectByQuery} / {@code selectByQueryWithoutTenant} /
 * {@code JdbcTemplate}).
 *
 * <p>Such reads apply <strong>no entity type-handler</strong>, so the PostgreSQL
 * driver returns a {@code PGobject} for a JSONB column — not a String, not a
 * parsed Map/List. Code that only handled {@code instanceof String} (then fell
 * back to {@code objectMapper.writeValueAsString(raw)} or {@code convertValue} or
 * a {@code (String)} cast) silently mis-read it: {@code writeValueAsString} /
 * {@code convertValue} serialize the PGobject <em>wrapper</em>
 * ({@code {"type":"jsonb","value":"…"}}) and a cast throws ClassCastException.
 *
 * <p>The driver guarantees only {@code PGobject.toString()} (which returns the
 * JSON text), so that is what we use — mirroring the long-standing inline pattern
 * in {@code StepLoopService.parseExecutionConfig}. This util centralizes it so
 * the PGobject-on-generic-JSONB-read gap stops recurring (it has bitten
 * publishTaskCompleted, PlanService, CapabilityRouter, CapabilityMappingSupport,
 * AgentHintEnhancer, ShadowRunScheduler, …).
 */
public final class JsonbColumns {

    private JsonbColumns() {
    }

    /**
     * Extract the JSON text from a value read for a JSONB column. Handles a
     * String, a driver PGobject (via toString), and an already-parsed Map/List
     * (re-serialized with the given mapper). Returns null for null / blank /
     * literal {@code "null"}.
     */
    public static String toJsonText(Object raw, ObjectMapper mapper) {
        if (raw == null) {
            return null;
        }
        String json;
        if (raw instanceof String s) {
            json = s;
        } else if (raw instanceof Map || raw instanceof List) {
            try {
                json = mapper.writeValueAsString(raw);
            } catch (Exception e) {
                return null;
            }
        } else {
            // PGobject and other driver wrappers — JDBC only guarantees toString().
            json = raw.toString();
        }
        if (json == null || json.isBlank() || "null".equals(json.trim())) {
            return null;
        }
        return json;
    }
}
