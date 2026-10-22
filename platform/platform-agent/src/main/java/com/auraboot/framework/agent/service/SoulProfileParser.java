package com.auraboot.framework.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * Utility for parsing the agent soul_profile JSONB column.
 * <p>
 * soul_profile is stored as JSONB in ab_agent_definition and may contain:
 * <ul>
 *   <li>persona    — short identity description</li>
 *   <li>values     — list of core values (e.g. ["efficiency", "compliance-first"])</li>
 *   <li>tone       — tone keyword (professional / formal / friendly)</li>
 *   <li>tone_description — human-readable style guidance</li>
 *   <li>boundaries — list of hard behavioural constraints</li>
 *   <li>greeting   — opening message shown to the user</li>
 *   <li>language_preference — default response language (e.g. "zh-CN")</li>
 * </ul>
 * <p>
 * Backward-compatibility: if the stored value is plain text (not valid JSON) the
 * entire string is treated as the persona field and all other keys are absent.
 */
public final class SoulProfileParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SoulProfileParser() {
        // utility class — no instances
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Parse soul_profile into a typed map.
     * Returns an empty map when {@code soulProfile} is null or blank.
     * Falls back to {@code {"persona": <original text>}} when the value is not valid JSON.
     *
     * @param soulProfile raw value from ab_agent_definition.soul_profile (may be JSON or plain text)
     * @return parsed map — never null
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parse(String soulProfile) {
        if (soulProfile == null || soulProfile.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = MAPPER.readValue(soulProfile.trim(), Map.class);
            return parsed != null ? parsed : Map.of();
        } catch (Exception e) {
            // Fallback: treat entire string as persona description
            return Map.of("persona", soulProfile.trim());
        }
    }

    /**
     * Convenience: extract persona string.
     */
    public static String getPersona(Map<String, Object> profile) {
        Object val = profile.get("persona");
        return val instanceof String s ? s : null;
    }

    /**
     * Convenience: extract values list as strings.
     */
    @SuppressWarnings("unchecked")
    public static List<String> getValues(Map<String, Object> profile) {
        Object val = profile.get("values");
        if (val instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        }
        return List.of();
    }

    /**
     * Convenience: extract tone keyword.
     */
    public static String getTone(Map<String, Object> profile) {
        Object val = profile.get("tone");
        return val instanceof String s ? s : null;
    }

    /**
     * Convenience: extract tone_description string.
     */
    public static String getToneDescription(Map<String, Object> profile) {
        Object val = profile.get("tone_description");
        return val instanceof String s ? s : null;
    }

    /**
     * Convenience: extract boundaries as strings.
     */
    @SuppressWarnings("unchecked")
    public static List<String> getBoundaries(Map<String, Object> profile) {
        Object val = profile.get("boundaries");
        if (val instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        }
        return List.of();
    }

    /**
     * Convenience: extract greeting string.
     */
    public static String getGreeting(Map<String, Object> profile) {
        Object val = profile.get("greeting");
        return val instanceof String s ? s : null;
    }

    /**
     * Convenience: extract language_preference string (e.g. "zh-CN").
     */
    public static String getLanguagePreference(Map<String, Object> profile) {
        Object val = profile.get("language_preference");
        return val instanceof String s ? s : null;
    }

    // =========================================================================
    // Prompt fragment builder
    // =========================================================================

    /**
     * Render soul_profile as a system prompt section.
     * Returns an empty string when the profile is empty.
     */
    public static String toPromptSection(Map<String, Object> profile) {
        if (profile.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("## Agent Soul Profile\n");

        String persona = getPersona(profile);
        if (persona != null) {
            sb.append("**Persona**: ").append(persona).append("\n");
        }

        List<String> values = getValues(profile);
        if (!values.isEmpty()) {
            sb.append("**Core Values**: ").append(String.join(", ", values)).append("\n");
        }

        String toneDesc = getToneDescription(profile);
        String tone = getTone(profile);
        if (toneDesc != null) {
            sb.append("**Communication Style**: ").append(toneDesc).append("\n");
        } else if (tone != null) {
            sb.append("**Tone**: ").append(tone).append("\n");
        }

        List<String> boundaries = getBoundaries(profile);
        if (!boundaries.isEmpty()) {
            sb.append("\n## Behavioural Boundaries (MUST respect)\n");
            for (String b : boundaries) {
                sb.append("- ").append(b).append("\n");
            }
        }

        return sb.toString();
    }
}
