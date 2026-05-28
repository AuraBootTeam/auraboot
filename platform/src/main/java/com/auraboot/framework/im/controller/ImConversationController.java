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
import com.auraboot.framework.im.message.ImSystemMessageBuilder;
import com.auraboot.framework.im.service.ImConversationService;
import com.auraboot.framework.im.service.ImMessageService;
import com.auraboot.framework.im.websocket.ImWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/im/conversations")
public class ImConversationController {

    private final ImConversationService conversationService;
    private final ImWebSocketHandler webSocketHandler;
    private final ImMessageService messageService;

    public ImConversationController(ImConversationService conversationService,
                                     ImWebSocketHandler webSocketHandler,
                                     ImMessageService messageService) {
        this.conversationService = conversationService;
        this.webSocketHandler = webSocketHandler;
        this.messageService = messageService;
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
        Long userId = MetaContext.getCurrentUserId();
        String userName = MetaContext.getCurrentUsername();
        Long tenantId = MetaContext.getCurrentTenantId();
        List<Long> memberIds = readIdList(body, "memberIds", body.isArray());
        List<Long> agentIds = readIdList(body, "agentIds", false);
        conversationService.addMembers(id, memberIds, tenantId);
        conversationService.addAgentMembers(id, agentIds, tenantId);

        // Single fetch — used for both broadcast and system message
        List<ConversationMemberInfo> currentMembers = conversationService.getMembers(id, tenantId);
        List<Long> recipientHumanIds = currentMembers.stream()
                .filter(m -> ImConstants.MEMBER_TYPE_HUMAN.equals(m.getMemberType()))
                .map(ConversationMemberInfo::getMemberId).toList();

        // Broadcast member_added to all current human members (including newly added)
        if (!recipientHumanIds.isEmpty() && (!memberIds.isEmpty() || !agentIds.isEmpty())) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("conversationId", id);
            payload.put("memberIds", memberIds);
            payload.put("agentIds", agentIds);
            payload.put("byUserId", userId);
            payload.put("byUserName", userName);
            webSocketHandler.broadcastEvent(recipientHumanIds, ImConstants.WS_MEMBER_ADDED, payload);
        }

        // Write system message for newly added human members (batch variant)
        if (!memberIds.isEmpty()) {
            List<Long> newHumanIds = memberIds;
            List<String> newNames = currentMembers.stream()
                    .filter(m -> ImConstants.MEMBER_TYPE_HUMAN.equals(m.getMemberType())
                                  && newHumanIds.contains(m.getMemberId()))
                    .map(m -> resolveName(m, m.getMemberId()))
                    .toList();
            String sysContent = ImSystemMessageBuilder.memberJoinedBatch(
                    newHumanIds, newNames, userId, userName);
            messageService.sendSystemMessage(id, tenantId, "system", sysContent, null, null);
        }

        return ApiResponse.success(null);
    }

    @DeleteMapping("/{id}/members/{memberType}/{memberId}")
    public ApiResponse<Void> removeMember(@PathVariable Long id,
                                            @PathVariable String memberType,
                                            @PathVariable Long memberId) {
        Long actorUserId = MetaContext.getCurrentUserId();
        String actorUserName = MetaContext.getCurrentUsername();
        Long tenantId = MetaContext.getCurrentTenantId();

        // Capture removed member's name BEFORE deletion (humans only — agent removals skip sys msg)
        String removedName = null;
        if (ImConstants.MEMBER_TYPE_HUMAN.equals(memberType)) {
            removedName = conversationService.getMembers(id, tenantId).stream()
                    .filter(m -> ImConstants.MEMBER_TYPE_HUMAN.equals(m.getMemberType())
                                  && memberId.equals(m.getMemberId()))
                    .findFirst()
                    .map(m -> resolveName(m, memberId))
                    .orElse("User#" + memberId);
        }

        conversationService.removeMember(id, memberType, memberId, tenantId);

        // Send self_kicked to the removed human (agents don't receive WS events)
        if (ImConstants.MEMBER_TYPE_HUMAN.equals(memberType)) {
            Map<String, Object> selfPayload = new HashMap<>();
            selfPayload.put("conversationId", id);
            selfPayload.put("byUserId", actorUserId);
            selfPayload.put("byUserName", actorUserName);
            webSocketHandler.broadcastEvent(List.of(memberId), ImConstants.WS_SELF_KICKED, selfPayload);
        }

        // Send member_removed to all remaining human members
        List<Long> remainingHumanIds = conversationService.getMembers(id, tenantId).stream()
                .filter(m -> ImConstants.MEMBER_TYPE_HUMAN.equals(m.getMemberType()))
                .map(ConversationMemberInfo::getMemberId).toList();
        if (!remainingHumanIds.isEmpty()) {
            Map<String, Object> othersPayload = new HashMap<>();
            othersPayload.put("conversationId", id);
            othersPayload.put("removedMemberId", memberId);
            othersPayload.put("removedMemberType", memberType);
            othersPayload.put("byUserId", actorUserId);
            othersPayload.put("byUserName", actorUserName);
            webSocketHandler.broadcastEvent(remainingHumanIds, ImConstants.WS_MEMBER_REMOVED, othersPayload);
        }

        // Write system message (humans only)
        if (ImConstants.MEMBER_TYPE_HUMAN.equals(memberType) && removedName != null) {
            String sysContent = ImSystemMessageBuilder.memberRemoved(
                    memberId, removedName, actorUserId, actorUserName);
            messageService.sendSystemMessage(id, tenantId, "system", sysContent, null, null);
        }

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
        String userName = MetaContext.getCurrentUsername();
        Long tenantId = MetaContext.getCurrentTenantId();
        List<Long> memberIds = conversationService.dissolveGroup(id, userId, tenantId);
        // Broadcast to other members
        List<Long> others = memberIds.stream().filter(uid -> !uid.equals(userId)).toList();
        if (!others.isEmpty()) {
            // Legacy event for backward compatibility with older iOS clients
            webSocketHandler.broadcastEvent(others, ImConstants.WS_CONVERSATION_DELETED,
                    Map.of("conversationId", id));
            // New event with actor context
            Map<String, Object> payload = new HashMap<>();
            payload.put("conversationId", id);
            payload.put("byUserId", userId);
            payload.put("byUserName", userName);
            webSocketHandler.broadcastEvent(others, ImConstants.WS_CONVERSATION_DISSOLVED, payload);
        }
        return ApiResponse.success(null);
    }

    @PostMapping("/{id}/leave")
    public ApiResponse<Void> leaveGroup(@PathVariable Long id) {
        Long userId = MetaContext.getCurrentUserId();
        String userName = MetaContext.getCurrentUsername();
        Long tenantId = MetaContext.getCurrentTenantId();
        conversationService.leaveGroup(id, userId, tenantId);
        // Broadcast member_left to remaining human members (if group still exists)
        List<ConversationMemberInfo> remaining = conversationService.getMembers(id, tenantId);
        if (!remaining.isEmpty()) {
            List<Long> remainingHumanIds = remaining.stream()
                    .filter(m -> ImConstants.MEMBER_TYPE_HUMAN.equals(m.getMemberType()))
                    .map(ConversationMemberInfo::getMemberId).toList();
            Map<String, Object> payload = new HashMap<>();
            payload.put("conversationId", id);
            payload.put("byUserId", userId);
            payload.put("byUserName", userName);
            webSocketHandler.broadcastEvent(remainingHumanIds, ImConstants.WS_MEMBER_LEFT, payload);
        }
        return ApiResponse.success(null);
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> updateConversation(@PathVariable Long id,
                                                  @RequestBody ConversationUpdateRequest request) {
        Long userId = MetaContext.getCurrentUserId();
        String userName = MetaContext.getCurrentUsername();
        Long tenantId = MetaContext.getCurrentTenantId();

        // Capture old name BEFORE the update (for rename event payload)
        String oldName = null;
        boolean isRename = request.getName() != null && !request.getName().isBlank();
        if (isRename) {
            oldName = conversationService.getById(id, tenantId).getName();
        }

        conversationService.updateConversation(id, request, userId, tenantId);

        List<Long> humanMemberIds = conversationService.getMembers(id, tenantId).stream()
                .filter(m -> ImConstants.MEMBER_TYPE_HUMAN.equals(m.getMemberType()))
                .map(ConversationMemberInfo::getMemberId).toList();

        if (isRename) {
            // Legacy event for older iOS clients
            webSocketHandler.broadcastEvent(humanMemberIds, ImConstants.WS_CONVERSATION_UPDATED,
                    Map.of("conversationId", id, "name", request.getName()));
            // New rename event with old/new name and actor context
            Map<String, Object> renamePayload = new HashMap<>();
            renamePayload.put("conversationId", id);
            renamePayload.put("oldName", oldName);
            renamePayload.put("newName", request.getName());
            renamePayload.put("byUserId", userId);
            renamePayload.put("byUserName", userName);
            webSocketHandler.broadcastEvent(humanMemberIds, ImConstants.WS_CONVERSATION_RENAMED, renamePayload);
        }
        return ApiResponse.success(null);
    }

    /**
     * Resolve a human-readable name for a member info. Falls back to displayName, then "User#<id>".
     */
    private static String resolveName(ConversationMemberInfo info, Long fallbackId) {
        if (info == null) return "User#" + fallbackId;
        if (info.getName() != null && !info.getName().isBlank()) return info.getName();
        if (info.getDisplayName() != null && !info.getDisplayName().isBlank()) return info.getDisplayName();
        return "User#" + fallbackId;
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
