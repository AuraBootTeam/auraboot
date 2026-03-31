package com.auraboot.framework.agentchat.reply;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.entity.AgentDefinition;
import com.auraboot.framework.agent.mapper.AgentDefinitionMapper;
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

    public AgentReplyTask(AgentDefinitionMapper agentDefinitionMapper,
                          GroupChatMessagePort messagePort,
                          AgentReplyContext replyContext,
                          SseEmitterManager sseEmitterManager,
                          HandoffToolProvider handoffToolProvider) {
        this.agentDefinitionMapper = agentDefinitionMapper;
        this.messagePort = messagePort;
        this.replyContext = replyContext;
        this.sseEmitterManager = sseEmitterManager;
        this.handoffToolProvider = handoffToolProvider;
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
                .employeeTitle(agent.getEmployeeTitle())
                .systemPrompt(agent.getSystemPrompt())
                .soulProfile(agent.getSoulProfile() != null ? agent.getSoulProfile().toString() : null)
                .tools(agent.getTools())
                .build();

        String systemPrompt = replyContext.buildSystemPrompt(agentDto, conversationId, tenantId);

        // 5. Build tools (agent's own + handoff if other agents exist)
        List<LlmChatRequest.Tool> tools = buildTools(conversationId, tenantId, agentId);

        // 6. Build LLM request (placeholder — actual LLM wiring in integration task)
        LlmChatRequest request = LlmChatRequest.builder()
                .model(agent.getModel())
                .systemPrompt(systemPrompt)
                .messages(history)
                .tools(tools.isEmpty() ? null : tools)
                .maxTokens(4096)
                .build();

        // TODO: Call LLM provider and process response (streaming or non-streaming)
        // For now, generate a placeholder reply
        String replyContent = "[Agent " + agent.getName() + " is thinking... LLM integration pending]";
        log.info("Agent {} generating reply for conversation {} (depth={}, tools={})",
                agent.getName(), conversationId, depth, tools.size());

        // 7. Save reply message via SPI
        messagePort.saveAgentMessage(conversationId, tenantId, agentId, replyContent, null);

        // 8. Send STREAM_END to all human members
        sseEmitterManager.sendToUsers(humanMemberIds, SseEventType.STREAM_END, Map.of(
                "conversationId", conversationId,
                "agentId", agentId,
                "agentName", agent.getName() != null ? agent.getName() : "AI"
        ));
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
