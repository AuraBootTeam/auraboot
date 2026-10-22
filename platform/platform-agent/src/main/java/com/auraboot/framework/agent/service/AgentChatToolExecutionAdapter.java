package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.provider.ToolDefinition;
import com.auraboot.framework.agent.runtime.AgentErrorFrame;
import com.auraboot.framework.agent.runtime.ToolLoopResultNormalizer;
import com.auraboot.framework.common.util.LogSanitizer;
import com.auraboot.framework.conversation.TurnContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
class AgentChatToolExecutionAdapter {

    private final ToolLoopService toolLoopService;
    private final ObjectMapper objectMapper;

    Map<String, Object> execute(TurnContext ctx, String agentCode, String toolName,
                                Map<String, Object> input, List<ToolDefinition> toolDefs) {
        try {
            if (findToolDef(toolDefs, toolName) == null) {
                return errorResult(validationErrorFrame(toolName, input, toolDefs), 0L);
            }
            if (toolLoopService == null) {
                return errorResult(AgentErrorFrame.of(
                        AgentErrorFrame.CATEGORY_TOOL,
                        toolName,
                        input,
                        "ToolKernelUnavailable",
                        false,
                        "Tool execution kernel is unavailable.",
                        "Stop the turn and ask an operator to check the agent tool runtime."),
                        0L);
            }
            log.debug("Agent chat tool call via ToolLoopService: tool={}, input={}",
                    toolName, LogSanitizer.safe(input));
            String rawResult = toolLoopService.executeToolCall(
                    ctx.tenantId(),
                    ctx.turnId(),
                    ctx.taskPid(),
                    agentCode,
                    toolName,
                    input != null ? input : Map.of(),
                    toAgentToolDefinitions(toolDefs),
                    null);
            return ToolLoopResultNormalizer.normalize(objectMapper, rawResult, toolName, input);
        } catch (Exception e) {
            log.warn("Tool execution failed in agent chat: tool={}, errorType={}, message={}",
                    toolName, e.getClass().getSimpleName(), safeExceptionMessage(e));
            return errorResult(AgentErrorFrame.of(
                    AgentErrorFrame.CATEGORY_TOOL,
                    toolName,
                    input,
                    e.getClass().getSimpleName(),
                    true,
                    "Tool execution failed.",
                    "Use corrected arguments or summarize the failure to the user."),
                    0L);
        }
    }

    private ToolDefinition findToolDef(List<ToolDefinition> defs, String toolName) {
        if (defs == null || toolName == null) {
            return null;
        }
        for (ToolDefinition def : defs) {
            if (def == null) {
                continue;
            }
            if (toolName.equals(def.getToolCode())
                    || toolName.equals(def.getToolName())
                    || toolName.equals(toLlmSafeToolName(def.getToolCode()))) {
                return def;
            }
        }
        return null;
    }

    private List<AgentToolDefinition> toAgentToolDefinitions(List<ToolDefinition> toolDefs) {
        if (toolDefs == null || toolDefs.isEmpty()) {
            return Collections.emptyList();
        }
        List<AgentToolDefinition> result = new ArrayList<>();
        for (ToolDefinition def : toolDefs) {
            if (def == null || def.getToolCode() == null) {
                continue;
            }
            result.add(AgentToolDefinition.builder()
                    .name(def.getToolCode())
                    .description(def.getDescription())
                    .inputSchema(def.getParameterSchema())
                    .toolType(def.getToolType())
                    .sourceCode(def.getSourceCode())
                    .requiresApproval(def.isRequiresApproval())
                    .requiresConfirmation(def.isRequiresConfirmation())
                    .riskLevel(def.getRiskLevel())
                    .requiredPermissions(def.getRequiredPermissions())
                    .confirmationPolicy(def.getConfirmationPolicy())
                    .build());
        }
        return result;
    }

    private AgentErrorFrame validationErrorFrame(String toolName, Map<String, Object> input,
                                                 List<ToolDefinition> toolDefs) {
        return AgentErrorFrame.of(
                AgentErrorFrame.CATEGORY_VALIDATION,
                toolName,
                input,
                "UnknownTool",
                true,
                "The model requested an unavailable tool.",
                "Call one of the available tools: " + availableToolNames(toolDefs) + ".");
    }

    private Map<String, Object> errorResult(AgentErrorFrame errorFrame, Object durationMs) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("error", errorFrame.userSafeMessage());
        response.put("errorFrame", errorFrame.toSnapshotMap());
        response.put("retryable", errorFrame.retryable());
        response.put("durationMs", durationMs instanceof Number ? durationMs : 0L);
        return response;
    }

    private String availableToolNames(List<ToolDefinition> toolDefs) {
        if (toolDefs == null || toolDefs.isEmpty()) {
            return "<none>";
        }
        return toolDefs.stream()
                .filter(t -> t != null && t.getToolCode() != null)
                .map(ToolDefinition::getToolCode)
                .limit(10)
                .reduce((left, right) -> left + ", " + right)
                .orElse("<none>");
    }

    private String toLlmSafeToolName(String toolCode) {
        return toolCode.replace(':', '_').replace('.', '_');
    }

    private String safeExceptionMessage(Exception e) {
        if (e == null) {
            return "Unknown error";
        }
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return LogSanitizer.safe(message);
    }
}
