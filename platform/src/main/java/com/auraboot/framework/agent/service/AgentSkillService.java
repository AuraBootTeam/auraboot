package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.saas.executor.SystemTenantContextExecutor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Skill service — loads and resolves skill definitions from ab_agent_skill.
 *
 * Supports the 2-skill model introduced in Phase 3:
 * - dsl.command / dsl.query: execution_mode = dsl_dispatch, no pre-registered tools in skill_tools.
 *   Tools are resolved at runtime by SkillEngine; resolveSkillTools() returns empty list for these.
 * - Legacy ATOMIC/WORKFLOW/SOLUTION skills: skill_tools JSONB lists tool codes loaded from ab_agent_tool.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentSkillService {

    private static final Long PLATFORM_TENANT_ID = SystemTenantContextExecutor.SYSTEM_TENANT_ID;

    private final DynamicDataMapper dynamicDataMapper;
    private final AgentObservationService observationService;
    private final ObjectMapper objectMapper;

    /**
     * Load a skill definition by code.
     */
    public Map<String, Object> loadSkill(Long tenantId, String skillCode) {
        // First try tenant-specific skill, then fall back to platform built-in skills.
        String sql = "SELECT * FROM ab_agent_skill " +
                "WHERE (tenant_id = #{params.tenantId} OR (tenant_id = #{params.platformTenantId} AND is_builtin = TRUE)) " +
                "AND skill_code = #{params.skillCode} AND skill_status = 'active' " +
                "AND deleted_flag = FALSE " +
                "ORDER BY tenant_id DESC LIMIT 1";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                Map.of("tenantId", tenantId, "platformTenantId", PLATFORM_TENANT_ID, "skillCode", skillCode));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * List active skills, optionally filtered by category and/or level.
     * Includes platform-level built-in skills merged with tenant-specific skills.
     * Tenant-specific skills with the same skill_code override built-in ones.
     */
    public List<Map<String, Object>> listSkills(Long tenantId, String category, String level) {
        StringBuilder sql = new StringBuilder(
                "SELECT DISTINCT ON (skill_code) " +
                "pid, skill_code, skill_name, skill_description, skill_level, " +
                "skill_category, skill_icon, skill_tools, skill_input_schema, usage_count, avg_rating, is_builtin " +
                "FROM ab_agent_skill " +
                "WHERE (tenant_id = #{params.tenantId} OR (tenant_id = #{params.platformTenantId} AND is_builtin = TRUE)) " +
                "AND skill_status = 'active' AND deleted_flag = FALSE");
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("platformTenantId", PLATFORM_TENANT_ID);

        if (category != null && !category.isBlank()) {
            sql.append(" AND skill_category = #{params.category}");
            params.put("category", category);
        }
        if (level != null && !level.isBlank()) {
            sql.append(" AND skill_level = #{params.level}");
            params.put("level", level);
        }
        // DISTINCT ON requires ORDER BY to start with the distinct column; tenant-specific wins over built-in
        sql.append(" ORDER BY skill_code, tenant_id DESC, usage_count DESC");
        return dynamicDataMapper.selectByQuery(sql.toString(), params);
    }

    /**
     * Resolve a skill into its constituent tool definitions.
     * For WORKFLOW skills, returns tools in execution order.
     *
     * For dsl_dispatch mode skills (dsl.command / dsl.query), skill_tools is null/empty by design —
     * tools are resolved dynamically by SkillEngine at runtime. Returns empty list in that case.
     */
    public List<AgentToolDefinition> resolveSkillTools(Long tenantId, String skillCode) {
        Map<String, Object> skill = loadSkill(tenantId, skillCode);
        if (skill == null) return List.of();

        List<String> toolCodes = parseToolCodes(skill.get("skill_tools"));
        // dsl_dispatch skills have no pre-registered tools — SkillEngine handles dispatch directly
        if (toolCodes.isEmpty()) return List.of();

        return loadToolsByCode(tenantId, toolCodes);
    }

    /**
     * Load tool definitions from ab_agent_tool by code list.
     * Used by resolveSkillTools() for ATOMIC/WORKFLOW/SOLUTION skills.
     */
    private List<AgentToolDefinition> loadToolsByCode(Long tenantId, List<String> toolCodes) {
        List<AgentToolDefinition> tools = new ArrayList<>();
        for (String toolCode : toolCodes) {
            String sql = "SELECT tool_code, tool_type, tool_name, tool_description, source_type, source_code, " +
                    "input_schema, output_schema, requires_approval, risk_level, native_tool_config " +
                    "FROM ab_agent_tool WHERE tenant_id = #{params.tenantId} AND tool_code = #{params.toolCode} " +
                    "AND tool_status = 'active' AND deleted_flag = FALSE";
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                    Map.of("tenantId", tenantId, "toolCode", toolCode));
            if (!rows.isEmpty()) {
                tools.add(mapToToolDefinition(rows.get(0)));
            }
        }
        return tools;
    }

    private AgentToolDefinition mapToToolDefinition(Map<String, Object> row) {
        Map<String, Object> inputSchema = parseJsonObject(row.get("input_schema"));
        if (inputSchema == null) {
            inputSchema = Map.of("type", "object", "properties", Map.of());
        }
        return AgentToolDefinition.builder()
                .name((String) row.get("tool_code"))
                .description((String) row.get("tool_description"))
                .inputSchema(inputSchema)
                .toolType((String) row.get("tool_type"))
                .sourceCode((String) row.get("source_code"))
                .requiresApproval(Boolean.TRUE.equals(row.get("requires_approval")))
                .riskLevel((String) row.get("risk_level"))
                .nativeToolConfig((String) row.get("native_tool_config"))
                .build();
    }

    /**
     * Execute a skill — resolve tools and generate an execution plan.
     * Returns a structured plan that AgentRunService can execute.
     *
     * For ATOMIC: returns single-step plan
     * For WORKFLOW: returns multi-step plan based on prompt_template
     * For SOLUTION: recursively resolves sub-skills into a flat plan
     */
    public Map<String, Object> executeSkill(Long tenantId, String skillCode,
                                             Map<String, Object> input, String agentCode) {
        Map<String, Object> skill = loadSkill(tenantId, skillCode);
        if (skill == null) {
            return Map.of("success", false, "error", "Skill not found: " + skillCode);
        }

        String level = (String) skill.get("skill_level");
        String executionMode = (String) skill.get("execution_mode");
        List<String> toolCodes = parseToolCodes(skill.get("skill_tools"));

        // dsl_dispatch skills have no pre-registered tools — SkillEngine handles dispatch at runtime
        boolean isDslDispatch = "dsl_dispatch".equals(executionMode);
        List<AgentToolDefinition> tools = isDslDispatch ? List.of() : loadToolsByCode(tenantId, toolCodes);

        if (tools.isEmpty() && !isDslDispatch) {
            return Map.of("success", false, "error", "No active tools found for skill: " + skillCode);
        }

        // Generate execution plan based on skill level
        List<Map<String, Object>> steps;
        if ("atomic".equals(level)) {
            steps = buildAtomicPlan(tools.get(0), input);
        } else if ("solution".equals(level)) {
            steps = buildSolutionPlan(tenantId, skill, input);
        } else {
            // WORKFLOW (default)
            steps = buildWorkflowPlan(skill, tools, input);
        }

        // Increment usage count
        incrementUsageCount(tenantId, skillCode);

        // Publish observation
        if (agentCode != null) {
            observationService.publish(tenantId, "skill_executed", agentCode,
                    "agent_skill", (String) skill.get("pid"),
                    Map.of("skillCode", skillCode, "level", level != null ? level : "workflow",
                            "stepCount", steps.size()));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("skillCode", skillCode);
        result.put("skillName", skill.get("skill_name"));
        result.put("level", level);
        result.put("steps", steps);
        result.put("promptTemplate", skill.get("prompt_template"));
        result.put("inputSchema", skill.get("skill_input_schema"));
        return result;
    }

    // ==================== Plan Builders ====================

    private List<Map<String, Object>> buildAtomicPlan(AgentToolDefinition tool, Map<String, Object> input) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("stepIndex", 0);
        step.put("toolCode", tool.getName());
        step.put("description", tool.getDescription());
        step.put("input", input);
        step.put("requiresApproval", tool.isRequiresApproval());
        return List.of(step);
    }

    private List<Map<String, Object>> buildWorkflowPlan(Map<String, Object> skill,
                                                          List<AgentToolDefinition> tools,
                                                          Map<String, Object> input) {
        List<Map<String, Object>> steps = new ArrayList<>();

        for (int i = 0; i < tools.size(); i++) {
            AgentToolDefinition tool = tools.get(i);
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("stepIndex", i);
            step.put("toolCode", tool.getName());
            step.put("description", tool.getDescription());
            step.put("requiresApproval", tool.isRequiresApproval());
            step.put("riskLevel", tool.getRiskLevel());

            // For first step, pass the original input
            if (i == 0 && input != null) {
                step.put("input", input);
            }
            // Subsequent steps will receive output from previous step at runtime

            steps.add(step);
        }

        return steps;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildSolutionPlan(Long tenantId,
                                                          Map<String, Object> skill,
                                                          Map<String, Object> input) {
        // SOLUTION skills contain sub-skill references in skill_tools
        // Format: ["skill:sub_skill_code", "tool:tool_code", ...]
        List<String> refs = parseToolCodes(skill.get("skill_tools"));
        List<Map<String, Object>> steps = new ArrayList<>();
        int stepIndex = 0;

        for (String ref : refs) {
            if (ref.startsWith("skill:")) {
                // Recursively resolve sub-skill
                String subSkillCode = ref.substring(6);
                Map<String, Object> subResult = executeSkill(tenantId, subSkillCode, input, null);
                if (Boolean.TRUE.equals(subResult.get("success"))) {
                    List<Map<String, Object>> subSteps = (List<Map<String, Object>>) subResult.get("steps");
                    if (subSteps != null) {
                        for (Map<String, Object> subStep : subSteps) {
                            Map<String, Object> step = new LinkedHashMap<>(subStep);
                            step.put("stepIndex", stepIndex++);
                            step.put("fromSkill", subSkillCode);
                            steps.add(step);
                        }
                    }
                }
            } else {
                // Direct tool reference
                String toolCode = ref.startsWith("tool:") ? ref.substring(5) : ref;
                List<AgentToolDefinition> tools = loadToolsByCode(tenantId, List.of(toolCode));
                if (!tools.isEmpty()) {
                    AgentToolDefinition tool = tools.get(0);
                    Map<String, Object> step = new LinkedHashMap<>();
                    step.put("stepIndex", stepIndex++);
                    step.put("toolCode", tool.getName());
                    step.put("description", tool.getDescription());
                    step.put("requiresApproval", tool.isRequiresApproval());
                    steps.add(step);
                }
            }
        }

        return steps;
    }

    // ==================== Helpers ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonObject(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Map) return (Map<String, Object>) raw;
        if (raw instanceof String s && !s.isBlank()) {
            try {
                return objectMapper.readValue(s, new TypeReference<>() {});
            } catch (Exception e) {
                log.debug("Failed to parse JSON object: {}", e.getMessage());
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<String> parseToolCodes(Object skillTools) {
        if (skillTools == null) return List.of();
        if (skillTools instanceof List) return (List<String>) skillTools;
        if (skillTools instanceof String s && !s.isBlank()) {
            try {
                return objectMapper.readValue(s, new TypeReference<>() {});
            } catch (Exception e) {
                log.warn("Failed to parse skill_tools: {}", e.getMessage());
            }
        }
        return List.of();
    }

    private void incrementUsageCount(Long tenantId, String skillCode) {
        try {
            // Load current usage_count, then update via DynamicDataMapper.update()
            String sql = "SELECT pid, usage_count FROM ab_agent_skill " +
                    "WHERE tenant_id = #{params.tenantId} AND skill_code = #{params.skillCode}";
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                    Map.of("tenantId", tenantId, "skillCode", skillCode));
            if (rows.isEmpty()) return;

            Map<String, Object> row = rows.get(0);
            String pid = (String) row.get("pid");
            int currentCount = row.get("usage_count") != null
                    ? ((Number) row.get("usage_count")).intValue() : 0;

            Map<String, Object> data = new HashMap<>();
            data.put("usage_count", currentCount + 1);
            data.put("updated_at", LocalDateTime.now());
            dynamicDataMapper.update("ab_agent_skill", data, Map.of("pid", pid));
        } catch (Exception e) {
            // Non-critical — don't fail skill execution for stats update
            log.debug("Failed to increment usage count for skill {}: {}", skillCode, e.getMessage());
        }
    }

    /**
     * Load the four-contract fields for a skill.
     * Returns null if skill not found.
     */
    public Map<String, Object> loadSkillContract(Long tenantId, String skillCode) {
        String sql = "SELECT skill_code, execution_mode, failure_mode, max_retry, max_steps, timeout_sec, " +
                "output_type, output_schema, render_hint, actionability, " +
                "produced_action_types, idempotency_mode, skill_input_schema, step_input_mappings, " +
                "skill_tools, prompt_template " +
                "FROM ab_agent_skill " +
                "WHERE (tenant_id = #{params.tenantId} OR (tenant_id = 0 AND is_builtin = TRUE)) " +
                "AND skill_code = #{params.skillCode} AND skill_status = 'active' AND deleted_flag = FALSE " +
                "ORDER BY tenant_id DESC LIMIT 1";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                Map.of("tenantId", tenantId, "skillCode", skillCode));
        return rows.isEmpty() ? null : rows.get(0);
    }
}
