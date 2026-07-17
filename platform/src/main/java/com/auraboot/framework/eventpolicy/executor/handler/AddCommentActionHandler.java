package com.auraboot.framework.eventpolicy.executor.handler;

import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.eventpolicy.executor.ActionExecutionException;
import com.auraboot.framework.eventpolicy.executor.ActionHandler;
import com.auraboot.framework.eventpolicy.executor.ActionProviderDependency;
import com.auraboot.framework.eventpolicy.model.ResolvedActionPlan;
import com.auraboot.framework.meta.service.RecordCommentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Production {@code ADD_COMMENT} {@link ActionHandler} (docs/2.md §7): adds a record comment via the
 * platform {@link RecordCommentService} when a policy rule matches, attaching to the event's record
 * (modelCode/recordPid read from the decision context). Additive — no change to the comment
 * subsystem. The comment content comes from the action payload.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AddCommentActionHandler implements ActionHandler {

    private static final Pattern TEMPLATE = Pattern.compile("\\$\\{([^}]+)}");

    private final RecordCommentService recordCommentService;

    @Override
    public boolean supports(String actionType) {
        return "ADD_COMMENT".equals(actionType);
    }

    @Override
    public List<ActionProviderDependency> runtimeProviderDependencies() {
        return List.of(ActionProviderDependencies.recordComment());
    }

    @Override
    public void execute(ResolvedActionPlan plan, DecisionContext context) {
        executeWithResult(plan, context);
    }

    @Override
    public Map<String, Object> executeWithResult(ResolvedActionPlan plan, DecisionContext context) {
        String modelCode = resolveString(context, "entityCode");
        String recordPid = resolveString(context, "recordPid");
        if (modelCode == null || recordPid == null) {
            throw new ActionExecutionException(
                    "ADD_COMMENT requires record.entityCode + record.recordPid in the context",
                    failurePayload(plan, "comment_context_missing", modelCode, recordPid)
                            .with("requiredContext", List.of("record.entityCode", "record.recordPid"))
                            .build(),
                    null);
        }
        Map<String, Object> payload = plan.payload() != null ? plan.payload() : Map.of();
        String content = render(payload.get("content"), context);
        if (content == null || String.valueOf(content).isBlank()) {
            throw new ActionExecutionException(
                    "ADD_COMMENT requires a non-empty payload.content",
                    failurePayload(plan, "comment_content_missing", modelCode, recordPid)
                            .with("field", "payload.content")
                            .build(),
                    null);
        }
        String mentions = render(payload.get("mentions"), context);
        Map<String, Object> comment;
        try {
            comment = recordCommentService.addComment(modelCode, recordPid, content,
                    mentions != null && !mentions.isBlank() ? mentions : null);
        } catch (RuntimeException e) {
            throw new ActionExecutionException(
                    "ADD_COMMENT failed: " + messageOf(e),
                    failurePayload(plan, "comment_write_failed", modelCode, recordPid)
                            .with("content", content)
                            .with("mentions", mentions != null && !mentions.isBlank() ? mentions : null)
                            .with("errorMessage", messageOf(e))
                            .build(),
                    e);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("modelCode", modelCode);
        result.put("recordPid", recordPid);
        result.put("content", content);
        if (mentions != null && !mentions.isBlank()) {
            result.put("mentions", mentions);
        }
        Object commentPid = comment != null ? comment.get("commentPid") : null;
        if (commentPid != null) {
            result.put("commentPid", commentPid);
        }
        return result;
    }

    private static String resolveString(DecisionContext context, String field) {
        DecisionContext.PathValue pv = context.resolve(Scope.RECORD, field);
        return pv.present() && pv.value() != null ? String.valueOf(pv.value()) : null;
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

    private static PayloadBuilder failurePayload(
            ResolvedActionPlan plan,
            String failureReason,
            String modelCode,
            String recordPid) {
        return new PayloadBuilder()
                .with("failureReason", failureReason)
                .with("modelCode", modelCode)
                .with("recordPid", recordPid)
                .with("actionType", plan != null ? plan.type() : null);
    }

    private static String messageOf(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static final class PayloadBuilder {
        private final Map<String, Object> payload = new LinkedHashMap<>();

        private PayloadBuilder with(String key, Object value) {
            if (value != null) {
                payload.put(key, value);
            }
            return this;
        }

        private Map<String, Object> build() {
            return payload;
        }
    }
}
