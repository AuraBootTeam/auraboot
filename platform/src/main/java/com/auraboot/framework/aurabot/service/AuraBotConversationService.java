package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.agent.entity.AgentDefinition;
import com.auraboot.framework.agent.mapper.AgentDefinitionMapper;
import com.auraboot.framework.aurabot.dto.AuraBotConversationItem;
import com.auraboot.framework.aurabot.dto.AuraBotConversationMessage;
import com.auraboot.framework.im.dto.SendMessageRequest;
import com.auraboot.framework.im.mapper.ImConversationMapper;
import com.auraboot.framework.im.mapper.ImConversationMemberMapper;
import com.auraboot.framework.im.mapper.ImMessageMapper;
import com.auraboot.framework.im.model.ImConstants;
import com.auraboot.framework.im.model.ImConversation;
import com.auraboot.framework.im.model.ImConversationMember;
import com.auraboot.framework.im.model.ImMessage;
import com.auraboot.framework.im.service.ImConversationService;
import com.auraboot.framework.im.service.ImMessageService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class AuraBotConversationService {

    private static final String CHAT_KIND_KEY = "chat_kind";
    private static final String CHAT_KIND_VALUE = "aurabot_panel";
    private static final String AGENT_CODE_KEY = "agent_code";
    private static final String AGENT_NAME_KEY = "agent_name";

    private final ImConversationMapper conversationMapper;
    private final ImConversationMemberMapper memberMapper;
    private final ImMessageMapper messageMapper;
    private final ImConversationService imConversationService;
    private final ImMessageService imMessageService;
    private final AgentDefinitionMapper agentDefinitionMapper;
    private final ObjectMapper objectMapper;

    public List<AuraBotConversationItem> listConversations(Long tenantId, Long memberId) {
        List<Long> conversationIds = memberMapper.findVisibleConversationIdsByMember(
                tenantId, ImConstants.MEMBER_TYPE_HUMAN, memberId);
        if (conversationIds.isEmpty()) {
            return List.of();
        }

        List<AuraBotConversationItem> items = new ArrayList<>();
        for (Long conversationId : conversationIds) {
            ImConversation conversation = conversationMapper.selectById(conversationId);
            if (conversation == null || !isAuraBotConversation(conversation)) {
                continue;
            }
            items.add(toConversationItem(conversation, tenantId));
        }
        items.sort((a, b) -> {
            Instant at = a.getUpdatedAt() != null ? a.getUpdatedAt() : Instant.EPOCH;
            Instant bt = b.getUpdatedAt() != null ? b.getUpdatedAt() : Instant.EPOCH;
            return bt.compareTo(at);
        });
        return items;
    }

    @Transactional
    public AuraBotConversationItem ensureConversation(Long tenantId, Long memberId, String agentCode) {
        String resolvedAgentCode = (agentCode == null || agentCode.isBlank()) ? "aurabot" : agentCode.trim();

        List<Long> conversationIds = memberMapper.findConversationIdsByMember(
                tenantId, ImConstants.MEMBER_TYPE_HUMAN, memberId);
        for (Long conversationId : conversationIds) {
            ImConversation existing = conversationMapper.selectById(conversationId);
            if (existing != null && isAuraBotConversation(existing)
                    && resolvedAgentCode.equals(readMetadata(existing).get(AGENT_CODE_KEY))) {
                return toConversationItem(existing, tenantId);
            }
        }

        ImConversation conversation = new ImConversation();
        conversation.setTenantId(tenantId);
        conversation.setType(ImConstants.TYPE_BOT);
        conversation.setOwnerId(memberId);
        conversation.setName(resolveAgentName(tenantId, resolvedAgentCode));
        conversation.setMaxSeq(0L);
        conversation.setCreatedAt(Instant.now());
        conversation.setUpdatedAt(Instant.now());
        conversation.setMetadata(writeMetadata(Map.of(
                CHAT_KIND_KEY, CHAT_KIND_VALUE,
                AGENT_CODE_KEY, resolvedAgentCode,
                AGENT_NAME_KEY, conversation.getName()
        )));

        AgentDefinition agent = resolveAgentDefinition(tenantId, resolvedAgentCode);
        if (agent != null) {
            conversation.setConductorAgentId(agent.getId());
        }
        conversationMapper.insert(conversation);

        ImConversationMember humanMember = new ImConversationMember();
        humanMember.setConversationId(conversation.getId());
        humanMember.setMemberType(ImConstants.MEMBER_TYPE_HUMAN);
        humanMember.setMemberId(memberId);
        humanMember.setTenantId(tenantId);
        humanMember.setRole(ImConstants.ROLE_OWNER);
        humanMember.setLastReadSeq(0L);
        humanMember.setLastPullSeq(0L);
        humanMember.setMuted(false);
        humanMember.setPinned(false);
        humanMember.setHidden(false);
        humanMember.setJoinedAt(Instant.now());
        memberMapper.insert(humanMember);

        if (agent != null) {
            ImConversationMember agentMember = new ImConversationMember();
            agentMember.setConversationId(conversation.getId());
            agentMember.setMemberType(ImConstants.MEMBER_TYPE_AGENT);
            agentMember.setMemberId(agent.getId());
            agentMember.setTenantId(tenantId);
            agentMember.setRole(ImConstants.ROLE_MEMBER);
            agentMember.setLastReadSeq(0L);
            agentMember.setLastPullSeq(0L);
            agentMember.setMuted(false);
            agentMember.setPinned(false);
            agentMember.setHidden(false);
            agentMember.setJoinedAt(Instant.now());
            memberMapper.insert(agentMember);
        }

        return toConversationItem(conversation, tenantId);
    }

    public List<AuraBotConversationMessage> getMessages(Long conversationId, Long tenantId, Long memberId, int limit) {
        ensureMember(conversationId, tenantId, memberId);
        return imMessageService.getMessagesBeforeSeq(conversationId, Long.MAX_VALUE, limit, tenantId).stream()
                .sorted((a, b) -> Long.compare(
                        a.getSeq() != null ? a.getSeq() : 0L,
                        b.getSeq() != null ? b.getSeq() : 0L))
                .map(this::toMessage)
                .toList();
    }

    // Phase B.1: appendUserMessage / appendAssistantMessage removed. Server now
    // writes both inbound + outbound rows from /chat/stream via
    // AuraBotTurnPersistence — eliminating the frontend-driven persistence
    // detour design §1.4 called out. The corresponding controller endpoints
    // under /{id}/messages/user and /{id}/messages/assistant are also gone.

    private void ensureMember(Long conversationId, Long tenantId, Long memberId) {
        if (!imConversationService.isMember(conversationId, ImConstants.MEMBER_TYPE_HUMAN, memberId, tenantId)) {
            throw new IllegalArgumentException("Not a member of this conversation");
        }
    }

    private AuraBotConversationItem toConversationItem(ImConversation conversation, Long tenantId) {
        Map<String, String> metadata = readMetadata(conversation);
        List<ImMessage> lastMessages = messageMapper.findBeforeSeq(
                conversation.getId(), tenantId, Long.MAX_VALUE, 1);
        ImMessage lastMessage = lastMessages.isEmpty() ? null : lastMessages.get(0);
        Integer messageCount = Math.toIntExact(messageMapper.selectCount(
                new QueryWrapper<ImMessage>()
                        .eq("conversation_id", conversation.getId())
                        .eq("tenant_id", tenantId)
        ));

        return AuraBotConversationItem.builder()
                .conversationId(conversation.getId())
                .title(conversation.getName())
                .agentCode(metadata.getOrDefault(AGENT_CODE_KEY, "aurabot"))
                .agentName(metadata.getOrDefault(AGENT_NAME_KEY, conversation.getName()))
                .lastMessagePreview(lastMessage != null ? abbreviate(lastMessage.getContent()) : null)
                .lastMessageType(lastMessage != null ? lastMessage.getMessageType() : null)
                .messageCount(messageCount)
                .updatedAt(conversation.getLastMessageAt() != null ? conversation.getLastMessageAt() : conversation.getUpdatedAt())
                .build();
    }

    private AuraBotConversationMessage toMessage(ImMessage message) {
        Map<String, String> metadata = readMetadata(message.getCardPayload());
        String sender;
        if (ImConstants.SENDER_TYPE_HUMAN.equals(message.getSenderType())) {
            sender = "user";
        } else if (ImConstants.SENDER_TYPE_SYSTEM.equals(message.getSenderType())) {
            sender = "system";
        } else {
            sender = "assistant";
        }
        return AuraBotConversationMessage.builder()
                .id(message.getId())
                .conversationId(message.getConversationId())
                .seq(message.getSeq())
                .sender(sender)
                .type(message.getMessageType())
                .content(message.getContent())
                .traceId(metadata.get("traceId"))
                .createdAt(message.getCreatedAt())
                .build();
    }

    private boolean isAuraBotConversation(ImConversation conversation) {
        return ImConstants.TYPE_BOT.equals(conversation.getType())
                && CHAT_KIND_VALUE.equals(readMetadata(conversation).get(CHAT_KIND_KEY));
    }

    private Map<String, String> readMetadata(ImConversation conversation) {
        return readMetadata(conversation.getMetadata());
    }

    private Map<String, String> readMetadata(String rawMetadata) {
        if (rawMetadata == null || rawMetadata.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(rawMetadata, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String writeMetadata(Map<String, String> metadata) {
        try {
            return objectMapper.writeValueAsString(new LinkedHashMap<>(metadata));
        } catch (Exception e) {
            return "{}";
        }
    }

    private AgentDefinition resolveAgentDefinition(Long tenantId, String agentCode) {
        if (Objects.equals(agentCode, "aurabot")) {
            return null;
        }
        return agentDefinitionMapper.selectOne(new QueryWrapper<AgentDefinition>()
                .eq("tenant_id", tenantId)
                .eq("agent_code", agentCode)
                .eq("status", "active")
                .last("LIMIT 1"));
    }

    private String resolveAgentName(Long tenantId, String agentCode) {
        if (Objects.equals(agentCode, "aurabot")) {
            return "AuraBot";
        }
        AgentDefinition agent = resolveAgentDefinition(tenantId, agentCode);
        return agent != null && agent.getName() != null && !agent.getName().isBlank()
                ? agent.getName()
                : agentCode;
    }

    private String abbreviate(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String normalized = content.trim().replaceAll("\\s+", " ");
        return normalized.length() > 80 ? normalized.substring(0, 80) : normalized;
    }

    private void maybePromoteConversationTitle(Long conversationId, Long tenantId, String content) {
        String title = abbreviate(content);
        if (title == null) {
            return;
        }

        ImConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !Objects.equals(conversation.getTenantId(), tenantId)) {
            return;
        }

        Integer messageCount = Math.toIntExact(messageMapper.selectCount(
                new QueryWrapper<ImMessage>()
                        .eq("conversation_id", conversationId)
                        .eq("tenant_id", tenantId)
        ));
        if (messageCount != 0) {
            return;
        }

        conversation.setName(title);
        conversation.setUpdatedAt(Instant.now());
        conversationMapper.updateById(conversation);
    }
}
