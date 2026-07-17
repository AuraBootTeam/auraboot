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
 * <p>Supported target formats: {@code USER:<userId>}, {@code ROLE:<roleCode>},
 * {@code GROUP:<teamPid>} and {@code TEAM:<teamPid>}. Unsupported targets throw (the executor records
 * the failure per the policy FailureStrategy) rather than silently dropping the notification.
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
        if (target == null || target.isBlank()) {
            throw notifyFailure(plan, "action_target_missing", "NOTIFY requires target",
                    target, "UNKNOWN", null, null, null, null);
        }
        if (target != null && target.startsWith(USER_PREFIX)) {
            long userId = parseUserId(plan, target);
            try {
                notificationService.sendInApp(userId, title, content, "EVENT_POLICY", "EVENT_POLICY", plan.ruleCode());
            } catch (RuntimeException e) {
                throw notifyFailure(plan, "notify_delivery_failed",
                        "NOTIFY failed: " + ActionFailurePayload.messageOf(e),
                        target, "USER", String.valueOf(userId), null, ActionFailurePayload.messageOf(e), e);
            }
            return result("USER", String.valueOf(userId), title, plan, List.of(userId));
        }
        if (target != null && target.startsWith(ROLE_PREFIX)) {
            String recipientId = suffix(plan, target, ROLE_PREFIX);
            List<Long> targetUserIds = sendToRecipient(plan, target, "ROLE", "role", recipientId, title, content);
            return result("ROLE", recipientId, title, plan, targetUserIds);
        }
        if (target != null && target.startsWith(GROUP_PREFIX)) {
            String recipientId = suffix(plan, target, GROUP_PREFIX);
            List<Long> targetUserIds = sendToRecipient(plan, target, "GROUP", "group", recipientId, title, content);
            return result("GROUP", recipientId, title, plan, targetUserIds);
        }
        if (target != null && target.startsWith(TEAM_PREFIX)) {
            String recipientId = suffix(plan, target, TEAM_PREFIX);
            List<Long> targetUserIds = sendToRecipient(plan, target, "TEAM", "team", recipientId, title, content);
            return result("TEAM", recipientId, title, plan, targetUserIds);
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
                                       String content) {
        List<Long> targetUserIds;
        try {
            targetUserIds = notificationService.sendInAppToRecipient(recipientKind, recipientId, title, content,
                    "EVENT_POLICY", "EVENT_POLICY", plan.ruleCode());
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
                                              ResolvedActionPlan plan, List<Long> targetUserIds) {
        List<Long> deliveredUserIds = targetUserIds == null ? List.of() : List.copyOf(targetUserIds);
        return Map.of(
                "channel", "in_app",
                "recipientType", recipientType,
                "recipientId", recipientId,
                "sentCount", deliveredUserIds.size(),
                "recipientCount", deliveredUserIds.size(),
                "targetUserIds", deliveredUserIds,
                "title", title,
                "sourceId", plan.ruleCode()
        );
    }

    private static long parseUserId(ResolvedActionPlan plan, String target) {
        try {
            return Long.parseLong(suffix(plan, target, USER_PREFIX));
        } catch (NumberFormatException e) {
            throw notifyFailure(plan, "target_invalid",
                    "Invalid NOTIFY target, expected USER:<numericId>: " + target,
                    target, "USER", null, null, e.getMessage(), e);
        }
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
