package com.auraboot.framework.agentchat.reply;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agentchat.spi.AgentMemberDto;
import com.auraboot.framework.agentchat.spi.ChatMessageDto;
import com.auraboot.framework.agentchat.spi.GroupChatMessagePort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Assembles context for LLM calls in group chat scenarios.
 * Builds chat history and system prompts with agent awareness.
 */
@Slf4j
@Component
public class AgentReplyContext {

    private static final String SENDER_TYPE_AGENT = "agent";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String ROLE_USER = "user";

    private final GroupChatMessagePort messagePort;

    public AgentReplyContext(GroupChatMessagePort messagePort) {
        this.messagePort = messagePort;
    }

    /**
     * Build chat history from recent messages, mapping to LLM message format.
     * Agent messages become "assistant" role, human messages become "user" role.
     * Each message is prefixed with the sender's name for context.
     */
    public List<LlmChatRequest.Message> buildHistory(Long conversationId, Long tenantId, int limit) {
        List<ChatMessageDto> recentMessages = messagePort.getRecentMessages(conversationId, tenantId, limit);
        List<LlmChatRequest.Message> history = new ArrayList<>();

        for (ChatMessageDto msg : recentMessages) {
            String role = SENDER_TYPE_AGENT.equals(msg.getSenderType()) ? ROLE_ASSISTANT : ROLE_USER;
            String prefixedContent = "[" + msg.getSenderName() + "]: " + msg.getContent();

            history.add(LlmChatRequest.Message.builder()
                    .role(role)
                    .content(prefixedContent)
                    .build());
        }

        return history;
    }

    /**
     * Build system prompt combining agent's own prompt, soul profile, and group context.
     * Introduces other agents in the conversation so the LLM knows who else is present.
     */
    public String buildSystemPrompt(AgentMemberDto agent, Long conversationId, Long tenantId) {
        StringBuilder prompt = new StringBuilder();

        // Agent's own system prompt
        if (agent.getSystemPrompt() != null && !agent.getSystemPrompt().isBlank()) {
            prompt.append(agent.getSystemPrompt()).append("\n\n");
        }

        // Soul profile
        if (agent.getSoulProfile() != null && !agent.getSoulProfile().isBlank()) {
            prompt.append("Your personality profile:\n").append(agent.getSoulProfile()).append("\n\n");
        }

        // Introduce other agents in the group
        List<AgentMemberDto> allAgents = messagePort.getAgentMembers(conversationId, tenantId);
        List<AgentMemberDto> otherAgents = allAgents.stream()
                .filter(a -> !a.getAgentId().equals(agent.getAgentId()))
                .toList();

        if (!otherAgents.isEmpty()) {
            prompt.append("Other AI agents in this conversation:\n");
            for (AgentMemberDto other : otherAgents) {
                prompt.append("- ").append(other.getName());
                if (other.getEmployeeTitle() != null && !other.getEmployeeTitle().isBlank()) {
                    prompt.append(" (").append(other.getEmployeeTitle()).append(")");
                }
                prompt.append("\n");
            }
            prompt.append("\nYou can use the transfer_to_agent tool to hand off tasks to other agents if needed.\n");
        }

        return prompt.toString().trim();
    }
}
