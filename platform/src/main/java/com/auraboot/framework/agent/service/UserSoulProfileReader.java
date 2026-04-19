package com.auraboot.framework.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static com.auraboot.framework.agent.service.UserSoulProfileFieldPaths.*;

/**
 * User Soul Profile grounding reader (plan §5.5 / Phase 3 / PR-77).
 *
 * <p>Synchronous, read-only. Called by {@code AgentRunService.loadMemorySection}
 * and the AuraBot chat-path prompt assembly ({@code AuraBotChatService.buildSystemPrompt})
 * so the LLM system prompt gains a short, derived "about this user" section
 * before every memory-augmented turn.
 *
 * <p>Guarantees:
 * <ul>
 *   <li>Returns {@link Optional#empty()} — never {@code null}, never throws —
 *       for every benign "no profile" case: missing tenant/user, no ACTIVE row,
 *       row hidden via {@code hidden_at}, malformed JSON.</li>
 *   <li>Throws a {@link RuntimeException} only on actual infrastructure errors
 *       (DB connection failure). Prompt-assembly callers MAY catch at their
 *       own discretion — mirrors the existing pattern in {@code loadMemorySection}.</li>
 *   <li>Rendered prompt block is length-bounded ({@link #MAX_PROMPT_CHARS}) to
 *       protect the LLM context window. Verbose fields are truncated with
 *       an ellipsis rather than dropped.</li>
 *   <li>Honours user edits stored in {@code edited_fields}: hide wins over
 *       override wins over raw (see {@link UserSoulProfileFieldPaths}).</li>
 * </ul>
 */
@Slf4j
@Service
public class UserSoulProfileReader {

    /** Hard cap on rendered prompt text; keeps injection negligible vs the memory section. */
    public static final int MAX_PROMPT_CHARS = 500;

    /** Appended when the profile has {@code stale_flagged_at IS NOT NULL}. */
    public static final String STALE_WARNING_LINE =
            "\u26A0\uFE0F This profile may be outdated; prefer recent memories over the profile when they conflict.";

    private static final String SQL_LOAD_ACTIVE =
            "SELECT pid, version, profile, edited_fields, stale_flagged_at " +
            "FROM ab_agent_user_soul_profile " +
            "WHERE tenant_id = ? AND user_id = ? " +
            "  AND status = 'ACTIVE' AND hidden_at IS NULL " +
            "LIMIT 1";

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);

    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /** Phase 2 owns {@code UserSoulProfileMetrics}; we emit a narrow read counter directly. */
    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    public UserSoulProfileReader(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Load the ACTIVE User Soul Profile for {@code (tenantId, userId)} and
     * render it as a ready-to-prepend LLM prompt block.
     *
     * @param tenantId tenant; {@code null} → empty (system/cron path)
     * @param userId   user id (string form); {@code null}/blank → empty
     * @return {@link ProfileSection} when an ACTIVE visible profile exists,
     *         otherwise {@link Optional#empty()}
     */
    public Optional<ProfileSection> loadForGrounding(Long tenantId, String userId) {
        if (tenantId == null || userId == null || userId.isBlank()) {
            return Optional.empty();
        }

        Map<String, Object> row;
        try {
            row = jdbcTemplate.queryForMap(SQL_LOAD_ACTIVE, tenantId, userId);
        } catch (EmptyResultDataAccessException none) {
            return Optional.empty();
        }

        Map<String, Object> profile;
        Map<String, Object> editedFields;
        try {
            profile = readJson(row.get("profile"));
            editedFields = readJson(row.get("edited_fields"));
        } catch (IllegalStateException corrupt) {
            // Corrupt payload — skip grounding entirely rather than risk leaking
            // a field the user previously hid. Ops sees WARN with pid.
            log.warn("User Soul Profile row has corrupt JSON, skipping grounding: pid={} err={}",
                    row.get("pid"), corrupt.getMessage());
            return Optional.empty();
        }
        if (profile.isEmpty()) {
            log.warn("User Soul Profile row has empty 'profile' JSON: pid={}", row.get("pid"));
            return Optional.empty();
        }
        boolean stale = row.get("stale_flagged_at") != null;
        int version = numberAsInt(row.get("version"), 1);
        String pid = (String) row.get("pid");

        Map<String, Object> effective = applyEdits(profile, editedFields);
        String rendered = renderPromptSection(effective, version, derivedDate(row), stale);

        if (meterRegistry != null) {
            meterRegistry.counter("auraboot_user_soul_profile_read_total",
                    "tenant", tenantId.toString()).increment();
        }

        return Optional.of(new ProfileSection(pid, version, rendered, stale, profile));
    }

    // =========================================================================
    // Edit-merge (hide > override > raw)
    // =========================================================================

    @SuppressWarnings("unchecked")
    Map<String, Object> applyEdits(Map<String, Object> rawProfile,
                                   Map<String, Object> editedFields) {
        if (editedFields == null || editedFields.isEmpty()) {
            return rawProfile;
        }
        // Deep-copy top-level structure; we only mutate the level we touch
        Map<String, Object> result = new LinkedHashMap<>(rawProfile);

        for (Map.Entry<String, Object> edit : editedFields.entrySet()) {
            String path = edit.getKey();
            Object directive = edit.getValue();

            if (isHideDirective(directive)) {
                removeByPath(result, path);
                continue;
            }
            String override = extractOverrideText(directive);
            if (override != null) {
                overrideTextAtPath(result, path, override);
            }
            // Pinned-only markers do not change the rendered text
        }
        return result;
    }

    private boolean isHideDirective(Object directive) {
        if (EDIT_HIDDEN.equals(directive)) return true;
        if (directive instanceof Map<?, ?> m) {
            return Boolean.TRUE.equals(m.get(EDIT_HIDDEN))
                    || EDIT_HIDDEN.equals(m.get("status"));
        }
        return false;
    }

    private String extractOverrideText(Object directive) {
        if (directive instanceof Map<?, ?> m) {
            Object override = m.get(EDIT_OVERRIDE_TEXT);
            return override instanceof String s && !s.isBlank() ? s : null;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void removeByPath(Map<String, Object> target, String path) {
        String[] parts = path.split("\\.");
        Map<String, Object> cursor = target;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = cursor.get(parts[i]);
            if (!(next instanceof Map)) return;
            cursor = new LinkedHashMap<>((Map<String, Object>) next);
            target.put(parts[i], cursor);
            target = cursor;
        }
        cursor.remove(parts[parts.length - 1]);
    }

    @SuppressWarnings("unchecked")
    private void overrideTextAtPath(Map<String, Object> target, String path, String newText) {
        String[] parts = path.split("\\.");
        Map<String, Object> cursor = target;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = cursor.get(parts[i]);
            if (!(next instanceof Map)) return;
            Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) next);
            cursor.put(parts[i], copy);
            cursor = copy;
        }
        Object leaf = cursor.get(parts[parts.length - 1]);
        if (leaf instanceof Map<?, ?> leafMap) {
            Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) leafMap);
            copy.put("text", newText);
            cursor.put(parts[parts.length - 1], copy);
        } else {
            // Scalar text field (e.g. boundaries may be a string in some edits)
            cursor.put(parts[parts.length - 1], Map.of("text", newText));
        }
    }

    // =========================================================================
    // Prompt rendering
    // =========================================================================

    String renderPromptSection(Map<String, Object> profile, int version,
                               String derivedDate, boolean stale) {
        StringBuilder sb = new StringBuilder();
        sb.append("## About this user (User Soul Profile v").append(version)
                .append(", derived ").append(derivedDate).append(")\n");

        appendLine(sb, "Persona", fieldText(profile, PERSONA));
        appendLine(sb, "Communication", nestedText(profile, "preferences", "communication_style"));
        appendLine(sb, "Domain vocabulary", nestedText(profile, "preferences", "domain_vocabulary"));
        appendLine(sb, "Working hours", nestedText(profile, "preferences", "working_hours"));
        appendLine(sb, "Recurring tasks", summariseRecurringActions(profile));
        appendLine(sb, "Expertise", summariseExpertise(profile));
        appendLine(sb, "Boundaries", fieldText(profile, BOUNDARIES));
        appendLine(sb, "Language preference", scalar(profile, LANGUAGE));

        sb.append("\nNote: the user can see + edit this profile via /aurabot/my-profile.\n");
        sb.append("When asked about it, direct them there. Do not quote verbatim.\n");

        // Stale warning is load-bearing for LLM behaviour — ensure it survives
        // any truncation by appending AFTER the cap is applied. Reserve space
        // for it so the final string still honours MAX_PROMPT_CHARS.
        if (stale) {
            String staleSuffix = STALE_WARNING_LINE + "\n";
            String body = truncate(sb.toString(), MAX_PROMPT_CHARS - staleSuffix.length());
            return body + staleSuffix;
        }
        return truncate(sb.toString(), MAX_PROMPT_CHARS);
    }

    private void appendLine(StringBuilder sb, String label, String value) {
        if (value == null || value.isBlank()) return;
        sb.append("- ").append(label).append(": ").append(value).append("\n");
    }

    @SuppressWarnings("unchecked")
    private String fieldText(Map<String, Object> profile, String key) {
        Object val = profile.get(key);
        if (val instanceof Map<?, ?> m) {
            Object text = ((Map<String, Object>) m).get("text");
            return text instanceof String s ? s : stringifyScalarList(text);
        }
        return val instanceof String s ? s : null;
    }

    @SuppressWarnings("unchecked")
    private String nestedText(Map<String, Object> profile, String outer, String inner) {
        Object outerVal = profile.get(outer);
        if (!(outerVal instanceof Map)) return null;
        Object innerVal = ((Map<String, Object>) outerVal).get(inner);
        if (innerVal instanceof Map<?, ?> m) {
            Object text = ((Map<String, Object>) m).get("text");
            return text instanceof String s ? s : stringifyScalarList(text);
        }
        return innerVal instanceof String s ? s : null;
    }

    @SuppressWarnings("unchecked")
    private String scalar(Map<String, Object> profile, String key) {
        Object val = profile.get(key);
        return val instanceof String s ? s : (val != null ? val.toString() : null);
    }

    @SuppressWarnings("unchecked")
    private String summariseRecurringActions(Map<String, Object> profile) {
        Object habits = profile.get("habits");
        if (!(habits instanceof Map)) return null;
        Object rec = ((Map<String, Object>) habits).get("recurring_actions");
        if (!(rec instanceof List<?> list) || list.isEmpty()) return null;

        List<String> parts = new ArrayList<>();
        for (Object item : list) {
            if (parts.size() >= 3) break;
            if (item instanceof Map<?, ?> m) {
                Object pattern = m.get("pattern");
                Object freq = m.get("frequency");
                if (pattern != null) {
                    parts.add(freq != null ? pattern + " (" + freq + ")" : pattern.toString());
                }
            }
        }
        return parts.isEmpty() ? null : String.join("; ", parts);
    }

    @SuppressWarnings("unchecked")
    private String summariseExpertise(Map<String, Object> profile) {
        Object expertise = profile.get("expertise");
        if (!(expertise instanceof Map)) return null;
        Object domains = ((Map<String, Object>) expertise).get("domains");
        if (!(domains instanceof List<?> list) || list.isEmpty()) return null;

        List<String> names = new ArrayList<>();
        for (Object item : list) {
            if (names.size() >= 3) break;
            if (item instanceof Map<?, ?> m && m.get("name") instanceof String n) {
                names.add(n);
            } else if (item instanceof String s) {
                names.add(s);
            }
        }
        return names.isEmpty() ? null : String.join(", ", names);
    }

    private String stringifyScalarList(Object value) {
        if (value instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof String s) parts.add(s);
                else if (item != null) parts.add(item.toString());
            }
            return parts.isEmpty() ? null : String.join(", ", parts);
        }
        return null;
    }

    /**
     * Bounded-length truncation. When the rendered block exceeds
     * {@link #MAX_PROMPT_CHARS}, retain the header + meta footer and truncate
     * the longest inline bullet to fit, appending an ellipsis so the LLM
     * understands it's partial. The goal is to never let one verbose field
     * crowd out useful lines.
     */
    static String truncate(String rendered, int cap) {
        if (rendered.length() <= cap) return rendered;

        // Progressive fixed-length trim of bullet lines from the top
        String[] lines = rendered.split("\n", -1);
        StringBuilder out = new StringBuilder();
        int remaining = cap;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            boolean last = (i == lines.length - 1);
            int need = line.length() + (last ? 0 : 1);
            if (remaining >= need) {
                out.append(line);
                if (!last) out.append('\n');
                remaining -= need;
            } else if (remaining > 4) {
                // Truncate this bullet with ellipsis
                int keep = Math.max(0, remaining - 2);
                out.append(line, 0, Math.min(keep, line.length())).append("\u2026");
                break;
            } else {
                break;
            }
        }
        return out.toString();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(Object column) {
        if (column == null) return Collections.emptyMap();
        String raw;
        if (column instanceof String s) {
            raw = s;
        } else {
            raw = column.toString();
        }
        if (raw.isBlank()) return Collections.emptyMap();
        try {
            Map<String, Object> parsed = objectMapper.readValue(raw, MAP_TYPE);
            return parsed != null ? parsed : Collections.emptyMap();
        } catch (Exception e) {
            // Corrupt JSONB must NOT fall through to "no edits" — that would
            // leak fields the user previously hid. Fail loudly; grounding
            // skips this row via Optional.empty upstream.
            throw new IllegalStateException("Corrupt JSONB column in ab_agent_user_soul_profile", e);
        }
    }

    private int numberAsInt(Object val, int defaultVal) {
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return defaultVal;
    }

    private String derivedDate(Map<String, Object> row) {
        Object v = row.get("activated_at");
        if (v == null) v = row.get("created_at");
        if (v instanceof Timestamp ts) {
            return ts.toInstant().atOffset(ZoneOffset.UTC).toLocalDate().format(DATE_FMT);
        }
        if (v instanceof java.time.OffsetDateTime odt) {
            return odt.toLocalDate().format(DATE_FMT);
        }
        if (v instanceof java.time.LocalDateTime ldt) {
            return ldt.toLocalDate().format(DATE_FMT);
        }
        return java.time.LocalDate.now(ZoneOffset.UTC).format(DATE_FMT);
    }

    // =========================================================================
    // DTO
    // =========================================================================

    /**
     * Output record of {@link #loadForGrounding(Long, String)}.
     *
     * @param pid                 row PID (for audit/debug)
     * @param version             profile version
     * @param renderedPromptText  compact block intended to prepend to the
     *                            LLM system prompt; always &le; {@link #MAX_PROMPT_CHARS}
     * @param stale               true iff {@code stale_flagged_at IS NOT NULL}
     * @param rawProfile          the raw JSON map prior to edit-merge —
     *                            exposed for debugging / metrics, NOT normally injected
     */
    public record ProfileSection(
            String pid,
            int version,
            String renderedPromptText,
            boolean stale,
            Map<String, Object> rawProfile) {
    }
}
