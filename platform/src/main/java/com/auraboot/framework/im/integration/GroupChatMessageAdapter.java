package com.auraboot.framework.im.integration;

import com.auraboot.framework.agent.entity.AgentDefinition;
import com.auraboot.framework.agent.mapper.AgentDefinitionMapper;
import com.auraboot.framework.agentchat.spi.AgentMemberDto;
import com.auraboot.framework.agentchat.spi.ChatMessageDto;
import com.auraboot.framework.agentchat.spi.ConfirmationPayload;
import com.auraboot.framework.agentchat.spi.GroupChatMessagePort;
import com.auraboot.framework.im.mapper.ImConversationMapper;
import com.auraboot.framework.im.mapper.ImConversationMemberMapper;
import com.auraboot.framework.im.mapper.ImMessageMapper;
import com.auraboot.framework.im.model.ImConstants;
import com.auraboot.framework.im.model.ImConversation;
import com.auraboot.framework.im.model.ImConversationMember;
import com.auraboot.framework.im.model.ImMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Enterprise SPI implementation of GroupChatMessagePort.
 * Bridges the core agent-chat module with the enterprise IM module.
 */
@Component
@RequiredArgsConstructor
public class GroupChatMessageAdapter implements GroupChatMessagePort {

    private static final Logger log = LoggerFactory.getLogger(GroupChatMessageAdapter.class);

    private static final int DEFAULT_AI_CONTEXT_WINDOW = 50;
    private static final String MESSAGE_TYPE_AI_RESPONSE = "ai_response";
    private static final String MESSAGE_TYPE_CONFIRMATION = "confirmation_card";

    private final ImMessageMapper messageMapper;
    private final ImConversationMemberMapper memberMapper;
    private final ImConversationMapper conversationMapper;
    private final AgentDefinitionMapper agentDefinitionMapper;
    private final ObjectMapper objectMapper;

    /**
     * Retrieve recent messages in chronological order (oldest first) for AI context.
     * Uses findBeforeSeq with Long.MAX_VALUE to get the latest messages, then reverses.
     */
    @Override
    public List<ChatMessageDto> getRecentMessages(Long conversationId, Long tenantId, int limit) {
        List<ImMessage> messages = messageMapper.findBeforeSeq(conversationId, tenantId, Long.MAX_VALUE, limit);
        // findBeforeSeq returns DESC order; reverse to chronological (oldest first)
        List<ImMessage> chronological = new ArrayList<>(messages);
        Collections.reverse(chronological);

        List<ChatMessageDto> result = new ArrayList<>(chronological.size());
        for (ImMessage msg : chronological) {
            List<String> mentions = parseMentions(msg.getMentions());
            result.add(ChatMessageDto.builder()
                    .id(msg.getId())
                    .conversationId(msg.getConversationId())
                    .senderType(msg.getSenderType())
                    .senderId(msg.getSenderId())
                    .senderName(null)   // name resolution handled by caller if needed
                    .senderAvatar(null)
                    .seq(msg.getSeq())
                    .messageType(msg.getMessageType())
                    .content(msg.getContent())
                    .cardPayload(msg.getCardPayload())
                    .mentions(mentions)
                    .createdAt(msg.getCreatedAt())
                    .build());
        }
        return result;
    }

    /**
     * Return all agent members of the conversation, enriched with AgentDefinition data.
     */
    @Override
    public List<AgentMemberDto> getAgentMembers(Long conversationId, Long tenantId) {
        List<ImConversationMember> agentMembers = memberMapper.findAgentMembers(conversationId, tenantId);
        if (agentMembers.isEmpty()) {
            return Collections.emptyList();
        }

        List<AgentMemberDto> result = new ArrayList<>(agentMembers.size());
        for (ImConversationMember member : agentMembers) {
            AgentDefinition agent = agentDefinitionMapper.selectById(member.getMemberId());
            if (agent == null) {
                log.warn("AgentDefinition not found for memberId={} in conversation={}", member.getMemberId(), conversationId);
                continue;
            }

            String soulProfileJson = null;
            if (agent.getSoulProfile() != null) {
                soulProfileJson = toJson(agent.getSoulProfile());
            }

            result.add(AgentMemberDto.builder()
                    .agentId(agent.getId())
                    .agentCode(agent.getAgentCode())
                    .name(agent.getName())
                    .employeeId(agent.getEmployeeId())
                    .avatarUrl(agent.getAvatarUrl())
                    .autoReplyMode(agent.getAutoReplyMode())
                    .systemPrompt(agent.getSystemPrompt())
                    .soulProfile(soulProfileJson)
                    .tools(agent.getTools())
                    .build());
        }
        return result;
    }

    /**
     * Return true if the conversation has at least one agent member.
     */
    @Override
    public boolean hasAgentMembers(Long conversationId, Long tenantId) {
        List<ImConversationMember> agentMembers = memberMapper.findAgentMembers(conversationId, tenantId);
        return !agentMembers.isEmpty();
    }

    /**
     * Return the conductor agent ID from the conversation record.
     * Returns null if conversation not found or no conductor set.
     */
    @Override
    public Long getConductorAgentId(Long conversationId, Long tenantId) {
        ImConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            return null;
        }
        return conversation.getConductorAgentId();
    }

    /**
     * Return the AI context window size from the conversation record.
     * Falls back to DEFAULT_AI_CONTEXT_WINDOW if not set.
     */
    @Override
    public int getAiContextWindow(Long conversationId, Long tenantId) {
        ImConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || conversation.getAiContextWindow() == null) {
            return DEFAULT_AI_CONTEXT_WINDOW;
        }
        return conversation.getAiContextWindow();
    }

    /**
     * Save an agent-authored message directly into the IM store.
     * Increments the conversation seq atomically and inserts a message with
     * senderType=agent and the given agentId as senderId.
     * Returns the new message ID.
     */
    @Override
    @Transactional
    public Long saveAgentMessage(Long conversationId, Long tenantId, Long agentId,
                                  String content, String cardPayload) {
        // Increment seq atomically
        conversationMapper.incrementSeq(conversationId, tenantId);
        ImConversation conv = conversationMapper.selectById(conversationId);
        long newSeq = conv.getMaxSeq();

        ImMessage message = new ImMessage();
        message.setConversationId(conversationId);
        message.setTenantId(tenantId);
        message.setSenderId(agentId);
        message.setSenderType(ImConstants.SENDER_TYPE_AGENT);
        message.setSeq(newSeq);
        message.setMessageType(MESSAGE_TYPE_AI_RESPONSE);
        message.setContent(content);
        message.setCardPayload(cardPayload);
        message.setRecalled(false);
        message.setCreatedAt(Instant.now());

        messageMapper.insert(message);
        return message.getId();
    }

    /**
     * Serialize a ConfirmationPayload to JSON and save it as an agent card message.
     * Returns the new message ID.
     */
    @Override
    @Transactional
    public Long saveConfirmationCard(Long conversationId, Long tenantId, Long agentId,
                                      ConfirmationPayload payload) {
        String cardPayloadJson = toJson(payload);
        // Increment seq atomically
        conversationMapper.incrementSeq(conversationId, tenantId);
        ImConversation conv = conversationMapper.selectById(conversationId);
        long newSeq = conv.getMaxSeq();

        ImMessage message = new ImMessage();
        message.setConversationId(conversationId);
        message.setTenantId(tenantId);
        message.setSenderId(agentId);
        message.setSenderType(ImConstants.SENDER_TYPE_AGENT);
        message.setSeq(newSeq);
        message.setMessageType(MESSAGE_TYPE_CONFIRMATION);
        message.setContent(payload.getDescription());
        message.setCardPayload(cardPayloadJson);
        message.setRecalled(false);
        message.setCreatedAt(Instant.now());

        messageMapper.insert(message);
        return message.getId();
    }

    @Override
    public Set<Long> getHumanMemberIds(Long conversationId, Long tenantId) {
        List<Long> ids = memberMapper.findHumanMemberIds(conversationId, tenantId);
        return new HashSet<>(ids);
    }

    // ---- helpers ----

    @SuppressWarnings("unchecked")
    private List<String> parseMentions(String mentionsJson) {
        if (mentionsJson == null || mentionsJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(mentionsJson, List.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse mentions JSON: {}", mentionsJson);
            return Collections.emptyList();
        }
    }

    private String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize object to JSON: {}", obj.getClass().getSimpleName(), e);
            return null;
        }
    }
}
