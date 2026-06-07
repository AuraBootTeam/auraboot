package com.auraboot.framework.eventpolicy.model;

import java.util.Map;

/**
 * An action plan after the resolver has ordered it, rendered its idempotency key, and attributed it
 * to the rule that produced it (docs/2.md §X.5). Still a plan — the executor runs it.
 */
public record ResolvedActionPlan(
        String ruleCode,
        String type,
        String target,
        int order,
        Map<String, Object> payload,
        String idempotencyKey
) {}
