package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.agent.port.ToolDiscoveryPort;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.aurabot.skill.SkillRequest;
import com.auraboot.framework.aurabot.skill.SkillResult;
import com.auraboot.framework.aurabot.skill.provider.AuraBotSkillToolProvider;
import com.auraboot.framework.aurabot.skill.provider.SkillToolExecutor;
import com.auraboot.framework.aurabot.skill.provider.SkillToolExecutor.DispatchOutcome;
import com.auraboot.framework.aurabot.skill.provider.SkillToolExecutor.OutcomeKind;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Routes LLM tool calls to the appropriate backend service via ToolDiscoveryPort.
 *
 * <p>Converts sanitized LLM tool names back to ToolProvider codes,
 * then delegates execution to ToolProviderRegistry through ToolDiscoveryPort.
 *
 * <p>Tool name de-sanitization (from {@link ChatToolResolver}):
 * <ul>
 *   <li>{@code platform_*} → {@code platform.*}</li>
 *   <li>{@code cmd_*} → {@code cmd:*}</li>
 *   <li>{@code nq_*} → {@code nq:*}</li>
 *   <li>{@code list_*} → {@code list:*}</li>
 *   <li>{@code get_*} → {@code get:*}</li>
 * </ul>
 *
 * <p>The {@code aurabot:} branch (Plan §C-5 Task 4) intercepts AuraBot Skill SPI
 * calls before the legacy {@link ToolDiscoveryPort} path: LOW skills run inline
 * and return a {@code success/data} envelope, MEDIUM+ skills return a
 * {@code _aurabot_skill_pending} marker the chat layer translates into a
 * preview confirm card.
 *
 * @author AuraBoot Team
 * @since 3.1.0
 */
@Slf4j
@Service
public class ChatToolExecutor {

    private static final String AURABOT_TOOL_PREFIX = AuraBotSkillToolProvider.PROVIDER_CODE + ":";

    private final ToolDiscoveryPort toolDiscoveryPort;
    private final ChatToolResolver chatToolResolver;
    private final SkillToolExecutor skillToolExecutor;
    private final ObjectMapper objectMapper;

    public ChatToolExecutor(
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            ToolDiscoveryPort toolDiscoveryPort,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            ChatToolResolver chatToolResolver,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            SkillToolExecutor skillToolExecutor,
            ObjectMapper objectMapper) {
        this.toolDiscoveryPort = toolDiscoveryPort;
        this.chatToolResolver = chatToolResolver;
        this.skillToolExecutor = skillToolExecutor;
        this.objectMapper = objectMapper;
    }

    /**
     * Execute a tool call and return the result as a map.
     * <p>
     * Routes through ToolProviderRegistry via ToolDiscoveryPort in the shared AI runtime.
     *
     * @param toolName  the tool name (e.g., "cmd_crm_update_lead")
     * @param input     the tool input parameters from the LLM
     * @param modelCode the current model context code
     * @return result map with either "success" data or "error" details
     */
    public Map<String, Object> execute(String toolName, Map<String, Object> input, String modelCode) {
        if (toolName == null || toolName.isBlank()) {
            return errorResult("Tool name is required");
        }
        if (input == null) {
            input = Map.of();
        }

        // ── AuraBot Skill SPI branch (Plan §C-5 Task 4) ─────────────────────
        // Accept both the canonical "aurabot:<skill>" form and the bare skill
        // name form (LLM sanitisers occasionally drop the provider prefix).
        // Bare-name lookup is registry-gated so unrelated tools (e.g. raw
        // "cmd_crm_create_lead") fall through to the legacy path.
        String skillName = resolveAuraBotSkillName(toolName);
        if (skillName != null) {
            return executeAuraBotSkill(skillName, input);
        }

        if (toolDiscoveryPort == null) {
            return errorResult("ToolDiscoveryPort is not available in the current runtime.");
        }

        try {
            Long tenantId = MetaContext.getCurrentTenantId();
            String providerToolCode = chatToolResolver != null
                    ? chatToolResolver.getProviderToolCode(toolName)
                    : null;
            if (providerToolCode == null) {
                providerToolCode = toProviderToolCode(toolName, modelCode);
            }
            log.debug("Routing tool {} -> provider code {}", toolName, providerToolCode);
            return toolDiscoveryPort.executeTool(tenantId, providerToolCode, input);
        } catch (Exception e) {
            log.error("Tool execution failed for {}: {}", toolName, e.getMessage(), e);
            return errorResult(e.getMessage());
        }
    }

    /**
     * Resolve a chat-side tool name to an AuraBot skill name, or {@code null} when
     * the call should fall through to the legacy ToolDiscoveryPort path.
     *
     * <p>Two acceptance forms:
     * <ol>
     *   <li>{@code aurabot:<skillName>} — explicit provider prefix.</li>
     *   <li>{@code <skillName>} (no colon) — bare name registered with the skill
     *       registry. The registry probe avoids hijacking unrelated unprefixed
     *       names that legacy resolvers want to handle.</li>
     * </ol>
     */
    private String resolveAuraBotSkillName(String toolName) {
        if (skillToolExecutor == null) {
            return null;
        }
        if (toolName.startsWith(AURABOT_TOOL_PREFIX)) {
            String tail = toolName.substring(AURABOT_TOOL_PREFIX.length());
            return tail.isBlank() ? null : tail;
        }
        if (!toolName.contains(":") && skillToolExecutor.handlesBareSkillName(toolName)) {
            return toolName;
        }
        return null;
    }

    /**
     * Run an AuraBot skill via {@link SkillToolExecutor#dispatch} and translate the
     * outcome into the chat tool envelope shape:
     *
     * <ul>
     *   <li>EXECUTED → {@code {success:true, data:<payload>}}</li>
     *   <li>PREVIEW_PENDING → {@code {_aurabot_skill_pending:true, skillName, preview, previewToken, riskLevel}}</li>
     *   <li>RuntimeException → {@code errorResult(...)} (logged, not swallowed silently)</li>
     * </ul>
     */
    private Map<String, Object> executeAuraBotSkill(String skillName, Map<String, Object> input) {
        try {
            SkillRequest req = SkillRequest.builder()
                    .skillName(skillName)
                    .params(objectMapper.valueToTree(input))
                    .build();

            DispatchOutcome outcome = skillToolExecutor.dispatch(skillName, req);

            if (outcome.kind() == OutcomeKind.EXECUTED) {
                SkillResult result = outcome.result();
                Map<String, Object> ok = new LinkedHashMap<>();
                ok.put("success", true);
                ok.put("data", result == null ? null : result.getPayload());
                return ok;
            }

            // PREVIEW_PENDING — surface a stable marker the chat/FE layer
            // recognises and renders as a confirm card. Keep the keys flat so
            // SSE serialisation never collapses nested nulls.
            SkillResult preview = outcome.preview();
            Map<String, Object> pending = new LinkedHashMap<>();
            pending.put("_aurabot_skill_pending", true);
            pending.put("skillName", skillName);
            Object previewBody = null;
            if (preview != null) {
                previewBody = preview.getPreview() != null ? preview.getPreview() : preview.getPayload();
            }
            pending.put("preview", previewBody);
            pending.put("previewToken", outcome.previewToken());
            pending.put("riskLevel", outcome.riskLevel());
            return pending;
        } catch (RuntimeException e) {
            // Skill SPI throws SkillSpiException as RuntimeException; permission
            // / schema / preview-token failures are user-facing and must reach
            // the LLM as a typed error envelope, not propagate up the stream.
            log.warn("AuraBot skill dispatch failed for {}: {}", skillName, e.getMessage());
            return errorResult(e.getMessage());
        }
    }

    /**
     * De-sanitize LLM tool names back to ToolProvider code convention.
     * <p>
     * LLM tool names use underscores (LLM function-name compatible),
     * provider codes use colons/dots as namespace separators.
     */
    private String toProviderToolCode(String toolName, String modelCode) {
        if (toolName == null) return toolName;

        // Provider naming: platform_* → platform.*
        if (toolName.startsWith("platform_")) {
            return "platform." + toolName.substring("platform_".length());
        }
        // Provider naming: cmd_* → cmd:*
        if (toolName.startsWith("cmd_")) {
            return "cmd:" + toolName.substring(4);
        }
        // Provider naming: nq_* → nq:*
        if (toolName.startsWith("nq_")) {
            return "nq:" + toolName.substring(3);
        }
        // Provider naming: list_* → list:*
        if (toolName.startsWith("list_")) {
            return "list:" + toolName.substring(5);
        }
        // Provider naming: get_* → get:*
        if (toolName.startsWith("get_")) {
            return "get:" + toolName.substring(4);
        }
        // Pass-through for unknown patterns
        return toolName;
    }

    // ==================== Helpers ====================

    private static Map<String, Object> errorResult(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("error", message != null ? message : "Unknown error");
        return result;
    }
}
