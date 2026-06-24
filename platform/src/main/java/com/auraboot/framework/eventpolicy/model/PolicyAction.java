package com.auraboot.framework.eventpolicy.model;

import java.util.Map;

/**
 * An action plan template declared on a policy rule (docs/2.md §X.4). It is a <em>plan</em>, not
 * an execution — the EventPolicy runtime resolves and orders these; an executor runs them later.
 *
 * @param type   action type (NOTIFY / START_PROCESS / CREATE_TASK / ...), see docs/2.md §7
 * @param target opaque target descriptor, e.g. {@code "ROLE:support_manager"} or {@code "BPM:complaint_approval"}
 * @param order  intra-rule ordering hint (lower runs first)
 * @param payload action parameters (value-mapping only; no business logic)
 * @param idempotencyKeyTemplate template like
 *        {@code "${record.entityCode}:${record.recordPid}:${rule.ruleCode}:${action.type}"}
 */
public record PolicyAction(
        String type,
        String target,
        int order,
        Map<String, Object> payload,
        String idempotencyKeyTemplate
) {
    public PolicyAction {
        payload = payload == null ? Map.of() : payload;
    }
}
