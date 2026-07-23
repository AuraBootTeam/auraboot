package com.auraboot.framework.eventpolicy.executor.handler;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.eventpolicy.executor.ActionExecutionException;
import com.auraboot.framework.eventpolicy.executor.ActionHandler;
import com.auraboot.framework.eventpolicy.executor.ActionProviderDependency;
import com.auraboot.framework.eventpolicy.model.ResolvedActionPlan;
import com.auraboot.framework.meta.service.DynamicDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Production {@code UPDATE_RECORD} / {@code PATCH_RECORD} {@link ActionHandler} (docs/2.md §7):
 * updates fields on the event's record via the platform {@link DynamicDataService} when a policy rule
 * matches — the most common policy side effect (e.g. set status=ESCALATED). The record
 * (modelCode/recordPid) is read from the decision context; the fields come from {@code payload.fields}.
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
    public List<ActionProviderDependency> runtimeProviderDependencies() {
        return List.of(ActionProviderDependencies.dynamicData());
    }

    @Override
    @SuppressWarnings("unchecked")
    public void execute(ResolvedActionPlan plan, DecisionContext context) {
        executeWithResult(plan, context);
    }

    @Override
    public Map<String, Object> executeWithResult(ResolvedActionPlan plan, DecisionContext context) {
        String modelCode = resolveString(context, "entityCode");
        String recordPid = resolveString(context, "recordPid");
        if (modelCode == null || recordPid == null) {
            throw new ActionExecutionException(
                    "UPDATE_RECORD requires record.entityCode + record.recordPid in the context",
                    failurePayload(plan, "record_context_missing", modelCode, recordPid)
                            .with("requiredContext", List.of("record.entityCode", "record.recordPid"))
                            .build(),
                    null);
        }
        Map<String, Object> payload = plan.payload() != null ? plan.payload() : Map.of();
        Object fieldsObj = payload.get("fields");
        if (!(fieldsObj instanceof Map<?, ?> fields) || fields.isEmpty()) {
            throw new ActionExecutionException(
                    "UPDATE_RECORD requires a non-empty payload.fields object",
                    failurePayload(plan, "update_fields_missing", modelCode, recordPid)
                            .with("field", "payload.fields")
                            .build(),
                    null);
        }
        Map<String, Object> fieldMap = new LinkedHashMap<>();
        fields.forEach((key, value) -> fieldMap.put(String.valueOf(key), value));
        try {
            // System-triggered policy action: the event runtime already selected this record
            // (recordPid comes from the decision context), and AFTER_COMMIT policies run without
            // a caller's data-permission projection. Bypass data permission so the platform's
            // internal pre-update read-back (a visibility gate meant for interactive callers)
            // does not deny a write the policy engine is authorized to make.
            MetaContext.runWithoutDataPermission(() -> {
                dynamicDataService.update(modelCode, recordPid, fieldMap);
            });
        } catch (RuntimeException e) {
            throw new ActionExecutionException(
                    "UPDATE_RECORD failed: " + messageOf(e),
                    failurePayload(plan, "record_update_failed", modelCode, recordPid)
                            .with("updatedFields", List.copyOf(fieldMap.keySet()))
                            .with("fieldCount", fieldMap.size())
                            .with("errorMessage", messageOf(e))
                            .build(),
                    e);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("modelCode", modelCode);
        result.put("recordPid", recordPid);
        result.put("updatedFields", List.copyOf(fieldMap.keySet()));
        result.put("actionType", plan.type());
        return result;
    }

    private static String resolveString(DecisionContext context, String field) {
        DecisionContext.PathValue pv = context.resolve(Scope.RECORD, field);
        return pv.present() && pv.value() != null ? String.valueOf(pv.value()) : null;
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
