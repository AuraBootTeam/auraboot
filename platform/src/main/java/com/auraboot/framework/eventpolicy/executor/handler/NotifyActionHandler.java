package com.auraboot.framework.eventpolicy.executor.handler;

import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.eventpolicy.executor.ActionExecutionException;
import com.auraboot.framework.eventpolicy.executor.ActionHandler;
import com.auraboot.framework.eventpolicy.executor.ActionProviderDependency;
import com.auraboot.framework.eventpolicy.model.ResolvedActionPlan;
import com.auraboot.framework.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Production {@code NOTIFY} {@link ActionHandler} (docs/2.md §7): sends an in-app notification via the
 * platform {@link NotificationService} when a policy rule matches. Wires the EventPolicy executor to
 * the real notification subsystem (additive — no change to that subsystem).
 *
 * <p>Supported target formats: {@code USER:<publicPid-or-userId>}, {@code ROLE:<roleCode>},
 * {@code GROUP:<teamPid>} and {@code TEAM:<teamPid>}. Unsupported targets throw (the executor records
 * the failure per the policy FailureStrategy) rather than silently dropping the notification.
 *
 * <p>The optional payload pair {@code sourceType}/{@code sourceId} identifies an internal model
 * record for native notification navigation. Both support decision-context templates and must
 * resolve to safe route segments. Omitting both preserves the event-policy source reference;
 * providing an incomplete or unsafe pair fails before delivery. Opening a source record still
 * uses the client's normal tenant and record authorization.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotifyActionHandler implements ActionHandler {

    private static final String USER_PREFIX = "USER:";
    private static final String ROLE_PREFIX = "ROLE:";
    private static final String GROUP_PREFIX = "GROUP:";
    private static final String TEAM_PREFIX = "TEAM:";
    private static final Pattern TEMPLATE = Pattern.compile("\\$\\{([^}]+)}");

    private final NotificationService notificationService;
    private final com.auraboot.framework.plugin.extension.IdentityDirectoryAccessor identities;

    @Override
    public boolean supports(String actionType) {
        return "NOTIFY".equals(actionType);
    }

    @Override
    public List<ActionProviderDependency> runtimeProviderDependencies() {
        return List.of(ActionProviderDependencies.notification());
    }

    @Override
    public void execute(ResolvedActionPlan plan, DecisionContext context) {
        executeWithResult(plan, context);
    }

    @Override
    public Map<String, Object> executeWithResult(ResolvedActionPlan plan, DecisionContext context) {
        String target = render(plan.target(), context);
        Map<String, Object> payload = plan.payload() != null ? plan.payload() : Map.of();
        String title = str(render(payload.get("title"), context), "Policy notification");
        String content = str(render(payload.get("content"), context), "");
        SourceReference source = sourceReference(plan, payload, context);
        if (target == null || target.isBlank()) {
            throw notifyFailure(plan, "action_target_missing", "NOTIFY requires target",
                    target, "UNKNOWN", null, null, null, null);
        }
        if (target != null && target.startsWith(USER_PREFIX)) {
            long userId = parseUserId(plan, target);
            try {
                notificationService.sendInApp(userId, title, content, "EVENT_POLICY", source.type(), source.id());
            } catch (RuntimeException e) {
                throw notifyFailure(plan, "notify_delivery_failed",
                        "NOTIFY failed: " + ActionFailurePayload.messageOf(e),
                        target, "USER", String.valueOf(userId), null, ActionFailurePayload.messageOf(e), e);
            }
            return result("USER", String.valueOf(userId), title, List.of(userId), source);
        }
        if (target != null && target.startsWith(ROLE_PREFIX)) {
            String recipientId = suffix(plan, target, ROLE_PREFIX);
            List<Long> targetUserIds = sendToRecipient(plan, target, "ROLE", "role", recipientId, title, content, source);
            return result("ROLE", recipientId, title, targetUserIds, source);
        }
        if (target != null && target.startsWith(GROUP_PREFIX)) {
            String recipientId = suffix(plan, target, GROUP_PREFIX);
            List<Long> targetUserIds = sendToRecipient(plan, target, "GROUP", "group", recipientId, title, content, source);
            return result("GROUP", recipientId, title, targetUserIds, source);
        }
        if (target != null && target.startsWith(TEAM_PREFIX)) {
            String recipientId = suffix(plan, target, TEAM_PREFIX);
            List<Long> targetUserIds = sendToRecipient(plan, target, "TEAM", "team", recipientId, title, content, source);
            return result("TEAM", recipientId, title, targetUserIds, source);
        }
        throw notifyFailure(plan, "target_invalid",
                "NotifyActionHandler supports USER:<userId>, ROLE:<roleCode>, GROUP:<teamPid>, TEAM:<teamPid>. Got: "
                        + target,
                target, targetType(target), null, null, null, null);
    }

    private List<Long> sendToRecipient(ResolvedActionPlan plan,
                                       String target,
                                       String recipientType,
                                       String recipientKind,
                                       String recipientId,
                                       String title,
                                       String content, SourceReference source) {
        List<Long> targetUserIds;
        try {
            targetUserIds = notificationService.sendInAppToRecipient(recipientKind, recipientId, title, content,
                    "EVENT_POLICY", source.type(), source.id());
        } catch (RuntimeException e) {
            throw notifyFailure(plan, "notify_delivery_failed",
                    "NOTIFY failed: " + ActionFailurePayload.messageOf(e),
                    target, recipientType, recipientId, null, ActionFailurePayload.messageOf(e), e);
        }
        if (targetUserIds == null || targetUserIds.isEmpty()) {
            throw notifyFailure(plan, "target_resolved_no_users",
                    "NOTIFY target resolved no users: " + target,
                    target, recipientType, recipientId, 0, null, null);
        }
        return targetUserIds;
    }

    private static Map<String, Object> result(String recipientType, String recipientId, String title,
                                              List<Long> targetUserIds, SourceReference source) {
        List<Long> deliveredUserIds = targetUserIds == null ? List.of() : List.copyOf(targetUserIds);
        return Map.of(
                "channel", "in_app",
                "recipientType", recipientType,
                "recipientId", recipientId,
                "sentCount", deliveredUserIds.size(),
                "recipientCount", deliveredUserIds.size(),
                "targetUserIds", deliveredUserIds,
                "title", title,
                "sourceId", source.id(),
                "sourceType", source.type()
        );
    }

    private record SourceReference(String type, String id) {}

    private static SourceReference sourceReference(ResolvedActionPlan plan,
                                                    Map<String, Object> payload,
                                                    DecisionContext context) {
        Object rawType = payload.get("sourceType");
        Object rawId = payload.get("sourceId");
        if (rawType == null && rawId == null) {
            return new SourceReference("EVENT_POLICY", plan.ruleCode());
        }
        String type = rawType instanceof String ? render(rawType, context) : null;
        String id = rawId instanceof String ? render(rawId, context) : null;
        // These values become internal route segments in all native clients.
        // Require a complete safe pair; malformed links must fail before sending.
        if (type == null || !type.matches("[A-Za-z][A-Za-z0-9_]{0,63}")
                || id == null || !id.matches("[A-Za-z0-9_-]{1,128}")) {
            throw notifyFailure(plan, "notification_source_invalid",
                    "NOTIFY sourceType and sourceId must be safe internal record identifiers",
                    plan.target(), targetType(plan.target()), null, null, null, null);
        }
        return new SourceReference(type, id);
    }

    private long parseUserId(ResolvedActionPlan plan, String target) {
        String identity = suffix(plan, target, USER_PREFIX);
        Long tenantId = com.auraboot.framework.application.tenant.MetaContext.getCurrentTenantId();
        if (tenantId == null) throw notifyFailure(plan, "tenant_context_required", "NOTIFY requires the current space",
                target, "USER", null, null, null, null);
        var user = identities.findActiveTenantUser(tenantId, identity);
        if (user == null) throw notifyFailure(plan, "recipient_unavailable", "Notification recipient is not an active member of this space",
                target, "USER", null, 0, null, null);
        return user.id();
    }

    private static String suffix(ResolvedActionPlan plan, String target, String prefix) {
        String value = target.substring(prefix.length()).trim();
        if (value.isEmpty()) {
            throw notifyFailure(plan, "target_value_missing",
                    "Invalid NOTIFY target, missing value after " + prefix,
                    target, targetType(target), null, null, null, null);
        }
        return value;
    }

    private static ActionExecutionException notifyFailure(
            ResolvedActionPlan plan,
            String failureReason,
            String message,
            String target,
            String recipientType,
            String recipientId,
            Integer resolvedCount,
            String error,
            Throwable cause) {
        return ActionFailurePayload.builder(plan, failureReason)
                .with("channel", "in_app")
                .with("targetType", recipientType)
                .with("target", target)
                .with("recipientType", recipientType)
                .with("recipientId", recipientId)
                .with("resolvedCount", resolvedCount)
                .with("field", "target")
                .with("errorMessage", error)
                .exception(message, cause);
    }

    private static String targetType(String target) {
        if (target == null) {
            return "UNKNOWN";
        }
        if (target.startsWith(USER_PREFIX)) {
            return "USER";
        }
        if (target.startsWith(ROLE_PREFIX)) {
            return "ROLE";
        }
        if (target.startsWith(GROUP_PREFIX)) {
            return "GROUP";
        }
        if (target.startsWith(TEAM_PREFIX)) {
            return "TEAM";
        }
        return "UNKNOWN";
    }

    private static String str(Object v, String dflt) {
        return v != null ? String.valueOf(v) : dflt;
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
}
