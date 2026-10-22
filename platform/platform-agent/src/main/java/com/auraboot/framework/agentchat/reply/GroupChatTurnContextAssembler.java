package com.auraboot.framework.agentchat.reply;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agentchat.spi.AgentMemberDto;
import com.auraboot.framework.agentchat.spi.ChatMessageDto;
import com.auraboot.framework.agentchat.spi.GroupChatMessagePort;
import com.auraboot.framework.agentchat.spi.NoOpGroupChatMessagePort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Public utility for assembling group-chat LLM context — system prompts,
 * history, and (in future) tool lists — from {@code ab_im_message} +
 * {@code ab_agent_definition} state.
 *
 * <p>Renamed in DC.3b (Q-DC.1=A' / design v5 §10.8) from
 * {@code AgentReplyContext} to make the role explicit: <b>any caller that
 * needs to feed group-chat context into
 * {@link com.auraboot.framework.agent.port.AgentChatPort#runAgentTurn}
 * SHOULD use this class via the
 * {@link com.auraboot.framework.agent.port.AgentTurnOverrides} side channel</b>
 * — instead of duplicating the assembly logic in each caller. Today the only
 * caller is {@code AgentReplyTask}; the rename anticipates DC.3c routing it
 * through the chokepoint and future group-chat callers (e.g. webhook
 * triggers, scheduled agents) reusing the same assembler.
 *
 * <h2>What this assembler is responsible for</h2>
 *
 * <ul>
 *   <li>{@link #buildHistory(Long, Long, int)} — load recent
 *       {@code ab_im_message} rows for the conversation, map sender_type to
 *       role (agent → assistant, anything else → user), prefix sender name
 *       so the LLM sees who said what in a multi-agent setting.</li>
 *   <li>{@link #buildSystemPrompt(AgentMemberDto, Long, Long)} — combine the
 *       agent's own system_prompt + soul profile with a roster of OTHER
 *       agents in the conversation, plus a note about the
 *       {@code transfer_to_agent} tool.</li>
 * </ul>
 *
 * <h2>What this assembler is NOT responsible for</h2>
 *
 * <ul>
 *   <li>Resolving {@code AgentDefinition} from the database — caller does that.</li>
 *   <li>Building {@code AgentTurnOverrides} or {@code ChatRequest} — caller
 *       composes the assembler's outputs into those objects.</li>
 *   <li>Calling the LLM provider — that lives in {@code AgentChatPortImpl}'s
 *       tool loop (chokepoint single LLM gateway, per design v5 §10.5
 *       option A' rationale).</li>
 * </ul>
 *
 * <p>Module dependency direction: this class lives in {@code agentchat.reply}
 * (application layer); it depends ONLY on {@code agent.dto.LlmChatRequest}
 * (data class) and {@code agentchat.spi.*} (its own module's SPI). It does
 * NOT depend on {@code agent.service} or {@code agent.port} — keeping the
 * existing {@code agentchat → agent} dependency direction intact and avoiding
 * the cross-module reverse dependency that v4 option B would have introduced.
 */
@Slf4j
@Component
public class GroupChatTurnContextAssembler {

    private static final String SENDER_TYPE_AGENT = "agent";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String ROLE_USER = "user";

    private final GroupChatMessagePort messagePort;

    public GroupChatTurnContextAssembler(ObjectProvider<GroupChatMessagePort> messagePortProvider) {
        this.messagePort = messagePortProvider.getIfAvailable(NoOpGroupChatMessagePort::new);
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
