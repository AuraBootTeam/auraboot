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

        RoutingResult routing = resolveTargetAgents(
                conversationId, tenantId, event.getContent(), event.getMentions());

        if (routing.targetAgentId() == null) {
            log.debug("No target agent resolved for conversation {} message {}", conversationId, event.getMessageId());
            return;
        }

        // G1: single target — bypassed mentions are passed for context (T9 will thread to AgentReplyTask)
        agentReplyTask.executeReply(conversationId, tenantId, routing.targetAgentId(),
                event.getContent(), event.getSeq());
    }

    /**
     * Resolve which agent should reply based on priority:
     * P0: Explicit @mentions — first mentioned is target, rest are bypassed
     * P1: Agents with autoReplyMode=ALWAYS — first is target, rest bypassed
     * P2: Conductor agent of the conversation
     * P3: No response (RoutingResult.none())
     */
    RoutingResult resolveTargetAgents(Long conversationId, Long tenantId,
                                       String content, List<String> mentions) {
        List<AgentMemberDto> agentMembers = messagePort.getAgentMembers(conversationId, tenantId);
        if (agentMembers.isEmpty()) {
            return RoutingResult.none();
        }

        // P0: Parse explicit mentions for "agent:ID" format (preserve input order for deterministic target)
        List<Long> mentionedAgentIds = parseMentionedAgentIds(mentions, agentMembers);
        if (!mentionedAgentIds.isEmpty()) {
            Long target = mentionedAgentIds.get(0);
            List<Long> bypassed = mentionedAgentIds.size() > 1
                    ? mentionedAgentIds.subList(1, mentionedAgentIds.size()) : List.of();
            return new RoutingResult(target, bypassed, "P0");
        }

        // P1: Find agents with autoReplyMode=ALWAYS
        List<Long> alwaysReplyAgents = agentMembers.stream()
                .filter(a -> AgentReplyMode.ALWAYS.code().equals(a.getAutoReplyMode()))
                .map(AgentMemberDto::getAgentId)
                .toList();
        if (!alwaysReplyAgents.isEmpty()) {
            Long target = alwaysReplyAgents.get(0);
            List<Long> bypassed = alwaysReplyAgents.size() > 1
                    ? alwaysReplyAgents.subList(1, alwaysReplyAgents.size()) : List.of();
            return new RoutingResult(target, bypassed, "P1");
        }

        // P2: Conductor agent
        Long conductorId = messagePort.getConductorAgentId(conversationId, tenantId);
        if (conductorId != null) {
            return new RoutingResult(conductorId, List.of(), "P2");
        }

        // P3: No response
        return RoutingResult.none();
    }

    /**
     * Parse "agent:ID" mentions and match them against available agent members.
     * Returns a list in input order (deduped), so the first element is deterministically
     * the first mentioned agent.
     */
    private List<Long> parseMentionedAgentIds(List<String> mentions, List<AgentMemberDto> agentMembers) {
        if (mentions == null || mentions.isEmpty()) {
            return List.of();
        }

        Set<Long> availableIds = new HashSet<>();
        for (AgentMemberDto agent : agentMembers) {
            availableIds.add(agent.getAgentId());
        }

        // LinkedHashSet preserves input order while deduplicating
        Set<Long> result = new java.util.LinkedHashSet<>();
        for (String mention : mentions) {
            Matcher matcher = AGENT_MENTION_PATTERN.matcher(mention);
            if (matcher.matches()) {
                Long agentId = Long.parseLong(matcher.group(1));
                if (availableIds.contains(agentId)) {
                    result.add(agentId);
                }
            }
        }
        return new ArrayList<>(result);
    }
}
