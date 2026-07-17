package com.auraboot.framework.automation.executor.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.executor.ActionExecutor;
import com.auraboot.framework.im.model.ImConversation;
import com.auraboot.framework.im.model.ImMessage;
import com.auraboot.framework.im.service.ImConversationService;
import com.auraboot.framework.im.service.ImMessageService;
import com.auraboot.framework.rbac.mapper.UserRoleMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class SendImActionExecutor implements ActionExecutor {

    private static final String USER_PREFIX = "USER:";
    private static final String ROLE_PREFIX = "ROLE:";
    private static final int CLIENT_MSG_ID_MAX_LENGTH = 64;
    private static final int CLIENT_MSG_HASH_CHARS = 12;

    private final ImConversationService conversationService;
    private final ImMessageService messageService;
    private final UserRoleMapper userRoleMapper;
    private final ObjectMapper objectMapper;

    @Override
    public Object execute(AutomationAction action, Map<String, Object> context) {
        if (!MetaContext.exists() || MetaContext.getCurrentTenantId() == null) {
            throw new IllegalStateException("Tenant context required for send_im action");
        }
        Map<String, Object> config = action.getConfig() != null ? action.getConfig() : Map.of();
        String target = firstNonBlank(
                AutomationActionValueResolver.resolveString(config.get("target"), context),
                AutomationActionValueResolver.resolveString(config.get("recipients"), context));
        if (target == null) {
            throw new IllegalArgumentException("send_im action requires target");
        }
        String content = firstNonBlank(
                AutomationActionValueResolver.resolveString(config.get("content"), context),
                AutomationActionValueResolver.resolveString(config.get("message"), context));
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("send_im action requires content");
        }
        String title = AutomationActionValueResolver.resolveString(config.get("title"), context);
        String channel = firstNonBlank(AutomationActionValueResolver.resolveString(config.get("channel"), context), "im");
        String automationPid = string(context.get("automationPid"), string(context.get("_automation_id"), "automation"));
        String modelCode = string(context.get("modelCode"), string(context.get("entityCode"), null));
        String recordPid = string(context.get("recordPid"), null);

        Long tenantId = MetaContext.getCurrentTenantId();
        List<Long> targetUserIds = resolveUserTargets(target, tenantId);
        List<Long> conversationIds = new ArrayList<>();
        List<Long> messageIds = new ArrayList<>();

        for (Long userId : targetUserIds) {
            ImConversation conversation = conversationService.findOrCreateBotConversation(userId, tenantId);
            if (conversation == null || conversation.getId() == null) {
                throw new IllegalStateException("send_im could not resolve bot conversation for user " + userId);
            }
            String cardPayload = cardPayload(Map.of(
                    "actionType", "send_im",
                    "automationPid", automationPid,
                    "title", title != null ? title : "",
                    "channel", channel,
                    "modelCode", modelCode != null ? modelCode : "",
                    "recordPid", recordPid != null ? recordPid : ""));
            ImMessage message = messageService.sendSystemMessage(
                    conversation.getId(),
                    tenantId,
                    "system",
                    content,
                    cardPayload,
                    clientMsgId(automationPid, recordPid, userId, content));
            conversationIds.add(conversation.getId());
            if (message != null && message.getId() != null) {
                messageIds.add(message.getId());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("channel", channel);
        result.put("sentCount", messageIds.size());
        result.put("targetUserIds", targetUserIds);
        result.put("conversationIds", conversationIds);
        result.put("messageIds", messageIds);
        if (modelCode != null) {
            result.put("modelCode", modelCode);
        }
        if (recordPid != null) {
            result.put("recordPid", recordPid);
        }
        return result;
    }

    @Override
    public boolean supports(String actionType) {
        return "send_im".equals(actionType);
    }

    private List<Long> resolveUserTargets(String raw, Long tenantId) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (String token : raw.split(",")) {
            String value = token.trim();
            if (value.isEmpty()) {
                continue;
            }
            if (value.startsWith(USER_PREFIX)) {
                ids.add(parseUserId(value.substring(USER_PREFIX.length()), value));
                continue;
            }
            if (value.startsWith(ROLE_PREFIX)) {
                String roleCode = value.substring(ROLE_PREFIX.length()).trim();
                if (roleCode.isEmpty()) {
                    throw new IllegalArgumentException("send_im ROLE target requires a role code");
                }
                ids.addAll(userRoleMapper.findUserIdsByRoleCode(roleCode, tenantId));
                continue;
            }
            ids.add(parseUserId(value, value));
        }
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("send_im target resolved no users: " + raw);
        }
        return List.copyOf(ids);
    }

    private static Long parseUserId(String raw, String original) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("send_im target must be USER:<id>, ROLE:<code>, or numeric user id: "
                    + original, e);
        }
    }

    private String cardPayload(Map<String, Object> card) {
        try {
            return objectMapper.writeValueAsString(card);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("send_im card payload serialization failed: " + e.getMessage(), e);
        }
    }

    private static String clientMsgId(String automationPid, String recordPid, Long userId, String content) {
        return fitClientMsgId("automation_im_" + safe(automationPid) + "_" + safe(recordPid) + "_" + userId + "_"
                + Integer.toHexString(content.hashCode()));
    }

    private static String fitClientMsgId(String value) {
        if (value.length() <= CLIENT_MSG_ID_MAX_LENGTH) {
            return value;
        }
        String suffix = "_" + sha256Prefix(value, CLIENT_MSG_HASH_CHARS);
        int prefixLength = CLIENT_MSG_ID_MAX_LENGTH - suffix.length();
        return value.substring(0, Math.max(0, prefixLength)) + suffix;
    }

    private static String sha256Prefix(String value, int chars) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(chars);
            for (byte b : digest) {
                out.append(String.format("%02x", b));
                if (out.length() >= chars) {
                    return out.substring(0, chars);
                }
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String string(Object value, String fallback) {
        return value != null ? String.valueOf(value) : fallback;
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "none" : value.replaceAll("[^A-Za-z0-9_.:-]", "_");
    }
}
