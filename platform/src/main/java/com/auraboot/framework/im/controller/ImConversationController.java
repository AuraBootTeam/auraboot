package com.auraboot.framework.im.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.im.dto.ConversationAgentSettingsRequest;
import com.auraboot.framework.im.dto.ConversationCreateRequest;
import com.auraboot.framework.im.dto.ConversationListItem;
import com.auraboot.framework.im.dto.ConversationMemberInfo;
import com.auraboot.framework.im.dto.ConversationUpdateRequest;
import com.auraboot.framework.im.model.ImConstants;
import com.auraboot.framework.im.model.ImConversation;
import com.auraboot.framework.im.service.ImConversationService;
import com.auraboot.framework.im.websocket.ImWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/im/conversations")
public class ImConversationController {

    private final ImConversationService conversationService;
    private final ImWebSocketHandler webSocketHandler;

    public ImConversationController(ImConversationService conversationService,
                                     ImWebSocketHandler webSocketHandler) {
        this.conversationService = conversationService;
        this.webSocketHandler = webSocketHandler;
    }

    @GetMapping
    public ApiResponse<List<ConversationListItem>> listConversations(
            @RequestParam(required = false) String type) {
        Long userId = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(conversationService.listByUser(userId, tenantId, type));
    }

    @GetMapping("/by-record")
    public ApiResponse<ConversationListItem> findByRecord(
            @RequestParam String modelCode,
            @RequestParam Long recordId) {
        Long userId = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();
        ImConversation conv = conversationService.findByBoundRecord(modelCode, recordId, tenantId);
        if (conv == null) {
            return ApiResponse.success(null);
        }
        return ApiResponse.success(
                conversationService.getByIdAsListItem(conv.getId(), userId, tenantId));
    }

    @PostMapping
    public ApiResponse<ConversationListItem> createConversation(@Valid @RequestBody ConversationCreateRequest request) {
        Long userId = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(conversationService.createAndBuildListItem(request, userId, tenantId));
    }

    @GetMapping("/{id}")
    public ApiResponse<ConversationListItem> getConversation(@PathVariable Long id) {
        Long userId = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();
        ConversationListItem item = conversationService.getByIdAsListItem(id, userId, tenantId);
        if (item == null) {
            return ApiResponse.error("Conversation not found");
        }
        return ApiResponse.success(item);
    }

    @GetMapping("/{id}/members")
    public ApiResponse<List<ConversationMemberInfo>> getMembers(@PathVariable Long id) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(conversationService.getMembers(id, tenantId));
    }

    @PostMapping("/{id}/members")
    public ApiResponse<Void> addMembers(@PathVariable Long id, @RequestBody JsonNode body) {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<Long> memberIds = readIdList(body, "memberIds", body.isArray());
        List<Long> agentIds = readIdList(body, "agentIds", false);
        conversationService.addMembers(id, memberIds, tenantId);
        conversationService.addAgentMembers(id, agentIds, tenantId);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/{id}/members/{memberType}/{memberId}")
    public ApiResponse<Void> removeMember(@PathVariable Long id,
                                            @PathVariable String memberType,
                                            @PathVariable Long memberId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        conversationService.removeMember(id, memberType, memberId, tenantId);
        return ApiResponse.success(null);
    }

    @PutMapping("/{id}/settings")
    public ApiResponse<Void> updateSettings(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long userId = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();
        Boolean muted = body.containsKey("muted") ? (Boolean) body.get("muted") : null;
        Boolean pinned = body.containsKey("pinned") ? (Boolean) body.get("pinned") : null;
        conversationService.updateMemberSettings(id, userId, tenantId, muted, pinned);
        return ApiResponse.success(null);
    }

    @PutMapping("/{id}/agent-settings")
    public ApiResponse<Void> updateAgentSettings(@PathVariable Long id,
                                                 @RequestBody ConversationAgentSettingsRequest request) {
        Long userId = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();
        conversationService.updateAgentSettings(id, request, userId, tenantId);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/{id}/self")
    public ApiResponse<Void> hideConversation(@PathVariable Long id) {
        Long userId = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();
        conversationService.hideConversation(id, userId, tenantId);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> dissolveGroup(@PathVariable Long id) {
        Long userId = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();
        List<Long> memberIds = conversationService.dissolveGroup(id, userId, tenantId);
        // Broadcast to other members
        List<Long> others = memberIds.stream().filter(uid -> !uid.equals(userId)).toList();
        if (!others.isEmpty()) {
            webSocketHandler.broadcastEvent(others, ImConstants.WS_CONVERSATION_DELETED,
                    Map.of("conversationId", id));
        }
        return ApiResponse.success(null);
    }

    @PostMapping("/{id}/leave")
    public ApiResponse<Void> leaveGroup(@PathVariable Long id) {
        Long userId = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();
        conversationService.leaveGroup(id, userId, tenantId);
        // Broadcast member_left to remaining human members (if group still exists)
        List<ConversationMemberInfo> remaining = conversationService.getMembers(id, tenantId);
        if (!remaining.isEmpty()) {
            List<Long> remainingHumanIds = remaining.stream()
                    .filter(m -> ImConstants.MEMBER_TYPE_HUMAN.equals(m.getMemberType()))
                    .map(ConversationMemberInfo::getMemberId).toList();
            webSocketHandler.broadcastEvent(remainingHumanIds, ImConstants.WS_MEMBER_LEFT,
                    Map.of("conversationId", id, "userId", userId));
        }
        return ApiResponse.success(null);
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> updateConversation(@PathVariable Long id,
                                                  @RequestBody ConversationUpdateRequest request) {
        Long userId = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();
        conversationService.updateConversation(id, request, userId, tenantId);
        List<Long> humanMemberIds = conversationService.getMembers(id, tenantId).stream()
                .filter(m -> ImConstants.MEMBER_TYPE_HUMAN.equals(m.getMemberType()))
                .map(ConversationMemberInfo::getMemberId).toList();
        if (request.getName() != null && !request.getName().isBlank()) {
            webSocketHandler.broadcastEvent(humanMemberIds, ImConstants.WS_CONVERSATION_UPDATED,
                    Map.of("conversationId", id, "name", request.getName()));
        }
        return ApiResponse.success(null);
    }

    private List<Long> readIdList(JsonNode body, String fieldName, boolean readRootArray) {
        if (body == null) {
            return List.of();
        }
        JsonNode source = readRootArray ? body : body.get(fieldName);
        List<Long> ids = new ArrayList<>();
        if (source == null || !source.isArray()) {
            return ids;
        }
        source.forEach(node -> {
            if (node.canConvertToLong()) {
                ids.add(node.asLong());
            }
        });
        return ids;
    }
}
