package com.auraboot.framework.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Single exit point for assembling the system prompt sent to the LLM during agent execution.
 *
 * <p>Section order is FIXED and DETERMINISTIC — same inputs always produce the same output:
 * <ol>
 *   <li>IDENTITY — soul profile (persona, values, tone, boundaries)</li>
 *   <li>USER CONTEXT — communication preferences and role context</li>
 *   <li>SHARED KNOWLEDGE — agent-level memories, importance DESC</li>
 *   <li>USER MEMORY — user-level memories, importance DESC</li>
 *   <li>RECENT CONTEXT — session memories, importance DESC then created_at DESC</li>
 * </ol>
 *
 * <p>The caller is responsible for appending task-specific instructions after the returned string.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentPromptAssemblyService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    // Token budget per section (approximate: chars / 4 ≈ tokens)
    private static final int SOUL_MAX_CHARS         = 2000;  // ~500 tokens
    private static final int USER_PROFILE_MAX_CHARS = 800;   // ~200 tokens
    private static final int AGENT_MEMORY_MAX_CHARS = 4000;  // ~1000 tokens
    private static final int USER_MEMORY_MAX_CHARS  = 2000;  // ~500 tokens
    private static final int SESSION_MEMORY_MAX_CHARS = 1200; // ~300 tokens

    // Maximum rows to load per category before applying char budget
    private static final int MEMORY_ROW_LIMIT = 50;

    /**
     * Assemble the complete system prompt prefix for an agent execution.
     *
     * @param tenantId  tenant scope (never null)
     * @param agentCode the agent's code identifier (never null)
     * @param userId    the requesting user ID; may be null for scheduled/system runs
     * @return assembled prompt string; never null, may be empty if no data found
     */
    public String assemblePrompt(Long tenantId, String agentCode, Long userId) {
        StringBuilder sb = new StringBuilder();

        // 1. SOUL — always first (persona, expertise, style, boundaries, goals)
        String soulSection = loadSoulProfile(tenantId, agentCode);
        appendSection(sb, "IDENTITY", soulSection, SOUL_MAX_CHARS);

        // 2. USER PROFILE — communication preferences and role context
        if (userId != null) {
            String userSection = loadUserProfile(tenantId, userId);
            appendSection(sb, "USER CONTEXT", userSection, USER_PROFILE_MAX_CHARS);
        }

        // 3. AGENT-LEVEL MEMORIES — shared knowledge, importance DESC
        String agentMemories = loadMemoriesByCategory(tenantId, agentCode, "agent", AGENT_MEMORY_MAX_CHARS);
        appendSection(sb, "SHARED KNOWLEDGE", agentMemories, AGENT_MEMORY_MAX_CHARS);

        // 4. USER-LEVEL MEMORIES — personal preferences, importance DESC
        String userMemories = loadMemoriesByCategory(tenantId, agentCode, "user", USER_MEMORY_MAX_CHARS);
        appendSection(sb, "USER MEMORY", userMemories, USER_MEMORY_MAX_CHARS);

        // 5. SESSION MEMORIES — current conversation context, importance DESC then recency DESC
        String sessionMemories = loadMemoriesByCategory(tenantId, agentCode, "session", SESSION_MEMORY_MAX_CHARS);
        appendSection(sb, "RECENT CONTEXT", sessionMemories, SESSION_MEMORY_MAX_CHARS);

        return sb.toString();
    }

    // =========================================================================
    // Section loaders
    // =========================================================================

    /**
     * Load soul profile from {@code ab_agent_definition}.
     * Combines structured columns (personality, expertise, communication_style, boundaries,
     * soul_goals) and the free-text {@code system_prompt} field into a readable narrative.
     */
    private String loadSoulProfile(Long tenantId, String agentCode) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT name, description, personality, expertise, communication_style, "
                + "boundaries, soul_goals, system_prompt "
                + "FROM ab_agent_definition "
                + "WHERE tenant_id = ? AND agent_code = ? "
                + "AND (deleted_flag IS NULL OR deleted_flag = FALSE) "
                + "LIMIT 1",
                tenantId, agentCode);

        if (rows.isEmpty()) {
            log.warn("No agent definition found for agentCode={} tenantId={}", agentCode, tenantId);
            return null;
        }

        Map<String, Object> def = rows.get(0);
        StringBuilder soul = new StringBuilder();

        String name = str(def, "name");
        String description = str(def, "description");
        String personality = str(def, "personality");
        String expertise = str(def, "expertise");
        String commStyle = str(def, "communication_style");
        String goals = str(def, "soul_goals");
        String boundaries = str(def, "boundaries");
        String systemPrompt = str(def, "system_prompt");

        // Opening identity statement
        if (name != null) {
            soul.append("You are ").append(name).append(".");
            if (description != null) soul.append(" ").append(description);
            soul.append("\n");
        }

        if (personality != null)  soul.append("Personality: ").append(personality).append("\n");
        if (expertise != null)    soul.append("Expertise: ").append(expertise).append("\n");
        if (commStyle != null)    soul.append("Communication style: ").append(commStyle).append("\n");
        if (goals != null)        soul.append("Goals: ").append(goals).append("\n");

        if (boundaries != null) {
            soul.append("\nBoundaries (must respect):\n").append(boundaries).append("\n");
        }

        // Append base system_prompt if present (may contain additional instructions)
        if (systemPrompt != null) {
            if (soul.length() > 0) soul.append("\n");
            soul.append(systemPrompt);
        }

        return soul.length() > 0 ? soul.toString() : null;
    }

    /**
     * Load user profile from {@code ab_agent_user_profile}.
     * Formats the JSONB columns (communication, role_context, preferences) and
     * the text column decision_patterns into a human-readable block.
     */
    private String loadUserProfile(Long tenantId, Long userId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT communication, role_context, preferences, decision_patterns "
                + "FROM ab_agent_user_profile "
                + "WHERE tenant_id = ? AND user_id = ? "
                + "AND (deleted_flag IS NULL OR deleted_flag = FALSE) "
                + "LIMIT 1",
                tenantId, userId);

        if (rows.isEmpty()) {
            return null;
        }

        Map<String, Object> profile = rows.get(0);
        StringBuilder text = new StringBuilder();

        appendJsonbField(text, profile, "communication",   "Communication preferences");
        appendJsonbField(text, profile, "role_context",    "Role & responsibilities");
        appendJsonbField(text, profile, "preferences",     "User preferences");

        String decisionPatterns = str(profile, "decision_patterns");
        if (decisionPatterns != null) {
            text.append("Decision patterns: ").append(decisionPatterns).append("\n");
        }

        return text.length() > 0 ? text.toString() : null;
    }

    /**
     * Load memories filtered by {@code category} and apply a character budget.
     * Order: importance DESC (deterministic primary key), then created_at DESC for recency tie-breaking.
     * Rows are accumulated until the char budget is consumed; partial content is never included.
     */
    private String loadMemoriesByCategory(Long tenantId, String agentCode,
                                           String category, int maxChars) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT memory_title, memory_content, importance "
                + "FROM ab_agent_memory "
                + "WHERE tenant_id = ? AND memory_agent_id = ? AND category = ? "
                + "AND (deleted_flag IS NULL OR deleted_flag = FALSE) "
                + "AND (valid_until IS NULL OR valid_until > NOW()) "
                + "ORDER BY importance DESC, created_at DESC "
                + "LIMIT ?",
                tenantId, agentCode, category, MEMORY_ROW_LIMIT);

        if (rows.isEmpty()) {
            return null;
        }

        StringBuilder text = new StringBuilder();
        int totalChars = 0;

        for (Map<String, Object> row : rows) {
            String title   = str(row, "memory_title");
            String content = str(row, "memory_content");

            if (content == null) continue;

            // Format a single memory entry
            String entry = (title != null)
                    ? "- " + title + ": " + content + "\n"
                    : "- " + content + "\n";

            if (totalChars + entry.length() > maxChars) {
                break; // Budget exhausted — never include partial entries
            }

            text.append(entry);
            totalChars += entry.length();
        }

        return text.length() > 0 ? text.toString() : null;
    }

    // =========================================================================
    // G2 — cross-agent shared memory
    // =========================================================================

    /**
     * Load memories marked {@code shareable = true} from ALL agents in the tenant.
     *
     * <p>This allows a newly created agent to benefit immediately from lessons
     * learned by other agents in the same tenant without explicit data copying.
     * Only {@code agent} and {@code user} category memories are included (session
     * and context memories are agent-specific and would add noise).
     *
     * <p>Results are ordered by importance DESC so the most valuable shared
     * knowledge appears first, and truncated at {@code maxChars} using the same
     * partial-entry-safe algorithm used elsewhere.
     *
     * @param tenantId tenant scope
     * @param maxChars character budget for the returned string
     * @return formatted shared-memory text, or {@code null} when there is nothing to share
     */
    public String loadSharedMemories(Long tenantId, int maxChars) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT memory_agent_id, memory_title, memory_content, importance "
                + "FROM ab_agent_memory "
                + "WHERE tenant_id = ? AND shareable = TRUE "
                + "AND category IN ('agent', 'user') "
                + "AND (deleted_flag IS NULL OR deleted_flag = FALSE) "
                + "AND (valid_until IS NULL OR valid_until > NOW()) "
                + "ORDER BY importance DESC, created_at DESC "
                + "LIMIT ?",
                tenantId, MEMORY_ROW_LIMIT);

        if (rows.isEmpty()) {
            return null;
        }

        StringBuilder text = new StringBuilder();
        int totalChars = 0;

        for (Map<String, Object> row : rows) {
            String agentId = str(row, "memory_agent_id");
            String title   = str(row, "memory_title");
            String content = str(row, "memory_content");

            if (content == null) continue;

            String entry = (title != null)
                    ? "- [" + agentId + "] " + title + ": " + content + "\n"
                    : "- [" + agentId + "] " + content + "\n";

            if (totalChars + entry.length() > maxChars) {
                break;
            }
            text.append(entry);
            totalChars += entry.length();
        }

        return text.length() > 0 ? text.toString() : null;
    }

    // =========================================================================
    // Assembly helpers
    // =========================================================================

    /**
     * Append a named section to the output buffer.
     * If content exceeds maxChars the section is hard-truncated with an ellipsis.
     * Empty or blank content is silently skipped.
     */
    private void appendSection(StringBuilder sb, String header, String content, int maxChars) {
        if (content == null || content.isBlank()) return;

        sb.append("\n## ").append(header).append("\n");

        if (content.length() > maxChars) {
            sb.append(content, 0, maxChars).append("...\n");
        } else {
            sb.append(content).append("\n");
        }
    }

    /**
     * Format a JSONB-stored field as "Label: {json}\n" and append to buffer.
     * If the field is null or blank the line is omitted.
     */
    private void appendJsonbField(StringBuilder sb, Map<String, Object> row,
                                   String column, String label) {
        Object val = row.get(column);
        if (val == null) return;

        String jsonText = val.toString().trim();
        if (jsonText.isEmpty() || jsonText.equals("null")) return;

        // Pretty-print if possible, otherwise use raw string
        try {
            Object parsed = objectMapper.readValue(jsonText, Object.class);
            jsonText = objectMapper.writeValueAsString(parsed);
        } catch (Exception ignored) {
            // Use raw value as-is
        }

        sb.append(label).append(": ").append(jsonText).append("\n");
    }

    /**
     * Extract a string value from a map column, returning null if absent or blank.
     */
    private String str(Map<String, Object> row, String key) {
        Object val = row.get(key);
        if (!(val instanceof String s)) return null;
        return s.isBlank() ? null : s;
    }
}
