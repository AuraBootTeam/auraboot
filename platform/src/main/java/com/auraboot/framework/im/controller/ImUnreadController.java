package com.auraboot.framework.im.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.im.dto.UnreadSummary;
import com.auraboot.framework.im.service.ImConversationService;
import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Surfaced by the {@code check-controller-authz} gate only after its sensitive-read
 * pattern stopped requiring a trailing slash: this controller's class-level mapping is
 * {@code "/api/im"}, so the old {@code /api/im/} pattern never matched it and an
 * {@code /api/im/**} read shipped without an authorization decision on record.
 */
@RestController
@RequestMapping("/api/im")
@AuthenticatedAccess("unread summary is self-scoped: userId and tenantId both come from "
        + "MetaContext, so a caller can only ever read their own counts")
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
