package com.auraboot.framework.agentchat.reply;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.entity.AgentDefinition;
import com.auraboot.framework.agent.mapper.AgentDefinitionMapper;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agentchat.handoff.HandoffResult;
import com.auraboot.framework.agentchat.handoff.HandoffToolProvider;
import com.auraboot.framework.agentchat.spi.AgentMemberDto;
import com.auraboot.framework.agentchat.spi.GroupChatMessagePort;
import com.auraboot.framework.agentchat.sse.SseEmitterManager;
import com.auraboot.framework.agentchat.sse.SseEventType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Executes agent reply in an async thread.
 * Handles typing indicators, context assembly, LLM invocation, and message saving.
 */
@Slf4j
@Component
public class AgentReplyTask {

    private static final int MAX_HANDOFF_DEPTH = 5;
    private static final int DEFAULT_CONTEXT_WINDOW = 20;

    private final AgentDefinitionMapper agentDefinitionMapper;
    private final GroupChatMessagePort messagePort;
    private final AgentReplyContext replyContext;
    private final SseEmitterManager sseEmitterManager;
    private final HandoffToolProvider handoffToolProvider;
    private final LlmProviderFactory llmProviderFactory;

    public AgentReplyTask(AgentDefinitionMapper agentDefinitionMapper,
                          GroupChatMessagePort messagePort,
                          AgentReplyContext replyContext,
                          SseEmitterManager sseEmitterManager,
                          HandoffToolProvider handoffToolProvider,
                          LlmProviderFactory llmProviderFactory) {
        this.agentDefinitionMapper = agentDefinitionMapper;
        this.messagePort = messagePort;
        this.replyContext = replyContext;
        this.sseEmitterManager = sseEmitterManager;
        this.handoffToolProvider = handoffToolProvider;
        this.llmProviderFactory = llmProviderFactory;
    }

    /**
     * Entry point for async agent reply execution.
     */
    @Async
    public void executeReply(Long conversationId, Long tenantId, Long agentId, String triggerContent) {
        executeReplyWithDepth(conversationId, tenantId, agentId, triggerContent, 0);
    }

    /**
     * Internal method with depth tracking for handoff chains.
     */
    private void executeReplyWithDepth(Long conversationId, Long tenantId, Long agentId,
                                        String triggerContent, int depth) {
        if (depth >= MAX_HANDOFF_DEPTH) {
            log.warn("Max handoff depth {} reached for conversation {}, agent {}",
                    MAX_HANDOFF_DEPTH, conversationId, agentId);
            return;
        }

        // 1. Load agent definition
        AgentDefinition agent = agentDefinitionMapper.selectById(agentId);
        if (agent == null) {
            log.warn("Agent {} not found, skipping reply for conversation {}", agentId, conversationId);
            return;
        }

        // 2. Get human member IDs for SSE push
        Set<Long> humanMemberIds = messagePort.getHumanMemberIds(conversationId, tenantId);

        // 3. Send TYPING indicator
        sseEmitterManager.sendToUsers(humanMemberIds, SseEventType.TYPING, Map.of(
                "conversationId", conversationId,
                "agentId", agentId,
                "agentName", agent.getName() != null ? agent.getName() : "AI"
        ));

        // 4. Build context
        int contextWindow = messagePort.getAiContextWindow(conversationId, tenantId);
        if (contextWindow <= 0) {
            contextWindow = DEFAULT_CONTEXT_WINDOW;
        }
        List<LlmChatRequest.Message> history = replyContext.buildHistory(conversationId, tenantId, contextWindow);

        // Build agent member DTO for system prompt
        AgentMemberDto agentDto = AgentMemberDto.builder()
                .agentId(agentId)
                .agentCode(agent.getAgentCode())
                .name(agent.getName())
                .employeeId(agent.getEmployeeId())
                .systemPrompt(agent.getSystemPrompt())
                .soulProfile(agent.getSoulProfile() != null ? agent.getSoulProfile().toString() : null)
                .tools(agent.getTools())
                .build();

        String systemPrompt = replyContext.buildSystemPrompt(agentDto, conversationId, tenantId);

        // 5. Build tools (agent's own + handoff if other agents exist)
        List<LlmChatRequest.Tool> tools = buildTools(conversationId, tenantId, agentId);

        // 6. Resolve LLM provider and config
        String providerCode = llmProviderFactory.resolveProviderByModel(agent.getModel());
        LlmProviderFactory.ProviderConfig providerConfig = llmProviderFactory.resolveConfig(tenantId, providerCode);
        if (providerConfig == null) {
            log.error("No LLM provider configured for tenant {}, agent {} (model={})",
                    tenantId, agent.getName(), agent.getModel());
            String errorMsg = "LLM provider not configured. Please configure an AI provider in Settings.";
            messagePort.saveAgentMessage(conversationId, tenantId, agentId, errorMsg, null);
            sseEmitterManager.sendToUsers(humanMemberIds, SseEventType.STREAM_END, Map.of(
                    "conversationId", conversationId,
                    "agentId", agentId,
                    "error", errorMsg
            ));
            return;
        }

        String model = agent.getModel() != null ? agent.getModel()
                : providerConfig.getDefaultModel();

        LlmChatRequest request = LlmChatRequest.builder()
                .model(model)
                .providerCode(providerConfig.getProviderCode())
                .systemPrompt(systemPrompt)
                .messages(history)
                .tools(tools.isEmpty() ? null : tools)
                .maxTokens(providerConfig.getMaxTokens() > 0 ? providerConfig.getMaxTokens() : 4096)
                .build();

        // 7. Call LLM and process response
        LlmChatResponse response;
        try {
            LlmProvider provider = llmProviderFactory.getProvider(providerConfig.getProviderCode());
            log.info("Agent {} calling LLM (provider={}, model={}) for conversation {} (depth={}, tools={})",
                    agent.getName(), providerConfig.getProviderCode(), model,
                    conversationId, depth, tools.size());

            response = provider.chat(request, providerConfig.getApiKey(), providerConfig.getBaseUrl());
        } catch (Exception e) {
            // CATCH: non-transactional — LLM HTTP call failure, safe to handle and report to user
            log.error("LLM call failed for agent {} in conversation {}: {}",
                    agent.getName(), conversationId, e.getMessage(), e);
            String errorMsg = "Sorry, I encountered an error while processing your request.";
            messagePort.saveAgentMessage(conversationId, tenantId, agentId, errorMsg, null);
            sseEmitterManager.sendToUsers(humanMemberIds, SseEventType.STREAM_END, Map.of(
                    "conversationId", conversationId,
                    "agentId", agentId,
                    "error", e.getMessage() != null ? e.getMessage() : "LLM call failed"
            ));
            return;
        }

        log.info("Agent {} received LLM response: stopReason={}, contentBlocks={}, tokens={}/{}",
                agent.getName(), response.getStopReason(),
                response.getContent() != null ? response.getContent().size() : 0,
                response.getInputTokens(), response.getOutputTokens());

        // 8. Process response based on stopReason
        if ("tool_use".equals(response.getStopReason())) {
            handleToolUseResponse(response, conversationId, tenantId, agentId, agent, humanMemberIds, depth);
        } else {
            // "end_turn" or "max_tokens" — extract text and save
            String replyContent = extractTextContent(response);
            if (replyContent == null || replyContent.isBlank()) {
                replyContent = "[No response generated]";
            }

            // Send content as STREAM_CHUNK for frontend rendering
            sseEmitterManager.sendToUsers(humanMemberIds, SseEventType.STREAM_CHUNK, Map.of(
                    "conversationId", conversationId,
                    "agentId", agentId,
                    "agentName", agent.getName() != null ? agent.getName() : "AI",
                    "content", replyContent
            ));

            // Save the reply message
            messagePort.saveAgentMessage(conversationId, tenantId, agentId, replyContent, null);

            // Send STREAM_END
            sseEmitterManager.sendToUsers(humanMemberIds, SseEventType.STREAM_END, Map.of(
                    "conversationId", conversationId,
                    "agentId", agentId,
                    "agentName", agent.getName() != null ? agent.getName() : "AI"
            ));
        }
    }

    /**
     * Handle tool_use response — currently supports transfer_to_agent (handoff).
     * Other tool calls are logged and skipped (future work).
     */
    private void handleToolUseResponse(LlmChatResponse response, Long conversationId, Long tenantId,
                                        Long agentId, AgentDefinition agent, Set<Long> humanMemberIds, int depth) {
        if (response.getContent() == null) return;

        for (LlmChatResponse.ContentBlock block : response.getContent()) {
            if (!"tool_use".equals(block.getType())) continue;

            if ("transfer_to_agent".equals(block.getName())) {
                // Build agent lookup map
                List<AgentMemberDto> allAgents = messagePort.getAgentMembers(conversationId, tenantId);
                Map<String, AgentMemberDto> agentByCode = allAgents.stream()
                        .collect(Collectors.toMap(AgentMemberDto::getAgentCode, a -> a, (a1, a2) -> a1));

                HandoffResult result = handoffToolProvider.execute(block.getInput(), agentByCode);

                if (result.isSuccess()) {
                    // Save handoff message from current agent
                    AgentMemberDto targetAgent = agentByCode.get(result.getTargetAgentCode());
                    String targetName = targetAgent != null ? targetAgent.getName() : result.getTargetAgentCode();
                    String handoffMsg = "Handing off to " + targetName + "...";

                    messagePort.saveAgentMessage(conversationId, tenantId, agentId, handoffMsg, null);

                    // Send STREAM_CHUNK + STREAM_END for the handoff message
                    sseEmitterManager.sendToUsers(humanMemberIds, SseEventType.STREAM_CHUNK, Map.of(
                            "conversationId", conversationId,
                            "agentId", agentId,
                            "agentName", agent.getName() != null ? agent.getName() : "AI",
                            "content", handoffMsg
                    ));
                    sseEmitterManager.sendToUsers(humanMemberIds, SseEventType.STREAM_END, Map.of(
                            "conversationId", conversationId,
                            "agentId", agentId,
                            "agentName", agent.getName() != null ? agent.getName() : "AI"
                    ));

                    // Recursively execute reply with the target agent
                    String handoffContext = result.getContext() != null ? result.getContext() : "";
                    executeReplyWithDepth(conversationId, tenantId, result.getTargetAgentId(),
                            handoffContext, depth + 1);
                    return;
                } else {
                    log.warn("Handoff failed for agent {} in conversation {}: {}",
                            agent.getName(), conversationId, result.getError());
                    // Let the agent know the handoff failed — save a message and end
                    String errorMsg = "I tried to transfer to another agent but it failed: " + result.getError();
                    messagePort.saveAgentMessage(conversationId, tenantId, agentId, errorMsg, null);
                    sseEmitterManager.sendToUsers(humanMemberIds, SseEventType.STREAM_END, Map.of(
                            "conversationId", conversationId,
                            "agentId", agentId,
                            "agentName", agent.getName() != null ? agent.getName() : "AI"
                    ));
                    return;
                }
            } else {
                log.info("Agent {} called unsupported tool '{}' in conversation {} — skipping (future work)",
                        agent.getName(), block.getName(), conversationId);
            }
        }

        // If we get here, no actionable tool calls were found — extract any text content
        String textContent = extractTextContent(response);
        if (textContent != null && !textContent.isBlank()) {
            sseEmitterManager.sendToUsers(humanMemberIds, SseEventType.STREAM_CHUNK, Map.of(
                    "conversationId", conversationId,
                    "agentId", agentId,
                    "agentName", agent.getName() != null ? agent.getName() : "AI",
                    "content", textContent
            ));
            messagePort.saveAgentMessage(conversationId, tenantId, agentId, textContent, null);
        }

        sseEmitterManager.sendToUsers(humanMemberIds, SseEventType.STREAM_END, Map.of(
                "conversationId", conversationId,
                "agentId", agentId,
                "agentName", agent.getName() != null ? agent.getName() : "AI"
        ));
    }

    /**
     * Extract concatenated text content from LLM response content blocks.
     */
    private String extractTextContent(LlmChatResponse response) {
        if (response.getContent() == null || response.getContent().isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (LlmChatResponse.ContentBlock block : response.getContent()) {
            if ("text".equals(block.getType()) && block.getText() != null) {
                if (!sb.isEmpty()) sb.append("\n");
                sb.append(block.getText());
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /**
     * Build tool list: agent's own tools + handoff tool if other agents exist.
     */
    private List<LlmChatRequest.Tool> buildTools(Long conversationId, Long tenantId, Long currentAgentId) {
        List<LlmChatRequest.Tool> tools = new ArrayList<>();

        // Check for other agents to enable handoff
        List<AgentMemberDto> allAgents = messagePort.getAgentMembers(conversationId, tenantId);
        List<AgentMemberDto> otherAgents = allAgents.stream()
                .filter(a -> !a.getAgentId().equals(currentAgentId))
                .toList();

        if (!otherAgents.isEmpty()) {
            LlmChatRequest.Tool handoffTool = handoffToolProvider.getToolDefinition(otherAgents);
            if (handoffTool != null) {
                tools.add(handoffTool);
            }
        }

        // TODO: Add agent's own tools from agent definition

        return tools;
    }
}
