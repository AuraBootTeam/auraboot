package com.auraboot.framework.eventpolicy.executor.handler;

import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.eventpolicy.executor.ActionHandler;
import com.auraboot.framework.eventpolicy.model.ResolvedActionPlan;
import com.auraboot.framework.meta.service.DynamicDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Production {@code UPDATE_RECORD} / {@code PATCH_RECORD} {@link ActionHandler} (docs/2.md §7):
 * updates fields on the event's record via the platform {@link DynamicDataService} when a policy rule
 * matches — the most common policy side effect (e.g. set status=ESCALATED). The record
 * (modelCode/recordId) is read from the decision context; the fields come from {@code payload.fields}.
 * Additive — no change to the dynamic-data subsystem; {@code update} applies a partial field map
 * (PATCH semantics), so both action types map to it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UpdateRecordActionHandler implements ActionHandler {

    private final DynamicDataService dynamicDataService;

    @Override
    public boolean supports(String actionType) {
        return "UPDATE_RECORD".equals(actionType) || "PATCH_RECORD".equals(actionType);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void execute(ResolvedActionPlan plan, DecisionContext context) {
        String modelCode = resolveString(context, "entityCode");
        String recordId = resolveString(context, "recordId");
        if (modelCode == null || recordId == null) {
            throw new IllegalStateException(
                    "UPDATE_RECORD requires record.entityCode + record.recordId in the context; got model="
                            + modelCode + ", record=" + recordId);
        }
        Map<String, Object> payload = plan.payload() != null ? plan.payload() : Map.of();
        Object fieldsObj = payload.get("fields");
        if (!(fieldsObj instanceof Map<?, ?> fields) || fields.isEmpty()) {
            throw new IllegalArgumentException("UPDATE_RECORD requires a non-empty payload.fields object");
        }
        dynamicDataService.update(modelCode, recordId, (Map<String, Object>) fields);
    }

    private static String resolveString(DecisionContext context, String field) {
        DecisionContext.PathValue pv = context.resolve(Scope.RECORD, field);
        return pv.present() && pv.value() != null ? String.valueOf(pv.value()) : null;
    }
}
