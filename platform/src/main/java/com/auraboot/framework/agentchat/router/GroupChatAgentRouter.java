package com.auraboot.framework.agentchat.router;

import com.auraboot.framework.agentchat.event.ImMessageSentEvent;
import com.auraboot.framework.agentchat.reply.AgentReplyTask;
import com.auraboot.framework.agentchat.spi.AgentMemberDto;
import com.auraboot.framework.agentchat.spi.GroupChatMessagePort;
import com.auraboot.framework.agentchat.spi.NoOpGroupChatMessagePort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Routes incoming group chat messages to the appropriate AI agents.
 * Priority: P0 explicit mentions > P1 always-reply agents > P2 conductor agent > P3 no response.
 */
@Slf4j
@Component
public class GroupChatAgentRouter {

    private static final String SENDER_TYPE_HUMAN = "human";
    private static final String CONVERSATION_TYPE_GROUP = "group";
    private static final Pattern AGENT_MENTION_PATTERN = Pattern.compile("agent:(\\d+)");

    private final GroupChatMessagePort messagePort;
    private final AgentReplyTask agentReplyTask;

    public GroupChatAgentRouter(ObjectProvider<GroupChatMessagePort> messagePortProvider,
                                AgentReplyTask agentReplyTask) {
        this.messagePort = messagePortProvider.getIfAvailable(NoOpGroupChatMessagePort::new);
        this.agentReplyTask = agentReplyTask;
    }

    @Async
    @EventListener
    public void onMessageSent(ImMessageSentEvent event) {
        // Only process GROUP conversations from HUMAN senders
        if (!CONVERSATION_TYPE_GROUP.equals(event.getConversationType())) {
            return;
        }
        if (!SENDER_TYPE_HUMAN.equals(event.getSenderType())) {
            return;
        }

        Long conversationId = event.getConversationId();
        Long tenantId = event.getTenantId();

        // Skip if no agent members in this conversation
        if (!messagePort.hasAgentMembers(conversationId, tenantId)) {
            return;
        }

        List<Long> targetAgentIds = resolveTargetAgents(
                conversationId, tenantId, event.getContent(), event.getMentions());

        if (targetAgentIds.isEmpty()) {
            log.debug("No target agents resolved for conversation {} message {}", conversationId, event.getMessageId());
            return;
        }

        for (Long agentId : targetAgentIds) {
            agentReplyTask.executeReply(conversationId, tenantId, agentId, event.getContent());
        }
    }

    /**
     * Resolve which agents should reply based on priority:
     * P0: Explicit @mentions with "agent:ID" format
     * P1: Agents with autoReplyMode=ALWAYS
     * P2: Conductor agent of the conversation
     * P3: Empty list (no AI response)
     */
    private List<Long> resolveTargetAgents(Long conversationId, Long tenantId,
                                            String content, List<String> mentions) {
        List<AgentMemberDto> agentMembers = messagePort.getAgentMembers(conversationId, tenantId);
        if (agentMembers.isEmpty()) {
            return List.of();
        }

        // P0: Parse explicit mentions for "agent:ID" format
        Set<Long> mentionedAgentIds = parseMentionedAgentIds(mentions, agentMembers);
        if (!mentionedAgentIds.isEmpty()) {
            return new ArrayList<>(mentionedAgentIds);
        }

        // P1: Find agents with autoReplyMode=ALWAYS
        List<Long> alwaysReplyAgents = agentMembers.stream()
                .filter(a -> AgentReplyMode.ALWAYS.code().equals(a.getAutoReplyMode()))
                .map(AgentMemberDto::getAgentId)
                .toList();
        if (!alwaysReplyAgents.isEmpty()) {
            return alwaysReplyAgents;
        }

        // P2: Conductor agent
        Long conductorId = messagePort.getConductorAgentId(conversationId, tenantId);
        if (conductorId != null) {
            return List.of(conductorId);
        }

        // P3: No response
        return List.of();
    }

    /**
     * Parse "agent:ID" mentions and match them against available agent members.
     */
    private Set<Long> parseMentionedAgentIds(List<String> mentions, List<AgentMemberDto> agentMembers) {
        if (mentions == null || mentions.isEmpty()) {
            return Set.of();
        }

        Set<Long> availableIds = new HashSet<>();
        for (AgentMemberDto agent : agentMembers) {
            availableIds.add(agent.getAgentId());
        }

        Set<Long> result = new HashSet<>();
        for (String mention : mentions) {
            Matcher matcher = AGENT_MENTION_PATTERN.matcher(mention);
            if (matcher.matches()) {
                Long agentId = Long.parseLong(matcher.group(1));
                if (availableIds.contains(agentId)) {
                    result.add(agentId);
                }
            }
        }
        return result;
    }
}
