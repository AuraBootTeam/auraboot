package com.auraboot.framework.eventpolicy.executor.handler;

import com.auraboot.framework.application.tenant.MetaContext;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class CreateTaskActionHandler implements ActionHandler {

    private static final String USER_PREFIX = "USER:";
    private static final String ROLE_PREFIX = "ROLE:";
    private static final Pattern TEMPLATE = Pattern.compile("\\$\\{([^}]+)}");
    private static final int CLIENT_ITEM_ID_MAX_LENGTH = 128;

    private final InboxService inboxService;
    private final UserRoleMapper userRoleMapper;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(String actionType) {
        return "CREATE_TASK".equals(actionType);
    }

    @Override
    public List<ActionProviderDependency> runtimeProviderDependencies() {
        return List.of(ActionProviderDependencies.inboxTask());
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
                    .with("itemType", "task")
                    .with("requiredContext", List.of("tenantId"))
                    .exception("Tenant context required for CREATE_TASK action", null);
        }
        Map<String, Object> payload = plan.payload() != null ? plan.payload() : Map.of();
        String modelCode = resolveRecordString(context, "entityCode");
        String recordPid = resolveRecordString(context, "recordPid");
        String assignee = firstNonBlank(
                render(payload.get("assignee"), context),
                render(plan.target(), context));
        if (assignee == null) {
            throw ActionFailurePayload.builder(plan, "action_target_missing")
                    .with("delivery", "inbox")
                    .with("itemType", "task")
                    .with("field", "payload.assignee")
                    .with("requiredContext", List.of("payload.assignee", "action.target"))
                    .with("modelCode", modelCode)
                    .with("recordPid", recordPid)
                    .exception("CREATE_TASK requires target or payload.assignee", null);
        }
        String title = render(payload.get("title"), context);
        if (title == null || title.isBlank()) {
            throw ActionFailurePayload.builder(plan, "payload_title_missing")
                    .with("delivery", "inbox")
                    .with("itemType", "task")
                    .with("field", "payload.title")
                    .with("target", assignee)
                    .with("modelCode", modelCode)
                    .with("recordPid", recordPid)
                    .exception("CREATE_TASK requires payload.title", null);
        }
        String message = firstNonBlank(
                render(payload.get("message"), context),
                render(payload.get("content"), context),
                render(payload.get("subtitle"), context));
        String priority = firstNonBlank(render(payload.get("priority"), context), "normal");
        String dueDate = render(payload.get("dueDate"), context);

        Long tenantId = MetaContext.getCurrentTenantId();
        List<Long> assigneeUserIds = resolveUserTargets(plan, assignee, tenantId, modelCode, recordPid);
        List<Long> inboxItemIds = new ArrayList<>();

        for (Long userId : assigneeUserIds) {
            InboxItem item = new InboxItem();
            item.setTenantId(tenantId);
            item.setUserId(userId);
            item.setItemType("task");
            item.setTitle(title);
            item.setSubtitle(message);
            item.setPriority(priority);
            item.setSourceType("event_policy");
            item.setSourceId(plan.ruleCode());
            item.setModelCode(modelCode);
            item.setRecordPid(recordPid);
            item.setDeepLink(deepLink(modelCode, recordPid));
            item.setCardPayload(cardPayload(Map.of(
                    "actionType", "CREATE_TASK",
                    "ruleCode", plan.ruleCode(),
                    "title", title,
                    "message", message != null ? message : "",
                    "modelCode", modelCode != null ? modelCode : "",
                    "recordPid", recordPid != null ? recordPid : "",
                    "dueDate", dueDate != null ? dueDate : ""),
                    plan, assignee, assigneeUserIds, userId, modelCode, recordPid));
            item.setClientItemId(clientItemId(plan, recordPid, userId, title));
            item.setExpiresAt(parseInstant(dueDate));

            InboxItem created;
            try {
                created = inboxService.createItem(item);
            } catch (RuntimeException e) {
                throw taskFailure(plan, "task_write_failed", assignee, assigneeUserIds, userId,
                        modelCode, recordPid, "CREATE_TASK failed: " + ActionFailurePayload.messageOf(e), e);
            }
            if (created != null && created.getId() != null) {
                inboxItemIds.add(created.getId());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("delivery", "inbox");
        result.put("itemType", "task");
        result.put("createdCount", inboxItemIds.size());
        result.put("assigneeUserIds", assigneeUserIds);
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
                            "CREATE_TASK ROLE target requires a role code", raw, "ROLE", modelCode, recordPid, null);
                }
                ids.addAll(userRoleMapper.findUserIdsByRoleCode(roleCode, tenantId));
                continue;
            }
            ids.add(parseUserId(plan, value, value, raw, modelCode, recordPid));
        }
        if (ids.isEmpty()) {
            throw targetResolutionFailure(plan, "CREATE_TASK assignee resolved no users: " + raw, raw,
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
                .with("itemType", "task")
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
                    "CREATE_TASK target must be USER:<id>, ROLE:<code>, or numeric user id: " + original,
                    target, targetKind(original), modelCode, recordPid, null);
        }
    }

    private String cardPayload(
            Map<String, Object> card,
            ResolvedActionPlan plan,
            String assignee,
            List<Long> assigneeUserIds,
            Long userId,
            String modelCode,
            String recordPid) {
        try {
            return objectMapper.writeValueAsString(card);
        } catch (JsonProcessingException e) {
            throw taskFailure(plan, "action_payload_serialization_failed", assignee, assigneeUserIds, userId,
                    modelCode, recordPid, "CREATE_TASK card payload serialization failed: " + e.getMessage(), e);
        }
    }

    private static ActionExecutionException taskFailure(
            ResolvedActionPlan plan,
            String failureReason,
            String assignee,
            List<Long> assigneeUserIds,
            Long userId,
            String modelCode,
            String recordPid,
            String message,
            Throwable cause) {
        return ActionFailurePayload.builder(plan, failureReason)
                .with("delivery", "inbox")
                .with("itemType", "task")
                .with("targetType", targetKind(assignee))
                .with("target", assignee)
                .with("assigneeUserIds", assigneeUserIds)
                .with("assigneeUserId", userId)
                .with("modelCode", modelCode)
                .with("recordPid", recordPid)
                .with("errorMessage", message)
                .exception(message, cause);
    }

    private static String clientItemId(ResolvedActionPlan plan, String recordPid, Long userId, String title) {
        String base = firstNonBlank(plan.idempotencyKey(),
                "event_policy_task_" + safe(plan.ruleCode()) + "_" + safe(recordPid) + "_"
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

    private static String safe(String value) {
        return value == null || value.isBlank() ? "none" : value.replaceAll("[^A-Za-z0-9_.:-]", "_");
    }
}
