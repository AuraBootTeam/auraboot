package com.auraboot.framework.automation.executor.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.executor.ActionExecutor;
import com.auraboot.framework.bpm.service.CcService;
import com.auraboot.framework.inbox.model.InboxItem;
import com.auraboot.framework.inbox.service.InboxService;
import com.auraboot.framework.rbac.mapper.UserRoleMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class CcTaskActionExecutor implements ActionExecutor {

    private static final String USER_PREFIX = "USER:";
    private static final String ROLE_PREFIX = "ROLE:";

    private final InboxService inboxService;
    private final UserRoleMapper userRoleMapper;
    private final CcService ccService;
    private final ObjectMapper objectMapper;

    @Override
    public Object execute(AutomationAction action, Map<String, Object> context) {
        if (!MetaContext.exists() || MetaContext.getCurrentTenantId() == null) {
            throw new IllegalStateException("Tenant context required for cc_task action");
        }
        Map<String, Object> config = action.getConfig() != null ? action.getConfig() : Map.of();
        String target = firstNonBlank(
                AutomationActionValueResolver.resolveString(config.get("target"), context),
                AutomationActionValueResolver.resolveString(config.get("recipients"), context));
        if (target == null) {
            throw new IllegalArgumentException("cc_task action requires target");
        }
        String taskId = firstNonBlank(
                AutomationActionValueResolver.resolveString(config.get("taskId"), context),
                AutomationActionValueResolver.resolveString(context.get("taskId"), context));
        String title = firstNonBlank(
                AutomationActionValueResolver.resolveString(config.get("taskTitle"), context),
                AutomationActionValueResolver.resolveString(config.get("title"), context),
                "任务抄送");
        String message = firstNonBlank(
                AutomationActionValueResolver.resolveString(config.get("message"), context),
                AutomationActionValueResolver.resolveString(config.get("content"), context),
                AutomationActionValueResolver.resolveString(config.get("reason"), context));
        if (message == null) {
            message = "";
        }
        String automationPid = string(context.get("automationPid"), string(context.get("_automation_id"), "automation"));
        String modelCode = string(context.get("modelCode"), string(context.get("entityCode"), null));
        String recordPid = string(context.get("recordPid"), null);

        Long tenantId = MetaContext.getCurrentTenantId();
        List<Long> targetUserIds = resolveUserTargets(target, tenantId);
        if (taskId != null) {
            ccService.cc(taskId, targetUserIds, message);
            return bpmResult(taskId, targetUserIds, modelCode, recordPid);
        }
        return inboxResult(targetUserIds, tenantId, title, message, automationPid, modelCode, recordPid);
    }

    @Override
    public boolean supports(String actionType) {
        return "cc_task".equals(actionType);
    }

    private Map<String, Object> bpmResult(String taskId, List<Long> targetUserIds, String modelCode, String recordPid) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("delivery", "bpm_cc");
        result.put("taskId", taskId);
        result.put("ccCount", targetUserIds.size());
        result.put("targetUserIds", targetUserIds);
        if (modelCode != null) {
            result.put("modelCode", modelCode);
        }
        if (recordPid != null) {
            result.put("recordPid", recordPid);
        }
        return result;
    }

    private Map<String, Object> inboxResult(List<Long> targetUserIds, Long tenantId, String title, String message,
                                            String automationPid, String modelCode, String recordPid) {
        List<Long> inboxItemIds = new ArrayList<>();
        for (Long userId : targetUserIds) {
            InboxItem item = new InboxItem();
            item.setTenantId(tenantId);
            item.setUserId(userId);
            item.setItemType("mention");
            item.setTitle(title);
            item.setSubtitle(message);
            item.setPriority("normal");
            item.setSourceType("automation");
            item.setSourceId(automationPid);
            item.setModelCode(modelCode);
            item.setRecordPid(recordPid);
            item.setDeepLink(deepLink(modelCode, recordPid));
            item.setCardPayload(cardPayload(Map.of(
                    "actionType", "cc_task",
                    "automationPid", automationPid,
                    "title", title,
                    "message", message,
                    "modelCode", modelCode != null ? modelCode : "",
                    "recordPid", recordPid != null ? recordPid : "")));
            item.setClientItemId(clientItemId(automationPid, recordPid, userId, title));

            InboxItem created = inboxService.createItem(item);
            if (created != null && created.getId() != null) {
                inboxItemIds.add(created.getId());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("delivery", "inbox");
        result.put("itemType", "mention");
        result.put("ccCount", inboxItemIds.size());
        result.put("targetUserIds", targetUserIds);
        result.put("inboxItemIds", inboxItemIds);
        if (modelCode != null) {
            result.put("modelCode", modelCode);
        }
        if (recordPid != null) {
            result.put("recordPid", recordPid);
        }
        return result;
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
                    throw new IllegalArgumentException("cc_task ROLE target requires a role code");
                }
                ids.addAll(userRoleMapper.findUserIdsByRoleCode(roleCode, tenantId));
                continue;
            }
            ids.add(parseUserId(value, value));
        }
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("cc_task target resolved no users: " + raw);
        }
        return List.copyOf(ids);
    }

    private static Long parseUserId(String raw, String original) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("cc_task target must be USER:<id>, ROLE:<code>, or numeric user id: "
                    + original, e);
        }
    }

    private String cardPayload(Map<String, Object> card) {
        try {
            return objectMapper.writeValueAsString(card);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("cc_task card payload serialization failed: " + e.getMessage(), e);
        }
    }

    private static String clientItemId(String automationPid, String recordPid, Long userId, String title) {
        return "automation_cc_" + safe(automationPid) + "_" + safe(recordPid) + "_" + userId + "_"
                + Integer.toHexString(title.hashCode());
    }

    private static String deepLink(String modelCode, String recordPid) {
        if (modelCode == null || modelCode.isBlank() || recordPid == null || recordPid.isBlank()) {
            return null;
        }
        return "/p/" + modelCode + "/view/" + recordPid;
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
