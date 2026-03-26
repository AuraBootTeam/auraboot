package com.auraboot.framework.agent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Service that generates A2A-compliant Agent Card documents.
 * <p>
 * Agent Cards conform to the Agent-to-Agent (A2A) protocol standard
 * (https://google.github.io/A2A/), enabling external systems to discover
 * AuraBoot agents and their capabilities.
 * <p>
 * Security: Only public metadata is exposed (name, description, skills).
 * Internal fields (system_prompt, soul_profile, model, API keys) are
 * deliberately excluded.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentCardService {

    private final JdbcTemplate jdbcTemplate;

    @Value("${app.frontend-base-url:http://localhost:5173}")
    private String baseUrl;

    private static final String AURABOOT_VERSION = "1.0.0";
    private static final String WELL_KNOWN_PREFIX = "/.well-known/agent/";

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Generate an A2A Agent Card for a specific agent identified by its code.
     * Agents with tenant_id = 0 are system-level agents visible to all tenants.
     *
     * @param agentCode the agent's unique code (e.g. "tpl_aurabot_internal")
     * @return A2A-compliant agent card, or {@code null} if the agent is not found / not active
     */
    public Map<String, Object> generateAgentCard(String agentCode) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT agent_code, name, description, agent_type, communication_style " +
                "FROM ab_agent_definition " +
                "WHERE agent_code = ? " +
                "  AND status = 'active' " +
                "  AND (deleted_flag = FALSE OR deleted_flag IS NULL) " +
                "LIMIT 1",
                agentCode);

        if (rows.isEmpty()) {
            log.debug("AgentCardService.generateAgentCard: agent not found or inactive: {}", agentCode);
            return null;
        }

        Map<String, Object> agent = rows.get(0);
        List<Map<String, Object>> skills = loadSkills(agentCode);

        return buildAgentCard(agent, skills);
    }

    /**
     * Generate a discovery document listing all publicly accessible (active) agents.
     * Returns a lightweight index; each entry carries a {@code cardUrl} pointing to
     * the full Agent Card.
     *
     * @return discovery document: {@code { agents: [{name, code, description, cardUrl}], ... }}
     */
    public Map<String, Object> generateDiscoveryDocument() {
        List<Map<String, Object>> agents = jdbcTemplate.queryForList(
                "SELECT agent_code, name, description " +
                "FROM ab_agent_definition " +
                "WHERE status = 'active' " +
                "  AND (deleted_flag = FALSE OR deleted_flag IS NULL) " +
                "ORDER BY name");

        List<Map<String, Object>> agentEntries = new ArrayList<>();
        for (Map<String, Object> row : agents) {
            String code = (String) row.get("agent_code");
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", row.get("name"));
            entry.put("code", code);
            entry.put("description", row.get("description"));
            entry.put("cardUrl", baseUrl + WELL_KNOWN_PREFIX + code + ".json");
            agentEntries.add(entry);
        }

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("platform", "AuraBoot");
        doc.put("version", AURABOOT_VERSION);
        doc.put("protocol", "a2a");
        doc.put("agentCount", agentEntries.size());
        doc.put("agents", agentEntries);
        return doc;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Load active skills for a given agent code.
     * Skills are shared across all tenants via skill_code; we fetch ACTIVE ones only.
     */
    private List<Map<String, Object>> loadSkills(String agentCode) {
        // ab_agent_skill is a global catalogue (not per-agent assignment table exists yet),
        // so we load all active skills as agent capabilities.
        return jdbcTemplate.queryForList(
                "SELECT skill_code, skill_name, skill_description, skill_category " +
                "FROM ab_agent_skill " +
                "WHERE skill_status = 'active' " +
                "  AND (deleted_flag = FALSE OR deleted_flag IS NULL) " +
                "ORDER BY skill_name");
    }

    /**
     * Build an A2A-compliant agent card map from raw DB row + skills list.
     * Spec reference: https://google.github.io/A2A/specification/
     */
    private Map<String, Object> buildAgentCard(Map<String, Object> agent,
                                                List<Map<String, Object>> skills) {
        String code = (String) agent.get("agent_code");

        // capabilities block
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("streaming", false);
        capabilities.put("pushNotifications", false);

        // skills block — map to A2A SkillCard shape
        List<Map<String, Object>> skillCards = new ArrayList<>();
        for (Map<String, Object> s : skills) {
            Map<String, Object> skill = new LinkedHashMap<>();
            skill.put("id", s.get("skill_code"));
            skill.put("name", s.get("skill_name"));
            skill.put("description", s.get("skill_description"));
            String category = (String) s.get("skill_category");
            if (category != null && !category.isBlank()) {
                skill.put("tags", List.of(category));
            }
            skillCards.add(skill);
        }

        // authentication block
        Map<String, Object> authentication = new LinkedHashMap<>();
        authentication.put("schemes", List.of("bearer"));

        // assemble root card
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("name", agent.get("name"));
        card.put("description", agent.get("description"));
        card.put("url", baseUrl);
        card.put("version", AURABOOT_VERSION);
        card.put("protocol", "a2a");
        card.put("capabilities", capabilities);
        card.put("skills", skillCards);
        card.put("authentication", authentication);
        card.put("defaultInputModes", List.of("text"));
        card.put("defaultOutputModes", List.of("text"));

        // AuraBoot extensions (non-breaking, prefixed with "x-")
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("agentCode", code);
        String commStyle = (String) agent.get("communication_style");
        if (commStyle != null && !commStyle.isBlank()) {
            extensions.put("communicationStyle", commStyle);
        }
        extensions.put("agentType", agent.get("agent_type"));
        card.put("x-auraboot", extensions);

        return card;
    }
}
