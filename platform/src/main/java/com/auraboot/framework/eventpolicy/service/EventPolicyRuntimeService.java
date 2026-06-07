package com.auraboot.framework.eventpolicy.service;

import com.auraboot.framework.eventpolicy.model.EventPolicyExecutionResult;
import com.auraboot.framework.eventpolicy.model.EventPolicyResult;

import java.util.Map;

/**
 * Runtime service: resolves the current PUBLISHED policy for (eventType, targetType, targetKey),
 * builds the domain object, evaluates it, and returns the result.
 *
 * <p>This slice returns resolved action plans only; an executor runs them later.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
public interface EventPolicyRuntimeService {

    /**
     * Evaluate the PUBLISHED policy for the given event + target + context.
     *
     * @param eventType  event that fired, e.g. "FORM_SUBMITTED"
     * @param targetType target entity type, e.g. "FORM"
     * @param targetKey  target instance key, e.g. a model code
     * @param context    scope-keyed context maps (keys are Scope codes: "record", "event", etc.)
     * @return the evaluation result; status NOT_MATCHED if no policy is found or no rule matches
     */
    EventPolicyResult run(String eventType, String targetType, String targetKey,
                          Map<String, Map<String, Object>> context);

    /**
     * Run the policy AND execute its resolved action plans via the PolicyExecutor (docs/2.md §2):
     * the end-to-end chokepoint — event → matched rules → ordered/idempotent action execution.
     * Uses the policy version's FailureStrategy. Returns both the decision and execution outcomes.
     */
    EventPolicyExecutionResult runAndExecute(String eventType, String targetType, String targetKey,
                                             Map<String, Map<String, Object>> context);
}
