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

    // Phase B.1 deletion: POST /{id}/messages/user and POST /{id}/messages/assistant
    // were the frontend-driven persistence detour the design called out as the
    // anti-pattern fix point (design §1.4). With AuraBotTurnPersistence in place
    // the server now writes both inbound + outbound rows from /chat/stream itself,
    // so these endpoints have no reason to exist. Per dev-stage rule
    // (feedback_dev_stage_breaking_ok) we delete them outright instead of keeping
    // a deprecated stub.

    private Long currentHumanMemberId() {
        Long memberId = MetaContext.getCurrentMemberId();
        return memberId != null ? memberId : MetaContext.getCurrentUserId();
    }
}
