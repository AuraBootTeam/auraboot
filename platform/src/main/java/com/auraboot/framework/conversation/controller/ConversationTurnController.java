package com.auraboot.framework.conversation.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.conversation.dto.ActiveTurnDTO;
import com.auraboot.framework.conversation.turn.TurnHandle;
import com.auraboot.framework.conversation.turn.TurnRegistry;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.im.service.ImConversationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

/**
 * REST controller exposing AI turn management endpoints.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>POST /api/conversations/{conversationId}/turns/{turnId}/cancel — initiator-only cancel</li>
 *   <li>GET  /api/conversations/{conversationId}/turns/active — member-only list of active turns</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationTurnController {

    private final TurnRegistry turnRegistry;
    private final ImConversationService conversationService;

    public ConversationTurnController(TurnRegistry turnRegistry,
                                      ImConversationService conversationService) {
        this.turnRegistry = turnRegistry;
        this.conversationService = conversationService;
    }

    /**
     * Cancel an active AI turn. Only the turn initiator may cancel.
     *
     * <p>If the turnId is not found in the registry (multi-node mis-route or already expired),
     * returns 200 success as an idempotent no-op — callers (iOS replay, load balancer retry)
     * can safely retry without side effects.
     */
    @PostMapping("/{conversationId}/turns/{turnId}/cancel")
    public ApiResponse<Void> cancelTurn(@PathVariable Long conversationId,
                                        @PathVariable String turnId) {
        Long currentUserId = MetaContext.getCurrentUserId();

        Optional<TurnHandle> handleOpt = turnRegistry.get(turnId);
        if (handleOpt.isEmpty()) {
            // Idempotent no-op: turn not in this node's registry (already completed, cancelled, or mis-routed)
            return ApiResponse.success(null);
        }

        TurnHandle handle = handleOpt.get();
        if (!handle.getInitiatorUserId().equals(currentUserId)) {
            throw new RootUnCheckedException(ResponseCode.FORBIDDEN,
                    "Only the turn initiator can cancel it");
        }

        turnRegistry.markCancelled(turnId);
        return ApiResponse.success(null);
    }

    /**
     * List active AI turns for a conversation. Only conversation members may call this.
     */
    @GetMapping("/{conversationId}/turns/active")
    public ApiResponse<List<ActiveTurnDTO>> getActiveTurns(@PathVariable Long conversationId) {
        Long currentUserId = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();

        if (!conversationService.isMember(conversationId, currentUserId, tenantId)) {
            throw new RootUnCheckedException(ResponseCode.FORBIDDEN,
                    "Only conversation members can view active turns");
        }

        List<ActiveTurnDTO> result = turnRegistry.getActiveByConversation(conversationId)
                .stream()
                .map(ActiveTurnDTO::from)
                .toList();
        return ApiResponse.success(result);
    }
}
