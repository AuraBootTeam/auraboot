package com.auraboot.framework.im.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.im.dto.UnreadSummary;
import com.auraboot.framework.im.service.ImConversationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/im")
public class ImUnreadController {

    private final ImConversationService conversationService;

    public ImUnreadController(ImConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @GetMapping("/unread-summary")
    public ApiResponse<UnreadSummary> getUnreadSummary() {
        Long userId = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(conversationService.getUnreadSummary(userId, tenantId));
    }
}
