package com.auraboot.framework.agent.runtime;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.provider.ToolDefinition;
import com.auraboot.framework.agent.util.CanonicalJsonHasher;
import com.auraboot.framework.conversation.TurnContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds secret-free runtime snapshots for agent execution rounds.
 */
@Component
public class AgentRuntimeStateFactory {

    private static final double CHARS_PER_TOKEN = 4.0;
    private static final int TOOL_TOKEN_ESTIMATE = 150;
    private static final List<String> SENSITIVE_PENDING_KEY_FRAGMENTS = List.of(
            "apikey",
            "api_key",
            "baseurl",
            "base_url",
            "secret",
            "password",
            "credential",
            "authorization",
            "token");

    public AgentExecutionState chatTurnState(
            TurnContext ctx,
            String agentCode,
            String sessionId,
            String providerCode,
            String model,
            int round,
            String toolChoice,
            String systemPrompt,
            int maxTokens,
            List<LlmChatRequest.Message> messages,
            List<LlmChatRequest.Tool> llmTools,
            List<ToolDefinition> toolDefinitions,
            Map<String, Object> pending) {

        List<LlmChatRequest.Message> safeMessages = messages == null ? List.of() : messages;
        List<LlmChatRequest.Tool> safeLlmTools = llmTools == null ? List.of() : llmTools;
        List<ToolDefinition> safeToolDefinitions = toolDefinitions == null ? List.of() : toolDefinitions;
        List<AgentToolManifestItem> toolManifest = buildToolManifest(safeLlmTools, safeToolDefinitions);
        AgentContextManifest context = buildContextManifest(systemPrompt, maxTokens, safeMessages, safeLlmTools, toolManifest);
        Map<String, Object> safePending = sanitizePending(pending);

        AgentExecutionState withoutHash = new AgentExecutionState(
                AgentExecutionState.SCHEMA_VERSION,
                "chat_turn",
                ctx != null ? ctx.turnId() : null,
                ctx != null ? ctx.turnId() : null,
                ctx != null ? ctx.taskPid() : null,
                ctx != null ? ctx.tenantId() : null,
                ctx != null ? ctx.userId() : null,
                agentCode,
                sessionId,
                providerCode,
                model,
                round,
                toolChoice,
                context,
                toolManifest,
                safePending,
                null);
        String stateHash = CanonicalJsonHasher.sha256Canonical(withoutHash.toSnapshotMap(false));
        return new AgentExecutionState(
                withoutHash.schemaVersion(),
                withoutHash.executionKind(),
                withoutHash.turnId(),
                withoutHash.runPid(),
                withoutHash.taskPid(),
                withoutHash.tenantId(),
                withoutHash.userId(),
                withoutHash.agentCode(),
                withoutHash.sessionId(),
                withoutHash.providerCode(),
                withoutHash.model(),
                withoutHash.round(),
                withoutHash.toolChoice(),
                withoutHash.context(),
                withoutHash.tools(),
                withoutHash.pending(),
                stateHash);
    }

    public AgentExecutionState acpRunState(
            Long tenantId,
            Long userId,
            String runPid,
            String taskPid,
            String agentCode,
            String providerCode,
            String model,
            String systemPrompt,
            String userMessage,
            int maxTokens,
            List<AgentToolDefinition> tools,
            Map<String, Object> pending) {
        List<AgentToolDefinition> safeTools = tools == null ? List.of() : tools;
        List<LlmChatRequest.Message> messages = userMessage == null || userMessage.isBlank()
                ? List.of()
                : List.of(LlmChatRequest.Message.text("user", userMessage));
        List<LlmChatRequest.Tool> llmTools = toLlmTools(safeTools);
        List<AgentToolManifestItem> toolManifest = buildAgentToolManifest(safeTools);
        AgentContextManifest context = buildContextManifest(systemPrompt, maxTokens, messages, llmTools, toolManifest);
        Map<String, Object> safePending = sanitizePending(pending);

        AgentExecutionState withoutHash = new AgentExecutionState(
                AgentExecutionState.SCHEMA_VERSION,
                "acp_run",
                null,
                runPid,
                taskPid,
                tenantId,
                userId,
                agentCode,
                null,
                providerCode,
                model,
                0,
                null,
                context,
                toolManifest,
                safePending,
                null);
        String stateHash = CanonicalJsonHasher.sha256Canonical(withoutHash.toSnapshotMap(false));
        return new AgentExecutionState(
                withoutHash.schemaVersion(),
                withoutHash.executionKind(),
                withoutHash.turnId(),
                withoutHash.runPid(),
                withoutHash.taskPid(),
                withoutHash.tenantId(),
                withoutHash.userId(),
                withoutHash.agentCode(),
                withoutHash.sessionId(),
                withoutHash.providerCode(),
                withoutHash.model(),
                withoutHash.round(),
                withoutHash.toolChoice(),
                withoutHash.context(),
                withoutHash.tools(),
                withoutHash.pending(),
                stateHash);
    }

    private AgentContextManifest buildContextManifest(
            String systemPrompt,
            int maxTokens,
            List<LlmChatRequest.Message> messages,
            List<LlmChatRequest.Tool> llmTools,
            List<AgentToolManifestItem> toolManifest) {
        int systemPromptChars = systemPrompt == null ? 0 : systemPrompt.length();
        int messageChars = messageChars(messages);
        int toolCount = llmTools.size();
        int toolTokens = toolCount * TOOL_TOKEN_ESTIMATE;
        String systemPromptHash = hashText("systemPrompt", systemPrompt);
        String messagesHash = CanonicalJsonHasher.sha256Canonical(messageHashPayload(messages));
        String toolsHash = CanonicalJsonHasher.sha256Canonical(toolHashPayload(llmTools, toolManifest));

        AgentContextManifest withoutHash = new AgentContextManifest(
                maxTokens,
                systemPromptChars,
                estimateTokens(systemPromptChars),
                systemPromptHash,
                messages.size(),
                messageChars,
                estimateTokens(messageChars),
                messagesHash,
                toolCount,
                toolTokens,
                toolsHash,
                null);
        String contextHash = CanonicalJsonHasher.sha256Canonical(withoutHash.toSnapshotMap());
        return new AgentContextManifest(
                withoutHash.maxTokens(),
                withoutHash.systemPromptChars(),
                withoutHash.systemPromptTokens(),
                withoutHash.systemPromptHash(),
                withoutHash.messageCount(),
                withoutHash.messageChars(),
                withoutHash.messageTokens(),
                withoutHash.messagesHash(),
                withoutHash.toolCount(),
                withoutHash.toolTokens(),
                withoutHash.toolsHash(),
                contextHash);
    }

    private List<AgentToolManifestItem> buildToolManifest(
            List<LlmChatRequest.Tool> llmTools,
            List<ToolDefinition> toolDefinitions) {
        Map<String, ToolDefinition> defsByCode = new LinkedHashMap<>();
        for (ToolDefinition def : toolDefinitions) {
            if (def != null && def.getToolCode() != null) {
                defsByCode.put(def.getToolCode(), def);
            }
        }

        List<AgentToolManifestItem> items = new ArrayList<>();
        for (LlmChatRequest.Tool llmTool : llmTools) {
            if (llmTool == null) {
                continue;
            }
            ToolDefinition def = defsByCode.get(llmTool.getName());
            items.add(new AgentToolManifestItem(
                    def != null ? def.getToolCode() : llmTool.getName(),
                    llmTool.getName(),
                    def != null ? def.getToolName() : null,
                    def != null ? def.getToolType() : null,
                    def != null ? def.getProviderCode() : null,
                    def != null ? def.getSourceCode() : null,
                    def != null ? def.getRiskLevel() : null,
                    def != null ? def.getConfirmationPolicy() : null,
                    def != null && def.isRequiresApproval(),
                    def != null && def.isRequiresConfirmation(),
                    CanonicalJsonHasher.sha256Canonical(llmTool.getInputSchema())));
        }

        for (ToolDefinition def : toolDefinitions) {
            if (def == null || def.getToolCode() == null) {
                continue;
            }
            boolean alreadyIncluded = items.stream()
                    .anyMatch(item -> def.getToolCode().equals(item.toolCode()));
            if (!alreadyIncluded) {
                items.add(new AgentToolManifestItem(
                        def.getToolCode(),
                        def.getToolCode(),
                        def.getToolName(),
                        def.getToolType(),
                        def.getProviderCode(),
                        def.getSourceCode(),
                        def.getRiskLevel(),
                        def.getConfirmationPolicy(),
                        def.isRequiresApproval(),
                        def.isRequiresConfirmation(),
                        CanonicalJsonHasher.sha256Canonical(def.getParameterSchema())));
            }
        }
        return List.copyOf(items);
    }

    private List<AgentToolManifestItem> buildAgentToolManifest(List<AgentToolDefinition> tools) {
        List<AgentToolManifestItem> items = new ArrayList<>();
        for (AgentToolDefinition tool : tools) {
            if (tool == null || tool.getName() == null || tool.getName().isBlank()) {
                continue;
            }
            items.add(new AgentToolManifestItem(
                    tool.getName(),
                    tool.getName(),
                    null,
                    tool.getToolType(),
                    null,
                    tool.getSourceCode(),
                    tool.getRiskLevel(),
                    tool.getConfirmationPolicy(),
                    tool.isRequiresApproval(),
                    tool.isRequiresConfirmation(),
                    CanonicalJsonHasher.sha256Canonical(tool.getInputSchema())));
        }
        return List.copyOf(items);
    }

    private List<LlmChatRequest.Tool> toLlmTools(List<AgentToolDefinition> tools) {
        List<LlmChatRequest.Tool> out = new ArrayList<>();
        for (AgentToolDefinition tool : tools) {
            if (tool == null || tool.getName() == null || tool.getName().isBlank()) {
                continue;
            }
            out.add(LlmChatRequest.Tool.builder()
                    .name(tool.getName())
                    .description(tool.getDescription())
                    .inputSchema(tool.getInputSchema())
                    .build());
        }
        return out;
    }

    private Map<String, Object> sanitizePending(Map<String, Object> pending) {
        if (pending == null || pending.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : pending.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            if (isSensitivePendingKey(key)) {
                continue;
            }
            if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                out.put(key, value);
            } else {
                String hash = CanonicalJsonHasher.sha256Canonical(value);
                if (hash != null) {
                    out.put(key + "Hash", hash);
                }
            }
        }
        return Map.copyOf(out);
    }

    private boolean isSensitivePendingKey(String key) {
        String normalized = key.replace("-", "_").toLowerCase();
        for (String fragment : SENSITIVE_PENDING_KEY_FRAGMENTS) {
            if (normalized.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private List<Map<String, Object>> messageHashPayload(List<LlmChatRequest.Message> messages) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (LlmChatRequest.Message message : messages) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", message != null ? message.getRole() : null);
            item.put("content", message != null ? message.getContent() : null);
            out.add(item);
        }
        return out;
    }

    private List<Map<String, Object>> toolHashPayload(
            List<LlmChatRequest.Tool> llmTools,
            List<AgentToolManifestItem> toolManifest) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < llmTools.size(); i++) {
            LlmChatRequest.Tool llmTool = llmTools.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", llmTool != null ? llmTool.getName() : null);
            item.put("inputSchema", llmTool != null ? llmTool.getInputSchema() : null);
            item.put("nativeToolConfig", llmTool != null ? llmTool.getNativeToolConfig() : null);
            if (i < toolManifest.size()) {
                item.put("manifest", toolManifest.get(i).toSnapshotMap());
            }
            out.add(item);
        }
        return out;
    }

    private int messageChars(List<LlmChatRequest.Message> messages) {
        int total = 0;
        for (LlmChatRequest.Message message : messages) {
            if (message == null) {
                continue;
            }
            total += length(message.getRole());
            total += length(message.getContent());
        }
        return total;
    }

    private int estimateTokens(int chars) {
        if (chars <= 0) {
            return 0;
        }
        return (int) Math.ceil(chars / CHARS_PER_TOKEN);
    }

    private String hashText(String key, String value) {
        return CanonicalJsonHasher.sha256Canonical(Map.of(key, value == null ? "" : value));
    }

    private int length(Object value) {
        return value == null ? 0 : String.valueOf(value).length();
    }
}
