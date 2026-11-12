package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.util.JsonbColumns;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Immutable definition payload and opt-in publication resolver; legacy active rows are unchanged. */
final class SkillDefinitionSnapshot {
    static final String RESOURCE_TYPE = "agent-skill";
    static final String PUBLICATION = "_skill_publication_v1";
    static final List<String> FIELDS = List.of("skill_code", "skill_name", "skill_description", "skill_level",
            "skill_category", "skill_icon", "skill_version", "skill_tools", "prompt_template", "skill_input_schema",
            "execution_mode", "max_steps", "timeout_sec", "max_retry", "failure_mode", "output_type",
            "output_schema", "render_hint", "actionability", "produced_action_types", "idempotency_mode",
            "step_input_mappings", "execution_config", "guardrails");
    static final Set<String> JSON_FIELDS = Set.of("skill_tools", "skill_input_schema", "output_schema",
            "produced_action_types", "step_input_mappings", "execution_config", "guardrails");
    private SkillDefinitionSnapshot() {}

    static Map<String, Object> jsonObject(Object raw, ObjectMapper json) {
        Object value = jsonValue(raw, json);
        if (!(value instanceof Map<?, ?> map)) return new LinkedHashMap<>();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    static Object jsonValue(Object raw, ObjectMapper json) {
        if (raw == null) return null;
        String text = JsonbColumns.toJsonText(raw, json);
        try { return text == null ? null : json.readValue(text, Object.class); }
        catch (Exception e) { throw new IllegalStateException("Invalid skill JSON definition", e); }
    }

    static Map<String, Object> capture(Map<String, Object> row, ObjectMapper json) {
        Map<String, Object> snapshot = new TreeMap<>();
        for (String field : FIELDS) {
            Object value = row.get(field);
            if (JSON_FIELDS.contains(field)) value = jsonValue(value, json);
            if ("execution_config".equals(field)) {
                Map<String, Object> config = jsonObject(value, json);
                config.remove(PUBLICATION);
                value = config;
            }
            snapshot.put(field, value);
        }
        return snapshot;
    }

    static String hash(Map<String, Object> snapshot, ObjectMapper json) {
        try {
            // Canonicalize every object recursively, including nested input/provider schemas.
            byte[] bytes = json.writeValueAsString(sorted(snapshot)).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) { throw new IllegalStateException("Cannot fingerprint skill definition", e); }
    }
    private static Object sorted(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> ordered = new TreeMap<>();
            map.forEach((key, item) -> ordered.put(String.valueOf(key), sorted(item)));
            return ordered;
        }
        if (value instanceof List<?> list) return list.stream().map(SkillDefinitionSnapshot::sorted).toList();
        return value;
    }

    static Map<String, Object> publication(Map<String, Object> row, ObjectMapper json) {
        Map<String, Object> config = jsonObject(row.get("execution_config"), json);
        if (!config.containsKey(PUBLICATION)) return Map.of();
        Map<String, Object> pointer = jsonObject(config.get(PUBLICATION), json);
        if (!(pointer.get("versionPid") instanceof String pid) || pid.isBlank()
                || !(pointer.get("hash") instanceof String hash) || !hash.matches("[a-f0-9]{64}"))
            throw new IllegalStateException("Invalid skill publication pointer");
        return pointer;
    }

    static Map<String, Object> resolve(Map<String, Object> row, DynamicDataMapper mapper, ObjectMapper json) {
        Map<String, Object> publication = publication(row, json);
        if (publication.isEmpty()) return row;
        String versionPid = Objects.toString(publication.get("versionPid"), "");
        List<Map<String, Object>> versions = mapper.selectByQueryWithoutTenant(
                "SELECT schema_snapshot FROM ab_design_version_history WHERE tenant_id = #{params.tenantId} "
                + "AND pid = #{params.versionPid} AND resource_type = #{params.resourceType} "
                + "AND resource_id = #{params.resourceId}",
                Map.of("tenantId", row.get("tenant_id"), "versionPid", versionPid,
                        "resourceType", RESOURCE_TYPE, "resourceId", row.get("pid")));
        if (versions.size() != 1) throw new IllegalStateException("Published skill version unavailable");
        Map<String, Object> snapshot = jsonObject(versions.get(0).get("schema_snapshot"), json);
        if (!hash(snapshot, json).equals(publication.get("hash")))
            throw new IllegalStateException("Published skill fingerprint mismatch");
        if (!Objects.equals(row.get("skill_code"), snapshot.get("skill_code")))
            throw new IllegalStateException("Published skill code changed; restore the original code");
        Map<String, Object> resolved = new LinkedHashMap<>(row);
        resolved.putAll(snapshot);
        resolved.put("skill_status", "active");
        resolved.put("skill_version_pid", versionPid);
        resolved.put("skill_version_hash", publication.get("hash"));
        resolved.put("skill_version_label", publication.get("version"));
        return resolved;
    }
}
