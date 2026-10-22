package com.auraboot.framework.aurabot.skill;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Wire-level request envelope for AuraBot skill invocations.
 *
 * <p>{@code @NoArgsConstructor} is required to avoid Bootstrap race deserialisation
 * failures (see AGENTS.md / engineering-gotchas).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class SkillRequest {

    /** Skill name, matches {@link AuraBotSkill#name()}. Required. */
    private String skillName;

    /** Skill-specific parameters; validated against {@link AuraBotSkill#paramsSchema()}. */
    private JsonNode params;

    /** Caller-side runtime context (route, modelCode, selection, etc.). */
    @Builder.Default
    private Map<String, Object> context = new HashMap<>();

    /** Client-generated key; backend dedups within the configured TTL. */
    private String idempotencyKey;

    /** Returned by {@code dry-run}; required by {@code execute} when risk &gt;= MEDIUM. */
    private String previewToken;

    /** Optional confirmation literal for CRITICAL skills (e.g. "DELETE"). */
    private String confirmText;
}
