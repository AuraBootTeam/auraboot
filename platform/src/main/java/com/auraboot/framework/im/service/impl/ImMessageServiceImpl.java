package com.auraboot.framework.im.service.impl;

import com.auraboot.framework.im.dto.MessageSearchResult;
import com.auraboot.framework.im.dto.ReadReceiptInfo;
import com.auraboot.framework.im.dto.ReadReceiptSummary;
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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class ImMessageServiceImpl implements ImMessageService {

    private static final Logger log = LoggerFactory.getLogger(ImMessageServiceImpl.class);

    private final ImConversationMapper conversationMapper;
    private final ImConversationMemberMapper memberMapper;
    private final ImMessageMapper messageMapper;
    private final ImConversationService conversationService;
    private final ObjectMapper objectMapper;

    public ImMessageServiceImpl(ImConversationMapper conversationMapper,
                                 ImConversationMemberMapper memberMapper,
                                 ImMessageMapper messageMapper,
                                 @org.springframework.context.annotation.Lazy ImConversationService conversationService,
                                 ObjectMapper objectMapper) {
        this.conversationMapper = conversationMapper;
        this.memberMapper = memberMapper;
        this.messageMapper = messageMapper;
        this.conversationService = conversationService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public ImMessage sendMessage(SendMessageRequest request, Long senderId, Long tenantId) {
        Long conversationId = request.getConversationId();

        // Check membership
        if (!conversationService.isMember(conversationId, senderId, tenantId)) {
            throw new IllegalArgumentException("Not a member of this conversation");
        }

        // Dedup: if clientMsgId already exists, return existing message
        if (request.getClientMsgId() != null) {
            ImMessage existing = messageMapper.findByClientMsgId(conversationId, tenantId, request.getClientMsgId());
            if (existing != null) {
                return existing;
            }
        }

        // Increment seq atomically
        conversationMapper.incrementSeq(conversationId, tenantId);
        ImConversation conv = conversationMapper.selectById(conversationId);
        long newSeq = conv.getMaxSeq();

        // Build and insert message
        ImMessage message = new ImMessage();
        message.setConversationId(conversationId);
        message.setTenantId(tenantId);
        message.setSenderId(senderId);
        message.setSenderType(ImConstants.SENDER_TYPE_HUMAN);
        message.setSeq(newSeq);
        message.setMessageType(request.getMessageType() != null ? request.getMessageType() : "text");
        message.setContent(request.getContent());
        message.setClientMsgId(request.getClientMsgId());
        message.setReplyToId(request.getReplyToId());
        message.setRecalled(false);
        message.setCreatedAt(Instant.now());

        // Serialize JSONB fields
        if (request.getCardPayload() != null) {
            message.setCardPayload(toJson(request.getCardPayload()));
        }
        if (request.getAttachments() != null) {
            message.setAttachments(toJson(request.getAttachments()));
        }
        if (request.getMentions() != null && !request.getMentions().isEmpty()) {
            message.setMentions(toJson(request.getMentions()));
        }

        messageMapper.insert(message);

        // Unhide conversation for all members (in case any hid it)
        memberMapper.unhideForAllMembers(conversationId, tenantId);

        // Auto-read for sender (human)
        memberMapper.updateLastReadSeq(conversationId, ImConstants.MEMBER_TYPE_HUMAN, senderId, tenantId, newSeq);

        return message;
    }

    @Override
    public List<ImMessage> getMessagesAfterSeq(Long conversationId, Long afterSeq, int limit, Long tenantId) {
        return messageMapper.findAfterSeq(conversationId, tenantId, afterSeq, Math.min(limit, 200));
    }

    @Override
    public List<ImMessage> getMessagesBeforeSeq(Long conversationId, Long beforeSeq, int limit, Long tenantId) {
        return messageMapper.findBeforeSeq(conversationId, tenantId, beforeSeq, Math.min(limit, 200));
    }

    @Override
    public void markRead(Long conversationId, Long userId, Long seq, Long tenantId) {
        memberMapper.updateLastReadSeq(conversationId, ImConstants.MEMBER_TYPE_HUMAN, userId, tenantId, seq);
    }

    @Override
    @Transactional
    public ImMessage recallMessage(Long messageId, Long senderId, Long tenantId) {
        // Check time limit before recalling
        ImMessage message = messageMapper.selectById(messageId);
        if (message == null) {
            throw new IllegalArgumentException("Message not found");
        }
        if (message.getCreatedAt() != null) {
            long secondsAgo = java.time.Duration.between(message.getCreatedAt(), Instant.now()).getSeconds();
            if (secondsAgo > 120) {
                throw new IllegalArgumentException("Message can only be recalled within 2 minutes");
            }
        }

        int updated = messageMapper.recallMessage(messageId, tenantId, senderId);
        if (updated == 0) {
            throw new IllegalArgumentException("Message not found, already recalled, or not authorized");
        }
        return messageMapper.selectById(messageId);
    }

    @Override
    @Transactional
    public ImMessage sendSystemMessage(Long conversationId, Long tenantId,
                                        String messageType, String content,
                                        String cardPayload, String clientMsgId) {
        // Dedup by clientMsgId
        if (clientMsgId != null) {
            ImMessage existing = messageMapper.findByClientMsgId(conversationId, tenantId, clientMsgId);
            if (existing != null) {
                return existing;
            }
        }

        // Increment seq atomically
        conversationMapper.incrementSeq(conversationId, tenantId);
        ImConversation conv = conversationMapper.selectById(conversationId);
        long newSeq = conv.getMaxSeq();

        ImMessage message = new ImMessage();
        message.setConversationId(conversationId);
        message.setTenantId(tenantId);
        message.setSenderId(0L); // system sender
        message.setSenderType(ImConstants.SENDER_TYPE_SYSTEM);
        message.setSeq(newSeq);
        message.setMessageType(messageType != null ? messageType : "system");
        message.setContent(content);
        message.setCardPayload(cardPayload);
        message.setClientMsgId(clientMsgId);
        message.setRecalled(false);
        message.setCreatedAt(Instant.now());

        messageMapper.insert(message);
        return message;
    }

    @Override
    @Transactional
    public ImMessage sendAgentMessage(Long conversationId, Long tenantId, Long agentId,
                                        String messageType, String content,
                                        String cardPayload, String clientMsgId) {
        // Dedup by clientMsgId — same idempotency contract as sendSystemMessage.
        if (clientMsgId != null) {
            ImMessage existing = messageMapper.findByClientMsgId(conversationId, tenantId, clientMsgId);
            if (existing != null) {
                return existing;
            }
        }

        // Increment seq atomically
        conversationMapper.incrementSeq(conversationId, tenantId);
        ImConversation conv = conversationMapper.selectById(conversationId);
        long newSeq = conv.getMaxSeq();

        ImMessage message = new ImMessage();
        message.setConversationId(conversationId);
        message.setTenantId(tenantId);
        message.setSenderId(agentId != null ? agentId : 0L);
        message.setSenderType(ImConstants.SENDER_TYPE_AGENT);
        message.setSeq(newSeq);
        message.setMessageType(messageType != null ? messageType : "ai_response");
        message.setContent(content);
        message.setCardPayload(cardPayload);
        message.setClientMsgId(clientMsgId);
        message.setRecalled(false);
        message.setCreatedAt(Instant.now());

        messageMapper.insert(message);
        return message;
    }

    @Override
    public List<MessageSearchResult> searchMessages(String keyword, Long conversationId, Long userId, Long tenantId, int limit) {
        List<Long> targetConvIds;
        if (conversationId != null) {
            // Check membership directly
            ImConversationMember member = memberMapper.findMember(
                    conversationId, ImConstants.MEMBER_TYPE_HUMAN, userId, tenantId);
            if (member == null) return List.of();
            targetConvIds = List.of(conversationId);
        } else {
            targetConvIds = memberMapper.findConversationIdsByMember(
                    tenantId, ImConstants.MEMBER_TYPE_HUMAN, userId);
        }
        if (targetConvIds.isEmpty()) return List.of();

        return messageMapper.searchMessages(tenantId, targetConvIds, "%" + keyword + "%", Math.min(limit, 50));
    }

    @Override
    @Transactional
    public ImMessage forwardMessage(Long messageId, Long targetConversationId, Long userId, Long tenantId) {
        ImMessage original = messageMapper.selectById(messageId);
        if (original == null || !original.getTenantId().equals(tenantId)) {
            throw new IllegalArgumentException("Message not found");
        }

        // Increment seq atomically
        conversationMapper.incrementSeq(targetConversationId, tenantId);
        ImConversation conv = conversationMapper.selectById(targetConversationId);
        long newSeq = conv.getMaxSeq();

        ImMessage forwarded = new ImMessage();
        forwarded.setConversationId(targetConversationId);
        forwarded.setTenantId(tenantId);
        forwarded.setSenderId(userId);
        forwarded.setSenderType(ImConstants.SENDER_TYPE_HUMAN);
        forwarded.setMessageType(original.getMessageType());
        forwarded.setContent(original.getContent());
        forwarded.setCardPayload(original.getCardPayload());
        forwarded.setAttachments(original.getAttachments());
        forwarded.setForwardedFromId(messageId);
        forwarded.setSeq(newSeq);
        forwarded.setRecalled(false);
        forwarded.setCreatedAt(Instant.now());
        messageMapper.insert(forwarded);

        // Unhide conversation for all members
        memberMapper.unhideForAllMembers(targetConversationId, tenantId);

        // Auto-read for sender (human)
        memberMapper.updateLastReadSeq(targetConversationId, ImConstants.MEMBER_TYPE_HUMAN, userId, tenantId, newSeq);

        return forwarded;
    }

    @Override
    public ReadReceiptSummary getReadReceipts(Long messageId, Long tenantId) {
        ImMessage message = messageMapper.selectById(messageId);
        if (message == null || !message.getTenantId().equals(tenantId)) {
            throw new IllegalArgumentException("Message not found");
        }

        Long conversationId = message.getConversationId();
        Long senderId = message.getSenderId();
        String senderType = message.getSenderType() != null ? message.getSenderType() : ImConstants.SENDER_TYPE_HUMAN;
        Long seq = message.getSeq();

        int readCount = memberMapper.countReadersForSeq(conversationId, tenantId, seq, senderType, senderId);
        int totalMembers = memberMapper.countMembersExcluding(conversationId, tenantId, senderType, senderId);
        List<ReadReceiptInfo> readers = memberMapper.findReadersForSeq(conversationId, tenantId, seq, senderType, senderId);

        return ReadReceiptSummary.builder()
                .messageId(messageId)
                .conversationId(conversationId)
                .readCount(readCount)
                .totalMembers(totalMembers)
                .readers(readers)
                .build();
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize to JSON", e);
            return null;
        }
    }
}
