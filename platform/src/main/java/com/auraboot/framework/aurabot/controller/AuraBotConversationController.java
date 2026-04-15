package com.auraboot.framework.aurabot.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.aurabot.dto.AuraBotConversationCreateRequest;
import com.auraboot.framework.aurabot.dto.AuraBotConversationItem;
import com.auraboot.framework.aurabot.dto.AuraBotConversationMessage;
import com.auraboot.framework.aurabot.dto.AuraBotMessageCreateRequest;
import com.auraboot.framework.aurabot.service.AuraBotConversationService;
import com.auraboot.framework.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/ai/aurabot/conversations")
@RequiredArgsConstructor
public class AuraBotConversationController {

    private final AuraBotConversationService conversationService;

    @GetMapping
    public ApiResponse<List<AuraBotConversationItem>> listConversations() {
        return ApiResponse.success(conversationService.listConversations(
                MetaContext.getCurrentTenantId(),
                currentHumanMemberId()
        ));
    }

    @PostMapping
    public ApiResponse<AuraBotConversationItem> ensureConversation(
            @RequestBody(required = false) AuraBotConversationCreateRequest request) {
        String agentCode = request != null ? request.getAgentCode() : null;
        return ApiResponse.success(conversationService.ensureConversation(
                MetaContext.getCurrentTenantId(),
                currentHumanMemberId(),
                agentCode
        ));
    }

    @GetMapping("/{conversationId}/messages")
    public ApiResponse<List<AuraBotConversationMessage>> getMessages(
            @PathVariable Long conversationId,
            @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.success(conversationService.getMessages(
                conversationId,
                MetaContext.getCurrentTenantId(),
                currentHumanMemberId(),
                limit
        ));
    }

    @PostMapping("/{conversationId}/messages/user")
    public ApiResponse<AuraBotConversationMessage> appendUserMessage(
            @PathVariable Long conversationId,
            @RequestBody AuraBotMessageCreateRequest request) {
        return ApiResponse.success(conversationService.appendUserMessage(
                conversationId,
                MetaContext.getCurrentTenantId(),
                currentHumanMemberId(),
                request.getContent(),
                request.getClientMsgId()
        ));
    }

    @PostMapping("/{conversationId}/messages/assistant")
    public ApiResponse<AuraBotConversationMessage> appendAssistantMessage(
            @PathVariable Long conversationId,
            @RequestBody AuraBotMessageCreateRequest request) {
        return ApiResponse.success(conversationService.appendAssistantMessage(
                conversationId,
                MetaContext.getCurrentTenantId(),
                currentHumanMemberId(),
                request.getContent(),
                request.getTraceId(),
                Boolean.TRUE.equals(request.getError())
        ));
    }

    private Long currentHumanMemberId() {
        Long memberId = MetaContext.getCurrentMemberId();
        return memberId != null ? memberId : MetaContext.getCurrentUserId();
    }
}
