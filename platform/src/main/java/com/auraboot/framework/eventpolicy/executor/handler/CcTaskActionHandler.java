package com.auraboot.framework.eventpolicy.executor.handler;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.service.CcService;
import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.eventpolicy.executor.ActionExecutionException;
import com.auraboot.framework.eventpolicy.executor.ActionHandler;
import com.auraboot.framework.eventpolicy.executor.ActionProviderDependency;
import com.auraboot.framework.eventpolicy.model.ResolvedActionPlan;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class CcTaskActionHandler implements ActionHandler {

    private static final String USER_PREFIX = "USER:";
    private static final String ROLE_PREFIX = "ROLE:";
    private static final Pattern TEMPLATE = Pattern.compile("\\$\\{([^}]+)}");
    private static final int CLIENT_ITEM_ID_MAX_LENGTH = 128;

    private final InboxService inboxService;
    private final UserRoleMapper userRoleMapper;
    private final CcService ccService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(String actionType) {
        return "CC_TASK".equals(actionType);
    }

    @Override
    public List<ActionProviderDependency> runtimeProviderDependencies() {
        return List.of(
                ActionProviderDependencies.inboxMention(),
                ActionProviderDependencies.bpmTaskCc());
    }

    @Override
    public void execute(ResolvedActionPlan plan, DecisionContext context) {
        executeWithResult(plan, context);
    }

    @Override
    public Map<String, Object> executeWithResult(ResolvedActionPlan plan, DecisionContext context) {
        if (!MetaContext.exists() || MetaContext.getCurrentTenantId() == null) {
            throw ActionFailurePayload.builder(plan, "tenant_context_missing")
                    .with("delivery", "inbox")
                    .with("itemType", "mention")
                    .with("requiredContext", List.of("tenantId"))
                    .exception("Tenant context required for CC_TASK action", null);
        }
        Map<String, Object> payload = plan.payload() != null ? plan.payload() : Map.of();
        String modelCode = resolveRecordString(context, "entityCode");
        String recordPid = resolveRecordString(context, "recordPid");
        String target = firstNonBlank(
                render(plan.target(), context),
                render(payload.get("target"), context));
        if (target == null) {
            throw ActionFailurePayload.builder(plan, "action_target_missing")
                    .with("delivery", "inbox")
                    .with("itemType", "mention")
                    .with("field", "target")
                    .with("requiredContext", List.of("action.target", "payload.target"))
                    .with("modelCode", modelCode)
                    .with("recordPid", recordPid)
                    .exception("CC_TASK requires target", null);
        }
        String taskId = render(payload.get("taskId"), context);
        String title = firstNonBlank(
                render(payload.get("taskTitle"), context),
                render(payload.get("title"), context),
                "任务抄送");
        String message = firstNonBlank(
                render(payload.get("message"), context),
                render(payload.get("content"), context),
                render(payload.get("reason"), context));
        if (message == null) {
            message = "";
        }

        Long tenantId = MetaContext.getCurrentTenantId();
        List<Long> targetUserIds = resolveUserTargets(plan, target, tenantId, modelCode, recordPid);
        if (taskId != null && !taskId.isBlank()) {
            try {
                ccService.cc(taskId, targetUserIds, message);
            } catch (RuntimeException e) {
                throw ccFailure(plan, "cc_task_write_failed", target, targetUserIds, null,
                        modelCode, recordPid, taskId, "CC_TASK failed: " + ActionFailurePayload.messageOf(e), e);
            }
            return bpmResult(plan, taskId, targetUserIds, modelCode, recordPid);
        }
        return inboxResult(plan, target, targetUserIds, tenantId, title, message, modelCode, recordPid);
    }

    private Map<String, Object> bpmResult(ResolvedActionPlan plan, String taskId, List<Long> targetUserIds,
                                          String modelCode, String recordPid) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("delivery", "bpm_cc");
        result.put("taskId", taskId);
        result.put("ccCount", targetUserIds.size());
        result.put("targetUserIds", targetUserIds);
        result.put("ruleCode", plan.ruleCode());
        if (modelCode != null) {
            result.put("modelCode", modelCode);
        }
        if (recordPid != null) {
            result.put("recordPid", recordPid);
        }
        return result;
    }

    private Map<String, Object> inboxResult(ResolvedActionPlan plan, String target, List<Long> targetUserIds,
                                            Long tenantId, String title, String message,
                                            String modelCode, String recordPid) {
        List<Long> inboxItemIds = new ArrayList<>();
        for (Long userId : targetUserIds) {
            InboxItem item = new InboxItem();
            item.setTenantId(tenantId);
            item.setUserId(userId);
            item.setItemType("mention");
            item.setTitle(title);
            item.setSubtitle(message);
            item.setPriority("normal");
            item.setSourceType("event_policy");
            item.setSourceId(plan.ruleCode());
            item.setModelCode(modelCode);
            item.setRecordPid(recordPid);
            item.setDeepLink(deepLink(modelCode, recordPid));
            item.setCardPayload(cardPayload(Map.of(
                    "actionType", "CC_TASK",
                    "ruleCode", plan.ruleCode(),
                    "title", title,
                    "message", message,
                    "modelCode", modelCode != null ? modelCode : "",
                    "recordPid", recordPid != null ? recordPid : ""),
                    plan, target, targetUserIds, userId, modelCode, recordPid));
            item.setClientItemId(clientItemId(plan, recordPid, userId, title));

            InboxItem created;
            try {
                created = inboxService.createItem(item);
            } catch (RuntimeException e) {
                throw ccFailure(plan, "cc_task_write_failed", target, targetUserIds, userId,
                        modelCode, recordPid, null, "CC_TASK failed: " + ActionFailurePayload.messageOf(e), e);
            }
            if (created != null && created.getId() != null) {
                inboxItemIds.add(created.getId());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("delivery", "inbox");
        result.put("itemType", "mention");
        result.put("ccCount", inboxItemIds.size());
        result.put("targetUserIds", targetUserIds);
        result.put("inboxItemIds", inboxItemIds);
        result.put("ruleCode", plan.ruleCode());
        if (modelCode != null) {
            result.put("modelCode", modelCode);
        }
        if (recordPid != null) {
            result.put("recordPid", recordPid);
        }
        return result;
    }

    private List<Long> resolveUserTargets(
            ResolvedActionPlan plan,
            String raw,
            Long tenantId,
            String modelCode,
            String recordPid) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (String token : raw.split(",")) {
            String value = token.trim();
            if (value.isEmpty()) {
                continue;
            }
            if (value.startsWith(USER_PREFIX)) {
                ids.add(parseUserId(plan, value.substring(USER_PREFIX.length()), value, raw, modelCode, recordPid));
                continue;
            }
            if (value.startsWith(ROLE_PREFIX)) {
                String roleCode = value.substring(ROLE_PREFIX.length()).trim();
                if (roleCode.isEmpty()) {
                    throw targetFailure(plan, "target_role_code_missing",
                            "CC_TASK ROLE target requires a role code", raw, "ROLE", modelCode, recordPid, null);
                }
                ids.addAll(userRoleMapper.findUserIdsByRoleCode(roleCode, tenantId));
                continue;
            }
            ids.add(parseUserId(plan, value, value, raw, modelCode, recordPid));
        }
        if (ids.isEmpty()) {
            throw targetResolutionFailure(plan, "CC_TASK target resolved no users: " + raw, raw,
                    modelCode, recordPid);
        }
        return List.copyOf(ids);
    }

    private static ActionExecutionException targetResolutionFailure(
            ResolvedActionPlan plan,
            String message,
            String raw,
            String modelCode,
            String recordPid) {
        return targetFailure(plan, "target_resolved_no_users", message, raw, targetKind(raw), modelCode, recordPid, 0);
    }

    private static ActionExecutionException targetFailure(
            ResolvedActionPlan plan,
            String failureReason,
            String message,
            String raw,
            String targetType,
            String modelCode,
            String recordPid,
            Integer resolvedCount) {
        return ActionFailurePayload.builder(plan, failureReason)
                .with("delivery", "inbox")
                .with("itemType", "mention")
                .with("targetType", targetType)
                .with("target", raw)
                .with("field", "target")
                .with("resolvedCount", resolvedCount)
                .with("modelCode", modelCode)
                .with("recordPid", recordPid)
                .exception(message, null);
    }

    private static String targetKind(String raw) {
        if (raw == null) {
            return "UNKNOWN";
        }
        String value = raw.trim();
        if (value.startsWith(ROLE_PREFIX)) {
            return "ROLE";
        }
        if (value.startsWith(USER_PREFIX) || value.matches("\\d+")) {
            return "USER";
        }
        return "UNKNOWN";
    }

    private static Long parseUserId(
            ResolvedActionPlan plan,
            String raw,
            String original,
            String target,
            String modelCode,
            String recordPid) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw targetFailure(plan, "target_invalid",
                    "CC_TASK target must be USER:<id>, ROLE:<code>, or numeric user id: " + original,
                    target, targetKind(original), modelCode, recordPid, null);
        }
    }

    private String cardPayload(
            Map<String, Object> card,
            ResolvedActionPlan plan,
            String target,
            List<Long> targetUserIds,
            Long userId,
            String modelCode,
            String recordPid) {
        try {
            return objectMapper.writeValueAsString(card);
        } catch (JsonProcessingException e) {
            throw ccFailure(plan, "action_payload_serialization_failed", target, targetUserIds, userId,
                    modelCode, recordPid, null, "CC_TASK card payload serialization failed: " + e.getMessage(), e);
        }
    }

    private static ActionExecutionException ccFailure(
            ResolvedActionPlan plan,
            String failureReason,
            String target,
            List<Long> targetUserIds,
            Long userId,
            String modelCode,
            String recordPid,
            String taskId,
            String message,
            Throwable cause) {
        return ActionFailurePayload.builder(plan, failureReason)
                .with("delivery", taskId != null ? "bpm_cc" : "inbox")
                .with("itemType", taskId != null ? null : "mention")
                .with("taskId", taskId)
                .with("targetType", targetKind(target))
                .with("target", target)
                .with("targetUserIds", targetUserIds)
                .with("targetUserId", userId)
                .with("modelCode", modelCode)
                .with("recordPid", recordPid)
                .with("errorMessage", message)
                .exception(message, cause);
    }

    private static String clientItemId(ResolvedActionPlan plan, String recordPid, Long userId, String title) {
        String base = firstNonBlank(plan.idempotencyKey(),
                "event_policy_cc_" + safe(plan.ruleCode()) + "_" + safe(recordPid) + "_"
                        + Integer.toHexString(title.hashCode()));
        return ActionClientIdSupport.fit(base + ":" + userId, CLIENT_ITEM_ID_MAX_LENGTH);
    }

    private static String render(Object value, DecisionContext context) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        Matcher matcher = TEMPLATE.matcher(text);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            Object resolved = resolveToken(matcher.group(1).trim(), context);
            matcher.appendReplacement(out, Matcher.quoteReplacement(resolved != null ? String.valueOf(resolved) : ""));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static Object resolveToken(String token, DecisionContext context) {
        int dot = token.indexOf('.');
        if (dot <= 0) {
            return null;
        }
        try {
            Scope scope = Scope.fromCode(token.substring(0, dot));
            DecisionContext.PathValue pv = context.resolve(scope, token.substring(dot + 1));
            return pv.present() ? pv.value() : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String resolveRecordString(DecisionContext context, String field) {
        DecisionContext.PathValue pv = context.resolve(Scope.RECORD, field);
        return pv.present() && pv.value() != null ? String.valueOf(pv.value()) : null;
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

    private static String safe(String value) {
        return value == null || value.isBlank() ? "none" : value.replaceAll("[^A-Za-z0-9_.:-]", "_");
    }
}
