package com.auraboot.framework.agent.service;

import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * ACP Capability Layer: routes BIF intent+object to Capability, then Capability to Skill.
 * Two-phase routing:
 *   Phase 1: BIF(intent + object) → 1-3 candidate Capabilities (rule matching)
 *   Phase 2: Capability + BIF context → 1 specific Skill (from capability.skills list)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CapabilityRouter {

    private final DynamicDataMapper dynamicDataMapper;
    private final ObjectMapper objectMapper;
    private final AgentSkillService skillService;

    /**
     * Route from BIF to candidate Skills via Capability Layer.
     * Returns candidate skill codes, or empty if no capability matches.
     */
    public List<String> route(Long tenantId, String intent, String objectCode) {
        if (objectCode == null) return List.of();

        // Phase 1: Find matching Capabilities
        List<Map<String, Object>> capabilities = findCapabilities(tenantId, intent, objectCode);

        if (capabilities.isEmpty()) {
            return List.of();
        }

        // Phase 2: Extract skills from matching capabilities
        List<String> candidateSkills = new ArrayList<>();
        for (Map<String, Object> cap : capabilities) {
            List<String> skills = parseJsonList(cap.get("skills"));
            for (String skillCode : skills) {
                // Verify skill exists and avoid duplicates
                if (!candidateSkills.contains(skillCode) && skillService.loadSkill(tenantId, skillCode) != null) {
                    candidateSkills.add(skillCode);
                }
            }
        }

        log.debug("Capability routing: intent={}, object={}, capabilities={}, skills={}",
                intent, objectCode, capabilities.size(), candidateSkills.size());
        return candidateSkills;
    }

    /**
     * Find Capabilities matching the given intent and object patterns.
     */
    private List<Map<String, Object>> findCapabilities(Long tenantId, String intent, String objectCode) {
        String sql = "SELECT capability_code, capability_name, domain, intent_patterns, object_patterns, " +
                "skills, selection_strategy " +
                "FROM ab_agent_capability " +
                "WHERE (tenant_id = #{params.tenantId} OR tenant_id = -1) " +
                "AND capability_status = 'active' AND (deleted_flag = FALSE OR deleted_flag IS NULL) " +
                "ORDER BY tenant_id DESC";  // tenant-specific overrides platform

        try {
            List<Map<String, Object>> allCaps = dynamicDataMapper.selectByQuery(sql,
                    Map.of("tenantId", tenantId));

            // Filter by intent and object pattern matching
            List<Map<String, Object>> matched = new ArrayList<>();
            for (Map<String, Object> cap : allCaps) {
                boolean intentMatch = matchesPattern(parseJsonList(cap.get("intent_patterns")), intent);
                boolean objectMatch = matchesObjectPattern(parseJsonList(cap.get("object_patterns")), objectCode);
                if (intentMatch && objectMatch) {
                    matched.add(cap);
                }
            }
            return matched;
        } catch (Exception e) {
            log.debug("Failed to query capabilities: {}", e.getMessage());
            return List.of();
        }
    }

    private boolean matchesPattern(List<String> patterns, String value) {
        if (patterns.isEmpty()) return true;  // no pattern = match all
        return patterns.contains(value);
    }

    private boolean matchesObjectPattern(List<String> patterns, String objectCode) {
        if (patterns.isEmpty()) return true;
        for (String pattern : patterns) {
            if (pattern.endsWith("*")) {
                String prefix = pattern.substring(0, pattern.length() - 1);
                if (objectCode.startsWith(prefix)) return true;
            } else if (pattern.equals(objectCode)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private List<String> parseJsonList(Object jsonObj) {
        if (jsonObj == null) return List.of();
        try {
            if (jsonObj instanceof String s) {
                return objectMapper.readValue(s, List.class);
            } else if (jsonObj instanceof List<?> list) {
                return (List<String>) list;
            }
        } catch (Exception e) {
            // ignore parse failures — treat as empty
        }
        return List.of();
    }
}
