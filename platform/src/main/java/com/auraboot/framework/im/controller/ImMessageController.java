package com.auraboot.framework.im.controller;

import com.auraboot.framework.agentchat.event.ImMessageSentEvent;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.im.dto.ConversationMemberInfo;
import com.auraboot.framework.im.dto.ImMessageResponse;
import com.auraboot.framework.im.dto.MessageSearchResult;
import com.auraboot.framework.im.dto.ForwardMessageRequest;
import com.auraboot.framework.im.dto.ReadReceiptSummary;
import com.auraboot.framework.im.dto.SendMessageRequest;
import com.auraboot.framework.im.model.ImConversation;
import com.auraboot.framework.im.model.ImMessage;
import com.auraboot.framework.im.service.ImConversationService;
import com.auraboot.framework.im.service.ImMessageService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/im")
public class ImMessageController {

    private final ImMessageService messageService;
    private final ImConversationService conversationService;
    private final ApplicationEventPublisher eventPublisher;

    public ImMessageController(ImMessageService messageService,
                               ImConversationService conversationService,
                               ApplicationEventPublisher eventPublisher) {
        this.messageService = messageService;
        this.conversationService = conversationService;
        this.eventPublisher = eventPublisher;
    }

    @PostMapping("/conversations/{id}/messages")
    public ApiResponse<ImMessageResponse> sendMessage(
            @PathVariable Long id,
            @RequestBody SendMessageRequest request) {
        Long userId = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();

        if (!conversationService.isMember(id, userId, tenantId)) {
            return ApiResponse.error("Not a member of this conversation");
        }

        request.setConversationId(id);
        ImMessage msg = messageService.sendMessage(request, userId, tenantId);
        publishMessageSentEvent(msg, request, tenantId);
        Map<Long, ConversationMemberInfo> senderMap = buildSenderMap(id, tenantId);
        return ApiResponse.success(toResponse(msg, senderMap));
    }

    @PostMapping("/messages/{messageId}/recall")
    public ApiResponse<ImMessageResponse> recallMessage(@PathVariable Long messageId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        ImMessage recalled = messageService.recallMessage(messageId, userId, tenantId);
        Map<Long, ConversationMemberInfo> senderMap = buildSenderMap(recalled.getConversationId(), tenantId);
        return ApiResponse.success(toResponse(recalled, senderMap));
    }

    @GetMapping("/conversations/{id}/messages")
    public ApiResponse<List<ImMessageResponse>> getMessages(
            @PathVariable Long id,
            @RequestParam(required = false) Long afterSeq,
            @RequestParam(required = false) Long beforeSeq,
            @RequestParam(defaultValue = "50") int limit) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();

        if (!conversationService.isMember(id, userId, tenantId)) {
            return ApiResponse.error("Not a member of this conversation");
        }

        List<ImMessage> messages;
        if (afterSeq != null) {
            messages = messageService.getMessagesAfterSeq(id, afterSeq, limit, tenantId);
        } else if (beforeSeq != null) {
            messages = messageService.getMessagesBeforeSeq(id, beforeSeq, limit, tenantId);
        } else {
            // Default: latest messages
            messages = messageService.getMessagesBeforeSeq(id, Long.MAX_VALUE, limit, tenantId);
        }

        Map<Long, ConversationMemberInfo> senderMap = buildSenderMap(id, tenantId);
        List<ImMessageResponse> responses = messages.stream()
                .map(m -> toResponse(m, senderMap))
                .collect(Collectors.toList());
        return ApiResponse.success(responses);
    }

    @PostMapping("/messages/forward")
    public ApiResponse<ImMessageResponse> forwardMessage(@RequestBody ForwardMessageRequest request) {
        Long userId = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();

        if (!conversationService.isMember(request.getTargetConversationId(), userId, tenantId)) {
            return ApiResponse.error("Not a member of the target conversation");
        }

        ImMessage forwarded = messageService.forwardMessage(
                request.getMessageId(), request.getTargetConversationId(), userId, tenantId);

        Map<Long, ConversationMemberInfo> senderMap = buildSenderMap(request.getTargetConversationId(), tenantId);
        return ApiResponse.success(toResponse(forwarded, senderMap));
    }

    @GetMapping("/messages/{messageId}/read-receipts")
    public ApiResponse<ReadReceiptSummary> getReadReceipts(@PathVariable Long messageId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        ReadReceiptSummary summary = messageService.getReadReceipts(messageId, tenantId);
        return ApiResponse.success(summary);
    }

    @GetMapping("/messages/search")
    public ApiResponse<List<MessageSearchResult>> searchMessages(
            @RequestParam String keyword,
            @RequestParam(required = false) Long conversationId,
            @RequestParam(defaultValue = "20") int limit) {
        Long userId = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();
        List<MessageSearchResult> results = messageService.searchMessages(keyword, conversationId, userId, tenantId, limit);
        return ApiResponse.success(results);
    }

    /**
     * Build a senderId → member info map for sender enrichment.
     * Key is memberId (works for both human and agent members).
     */
    private Map<Long, ConversationMemberInfo> buildSenderMap(Long conversationId, Long tenantId) {
        return conversationService.getMembers(conversationId, tenantId).stream()
                .collect(Collectors.toMap(ConversationMemberInfo::getMemberId, m -> m, (a, b) -> a));
    }

    private void publishMessageSentEvent(ImMessage saved, SendMessageRequest request, Long tenantId) {
        ImConversation conversation = conversationService.getById(saved.getConversationId(), tenantId);
        String conversationType = conversation != null ? conversation.getType() : null;
        eventPublisher.publishEvent(new ImMessageSentEvent(
                this,
                saved.getConversationId(),
                tenantId,
                saved.getSenderId(),
                saved.getSenderType(),
                saved.getContent(),
                request.getMentions(),
                saved.getId(),
                conversationType,
                saved.getSeq()));
    }

    /** Convert ImMessage entity to enriched response DTO. */
    private ImMessageResponse toResponse(ImMessage msg, Map<Long, ConversationMemberInfo> senderMap) {
        ConversationMemberInfo sender = senderMap.get(msg.getSenderId());
        String senderName = sender != null
                ? sender.getDisplayName()
                : (msg.getSenderId() != null && msg.getSenderId() == 0L ? "System" : "User " + msg.getSenderId());
        String senderAvatar = sender != null ? sender.getAvatarUrl() : null;
        String senderType = msg.getSenderType() != null ? msg.getSenderType() : "human";

        // Agent-specific fields
        String agentCode = sender != null ? sender.getAgentCode() : null;
        String agentName = sender != null && "agent".equals(sender.getMemberType()) ? sender.getDisplayName() : null;
        String employeeTitle = sender != null ? sender.getEmployeeTitle() : null;

        return ImMessageResponse.builder()
                .id(msg.getId())
                .pid(msg.getId() != null ? msg.getId().toString() : null)
                .conversationId(msg.getConversationId())
                .senderId(msg.getSenderId())
                .senderPid(msg.getSenderId() != null ? msg.getSenderId().toString() : null)
                .senderType(senderType)
                .senderName(senderName)
                .senderAvatar(senderAvatar)
                .agentCode(agentCode)
                .agentName(agentName)
                .employeeTitle(employeeTitle)
                .type(msg.getMessageType() != null ? msg.getMessageType().toLowerCase() : "text")
                .content(msg.getContent())
                .seq(msg.getSeq())
                .createdAt(msg.getCreatedAt())
                .replyToId(msg.getReplyToId())
                .recalled(msg.getRecalled())
                .forwardedFromId(msg.getForwardedFromId())
                .build();
    }
}
