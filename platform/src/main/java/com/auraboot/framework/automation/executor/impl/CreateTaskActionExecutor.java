package com.auraboot.framework.automation.executor.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.executor.ActionExecutor;
import com.auraboot.framework.inbox.model.InboxItem;
import com.auraboot.framework.inbox.service.InboxService;
import com.auraboot.framework.rbac.mapper.UserRoleMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class CreateTaskActionExecutor implements ActionExecutor {

    private static final String USER_PREFIX = "USER:";
    private static final String ROLE_PREFIX = "ROLE:";

    private final InboxService inboxService;
    private final UserRoleMapper userRoleMapper;
    private final ObjectMapper objectMapper;

    @Override
    public Object execute(AutomationAction action, Map<String, Object> context) {
        if (!MetaContext.exists() || MetaContext.getCurrentTenantId() == null) {
            throw new IllegalStateException("Tenant context required for create_task action");
        }
        Map<String, Object> config = action.getConfig() != null ? action.getConfig() : Map.of();
        String assignee = firstNonBlank(
                AutomationActionValueResolver.resolveString(config.get("assignee"), context),
                AutomationActionValueResolver.resolveString(config.get("target"), context));
        if (assignee == null) {
            throw new IllegalArgumentException("create_task action requires assignee");
        }
        String title = AutomationActionValueResolver.resolveString(config.get("title"), context);
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("create_task action requires title");
        }
        String message = firstNonBlank(
                AutomationActionValueResolver.resolveString(config.get("message"), context),
                AutomationActionValueResolver.resolveString(config.get("content"), context),
                AutomationActionValueResolver.resolveString(config.get("subtitle"), context));
        String priority = firstNonBlank(String.valueOf(config.getOrDefault("priority", "")), "normal");
        String dueDate = AutomationActionValueResolver.resolveString(config.get("dueDate"), context);
        String automationPid = string(context.get("automationPid"), string(context.get("_automation_id"), "automation"));
        String modelCode = string(context.get("modelCode"), string(context.get("entityCode"), null));
        String recordPid = string(context.get("recordPid"), null);

        Long tenantId = MetaContext.getCurrentTenantId();
        List<Long> assigneeUserIds = resolveUserTargets(assignee, tenantId);
        List<Long> inboxItemIds = new ArrayList<>();

        for (Long userId : assigneeUserIds) {
            InboxItem item = new InboxItem();
            item.setTenantId(tenantId);
            item.setUserId(userId);
            item.setItemType("task");
            item.setTitle(title);
            item.setSubtitle(message);
            item.setPriority(priority);
            item.setSourceType("automation");
            item.setSourceId(automationPid);
            item.setModelCode(modelCode);
            item.setRecordPid(recordPid);
            item.setDeepLink(deepLink(modelCode, recordPid));
            item.setCardPayload(cardPayload(Map.of(
                    "actionType", "create_task",
                    "automationPid", automationPid,
                    "title", title,
                    "message", message != null ? message : "",
                    "modelCode", modelCode != null ? modelCode : "",
                    "recordPid", recordPid != null ? recordPid : "",
                    "dueDate", dueDate != null ? dueDate : "")));
            item.setClientItemId(clientItemId(automationPid, recordPid, userId, title));
            item.setExpiresAt(parseInstant(dueDate));

            InboxItem created = inboxService.createItem(item);
            if (created != null && created.getId() != null) {
                inboxItemIds.add(created.getId());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("delivery", "inbox");
        result.put("itemType", "task");
        result.put("createdCount", inboxItemIds.size());
        result.put("assigneeUserIds", assigneeUserIds);
        result.put("inboxItemIds", inboxItemIds);
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
        return "create_task".equals(actionType);
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
                    throw new IllegalArgumentException("create_task ROLE target requires a role code");
                }
                ids.addAll(userRoleMapper.findUserIdsByRoleCode(roleCode, tenantId));
                continue;
            }
            ids.add(parseUserId(value, value));
        }
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("create_task assignee resolved no users: " + raw);
        }
        return List.copyOf(ids);
    }

    private static Long parseUserId(String raw, String original) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("create_task target must be USER:<id>, ROLE:<code>, or numeric user id: "
                    + original, e);
        }
    }

    private String cardPayload(Map<String, Object> card) {
        try {
            return objectMapper.writeValueAsString(card);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("create_task card payload serialization failed: " + e.getMessage(), e);
        }
    }

    private static String clientItemId(String automationPid, String recordPid, Long userId, String title) {
        return "automation_task_" + safe(automationPid) + "_" + safe(recordPid) + "_" + userId + "_"
                + Integer.toHexString(title.hashCode());
    }

    private static String deepLink(String modelCode, String recordPid) {
        if (modelCode == null || modelCode.isBlank() || recordPid == null || recordPid.isBlank()) {
            return null;
        }
        return "/p/" + modelCode + "/view/" + recordPid;
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            return null;
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
