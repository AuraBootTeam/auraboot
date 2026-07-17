package com.auraboot.framework.eventpolicy.executor.handler;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.eventpolicy.executor.ActionExecutionException;
import com.auraboot.framework.eventpolicy.executor.ActionHandler;
import com.auraboot.framework.eventpolicy.executor.ActionProviderDependency;
import com.auraboot.framework.eventpolicy.model.ResolvedActionPlan;
import com.auraboot.framework.im.model.ImConversation;
import com.auraboot.framework.im.model.ImMessage;
import com.auraboot.framework.im.service.ImConversationService;
import com.auraboot.framework.im.service.ImMessageService;
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
public class SendImActionHandler implements ActionHandler {

    private static final String USER_PREFIX = "USER:";
    private static final String ROLE_PREFIX = "ROLE:";
    private static final Pattern TEMPLATE = Pattern.compile("\\$\\{([^}]+)}");
    private static final int CLIENT_MSG_ID_MAX_LENGTH = 64;

    private final ImConversationService conversationService;
    private final ImMessageService messageService;
    private final UserRoleMapper userRoleMapper;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(String actionType) {
        return "SEND_IM".equals(actionType);
    }

    @Override
    public List<ActionProviderDependency> runtimeProviderDependencies() {
        return List.of(ActionProviderDependencies.im());
    }

    @Override
    public void execute(ResolvedActionPlan plan, DecisionContext context) {
        executeWithResult(plan, context);
    }

    @Override
    public Map<String, Object> executeWithResult(ResolvedActionPlan plan, DecisionContext context) {
        if (!MetaContext.exists() || MetaContext.getCurrentTenantId() == null) {
            throw ActionFailurePayload.builder(plan, "tenant_context_missing")
                    .with("channel", "im")
                    .with("requiredContext", List.of("tenantId"))
                    .exception("Tenant context required for SEND_IM action", null);
        }
        Map<String, Object> payload = plan.payload() != null ? plan.payload() : Map.of();
        String modelCode = resolveRecordString(context, "entityCode");
        String recordPid = resolveRecordString(context, "recordPid");
        String target = firstNonBlank(
                render(plan.target(), context),
                render(payload.get("target"), context));
        if (target == null) {
            throw ActionFailurePayload.builder(plan, "action_target_missing")
                    .with("channel", "im")
                    .with("field", "target")
                    .with("requiredContext", List.of("action.target", "payload.target"))
                    .with("modelCode", modelCode)
                    .with("recordPid", recordPid)
                    .exception("SEND_IM requires target", null);
        }
        String content = firstNonBlank(
                render(payload.get("content"), context),
                render(payload.get("message"), context));
        if (content == null || content.isBlank()) {
            throw ActionFailurePayload.builder(plan, "payload_content_missing")
                    .with("channel", "im")
                    .with("field", "payload.content")
                    .with("target", target)
                    .with("modelCode", modelCode)
                    .with("recordPid", recordPid)
                    .exception("SEND_IM requires payload.content", null);
        }
        String title = render(payload.get("title"), context);
        String channel = firstNonBlank(render(payload.get("channel"), context), "im");

        Long tenantId = MetaContext.getCurrentTenantId();
        List<Long> targetUserIds = resolveUserTargets(plan, target, tenantId, modelCode, recordPid);
        List<Long> conversationIds = new ArrayList<>();
        List<Long> messageIds = new ArrayList<>();

        for (Long userId : targetUserIds) {
            ImConversation conversation;
            try {
                conversation = conversationService.findOrCreateBotConversation(userId, tenantId);
            } catch (RuntimeException e) {
                throw imDeliveryFailure(plan, target, targetUserIds, userId, modelCode, recordPid,
                        ActionFailurePayload.messageOf(e), e);
            }
            if (conversation == null || conversation.getId() == null) {
                throw imDeliveryFailure(plan, target, targetUserIds, userId, modelCode, recordPid,
                        "SEND_IM could not resolve bot conversation for user " + userId, null);
            }
            String cardPayload = cardPayload(Map.of(
                    "actionType", "SEND_IM",
                    "ruleCode", plan.ruleCode(),
                    "title", title != null ? title : "",
                    "channel", channel,
                    "modelCode", modelCode != null ? modelCode : "",
                    "recordPid", recordPid != null ? recordPid : ""),
                    plan, target, targetUserIds, userId, modelCode, recordPid);
            ImMessage message;
            try {
                message = messageService.sendSystemMessage(
                        conversation.getId(),
                        tenantId,
                        "system",
                        content,
                        cardPayload,
                        clientMsgId(plan, recordPid, userId, content));
            } catch (RuntimeException e) {
                throw imDeliveryFailure(plan, target, targetUserIds, userId, modelCode, recordPid,
                        ActionFailurePayload.messageOf(e), e);
            }
            conversationIds.add(conversation.getId());
            if (message != null && message.getId() != null) {
                messageIds.add(message.getId());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("channel", channel);
        result.put("sentCount", messageIds.size());
        result.put("targetUserIds", targetUserIds);
        result.put("conversationIds", conversationIds);
        result.put("messageIds", messageIds);
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
                            "SEND_IM ROLE target requires a role code", raw, "ROLE", modelCode, recordPid, null);
                }
                ids.addAll(userRoleMapper.findUserIdsByRoleCode(roleCode, tenantId));
                continue;
            }
            ids.add(parseUserId(plan, value, value, raw, modelCode, recordPid));
        }
        if (ids.isEmpty()) {
            throw targetResolutionFailure(plan, "SEND_IM target resolved no users: " + raw, raw,
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
                .with("channel", "im")
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
                    "SEND_IM target must be USER:<id>, ROLE:<code>, or numeric user id: " + original,
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
            throw imFailure(plan, "action_payload_serialization_failed", target, targetUserIds, userId,
                    modelCode, recordPid, "SEND_IM card payload serialization failed: " + e.getMessage(), e);
        }
    }

    private static ActionExecutionException imDeliveryFailure(
            ResolvedActionPlan plan,
            String target,
            List<Long> targetUserIds,
            Long userId,
            String modelCode,
            String recordPid,
            String error,
            RuntimeException cause) {
        return imFailure(plan, "im_delivery_failed", target, targetUserIds, userId,
                modelCode, recordPid, "SEND_IM failed: " + error, cause);
    }

    private static ActionExecutionException imFailure(
            ResolvedActionPlan plan,
            String failureReason,
            String target,
            List<Long> targetUserIds,
            Long userId,
            String modelCode,
            String recordPid,
            String message,
            Throwable cause) {
        return ActionFailurePayload.builder(plan, failureReason)
                .with("channel", "im")
                .with("targetType", targetKind(target))
                .with("target", target)
                .with("targetUserIds", targetUserIds)
                .with("targetUserId", userId)
                .with("modelCode", modelCode)
                .with("recordPid", recordPid)
                .with("errorMessage", message)
                .exception(message, cause);
    }

    private static String clientMsgId(ResolvedActionPlan plan, String recordPid, Long userId, String content) {
        String base = firstNonBlank(plan.idempotencyKey(),
                "event_policy_im_" + safe(plan.ruleCode()) + "_" + safe(recordPid) + "_"
                        + Integer.toHexString(content.hashCode()));
        return ActionClientIdSupport.fit(base + ":" + userId, CLIENT_MSG_ID_MAX_LENGTH);
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
