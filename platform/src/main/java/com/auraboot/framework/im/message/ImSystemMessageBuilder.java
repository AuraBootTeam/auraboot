package com.auraboot.framework.im.message;

import com.auraboot.framework.im.model.ImConstants;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds structured JSON content for system messages stored in {@code ab_im_message.content}.
 * Schema: {@code {"subType":"<key>","params":{...}}}. Clients (iOS / web) parse params and
 * render localized text. No additional DB column required — schema-light approach.
 *
 * <p>{@link #memberJoinedBatch(List, List, Long, String)} is the multi-member variant used
 * when {@code addMembers} adds multiple users in one call; it stores arrays so the client
 * can render "Alice added 3 members" without spamming N messages.
 */
public final class ImSystemMessageBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ImSystemMessageBuilder() {}

    public static String memberJoined(Long memberId, String memberName, Long byUserId, String byUserName) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("memberId", memberId);
        params.put("memberName", memberName);
        params.put("byUserId", byUserId);
        params.put("byUserName", byUserName);
        return wrap(ImConstants.SYS_MEMBER_JOINED, params);
    }

    public static String memberJoinedBatch(List<Long> memberIds, List<String> memberNames,
                                            Long byUserId, String byUserName) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("memberIds", memberIds);
        params.put("memberNames", memberNames);
        params.put("byUserId", byUserId);
        params.put("byUserName", byUserName);
        return wrap(ImConstants.SYS_MEMBER_JOINED, params);
    }

    public static String memberLeft(Long memberId, String memberName) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("memberId", memberId);
        params.put("memberName", memberName);
        return wrap(ImConstants.SYS_MEMBER_LEFT, params);
    }

    public static String memberRemoved(Long memberId, String memberName, Long byUserId, String byUserName) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("memberId", memberId);
        params.put("memberName", memberName);
        params.put("byUserId", byUserId);
        params.put("byUserName", byUserName);
        return wrap(ImConstants.SYS_MEMBER_REMOVED, params);
    }

    public static String conversationCreated(Long byUserId, String byUserName) {
        return wrap(ImConstants.SYS_CONVERSATION_CREATED, byUserParams(byUserId, byUserName));
    }

    public static String conversationRenamed(String oldName, String newName, Long byUserId, String byUserName) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("oldName", oldName);
        params.put("newName", newName);
        params.put("byUserId", byUserId);
        params.put("byUserName", byUserName);
        return wrap(ImConstants.SYS_CONVERSATION_RENAMED, params);
    }

    public static String announcementUpdated(Long byUserId, String byUserName) {
        return wrap(ImConstants.SYS_ANNOUNCEMENT_UPDATED, byUserParams(byUserId, byUserName));
    }

    public static String announcementCleared(Long byUserId, String byUserName) {
        return wrap(ImConstants.SYS_ANNOUNCEMENT_CLEARED, byUserParams(byUserId, byUserName));
    }

    public static String agentSettingsChanged(Long byUserId, String byUserName) {
        return wrap(ImConstants.SYS_AGENT_SETTINGS_CHANGED, byUserParams(byUserId, byUserName));
    }

    public static String conversationDissolved(Long byUserId, String byUserName) {
        return wrap(ImConstants.SYS_CONVERSATION_DISSOLVED, byUserParams(byUserId, byUserName));
    }

    public static String conversationArchived(Long byUserId, String byUserName) {
        return wrap(ImConstants.SYS_CONVERSATION_ARCHIVED, byUserParams(byUserId, byUserName));
    }

    public static String conversationPinnedMsg(Long byUserId, String byUserName, Long messageId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("byUserId", byUserId);
        params.put("byUserName", byUserName);
        params.put("messageId", messageId);
        return wrap(ImConstants.SYS_CONVERSATION_PINNED_MSG, params);
    }

    private static Map<String, Object> byUserParams(Long byUserId, String byUserName) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("byUserId", byUserId);
        params.put("byUserName", byUserName);
        return params;
    }

    private static String wrap(String subType, Map<String, Object> params) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("subType", subType);
        root.put("params", params);
        try {
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize system message", e);
        }
    }
}
