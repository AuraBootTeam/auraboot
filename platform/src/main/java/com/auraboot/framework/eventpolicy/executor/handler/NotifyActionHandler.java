package com.auraboot.framework.eventpolicy.executor.handler;

import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.eventpolicy.executor.ActionHandler;
import com.auraboot.framework.eventpolicy.model.ResolvedActionPlan;
import com.auraboot.framework.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Production {@code NOTIFY} {@link ActionHandler} (docs/2.md §7): sends an in-app notification via the
 * platform {@link NotificationService} when a policy rule matches. Wires the EventPolicy executor to
 * the real notification subsystem (additive — no change to that subsystem).
 *
 * <p>Target format {@code USER:<userId>} is supported today (the in-app channel persists a
 * notification for that user). Role / group fan-out ({@code ROLE:<code>}) needs recipient resolution
 * and is an explicit follow-on — an unsupported target throws (the executor records the failure per
 * the policy FailureStrategy) rather than silently dropping the notification.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotifyActionHandler implements ActionHandler {

    private static final String USER_PREFIX = "USER:";

    private final NotificationService notificationService;

    @Override
    public boolean supports(String actionType) {
        return "NOTIFY".equals(actionType);
    }

    @Override
    public void execute(ResolvedActionPlan plan, DecisionContext context) {
        String target = plan.target();
        if (target == null || !target.startsWith(USER_PREFIX)) {
            throw new UnsupportedOperationException(
                    "NotifyActionHandler supports USER:<userId> targets; role/group fan-out is a follow-on. Got: " + target);
        }
        long userId;
        try {
            userId = Long.parseLong(target.substring(USER_PREFIX.length()).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid NOTIFY target, expected USER:<numericId>: " + target, e);
        }
        Map<String, Object> payload = plan.payload() != null ? plan.payload() : Map.of();
        String title = str(payload.get("title"), "Policy notification");
        String content = str(payload.get("content"), "");
        notificationService.sendInApp(userId, title, content, "EVENT_POLICY", "EVENT_POLICY", plan.ruleCode());
    }

    private static String str(Object v, String dflt) {
        return v != null ? String.valueOf(v) : dflt;
    }
}
