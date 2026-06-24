package com.auraboot.framework.eventpolicy.executor.handler;

import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.eventpolicy.executor.ActionHandler;
import com.auraboot.framework.eventpolicy.model.ResolvedActionPlan;
import com.auraboot.framework.meta.service.RecordCommentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

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

    private final RecordCommentService recordCommentService;

    @Override
    public boolean supports(String actionType) {
        return "ADD_COMMENT".equals(actionType);
    }

    @Override
    public void execute(ResolvedActionPlan plan, DecisionContext context) {
        String modelCode = resolveString(context, "entityCode");
        String recordPid = resolveString(context, "recordPid");
        if (modelCode == null || recordPid == null) {
            throw new IllegalStateException(
                    "ADD_COMMENT requires record.entityCode + record.recordPid in the context; got model="
                            + modelCode + ", record=" + recordPid);
        }
        Map<String, Object> payload = plan.payload() != null ? plan.payload() : Map.of();
        Object content = payload.get("content");
        if (content == null || String.valueOf(content).isBlank()) {
            throw new IllegalArgumentException("ADD_COMMENT requires a non-empty payload.content");
        }
        Object mentions = payload.get("mentions");
        recordCommentService.addComment(modelCode, recordPid, String.valueOf(content),
                mentions != null ? String.valueOf(mentions) : null);
    }

    private static String resolveString(DecisionContext context, String field) {
        DecisionContext.PathValue pv = context.resolve(Scope.RECORD, field);
        return pv.present() && pv.value() != null ? String.valueOf(pv.value()) : null;
    }
}
