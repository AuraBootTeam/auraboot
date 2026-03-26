package com.auraboot.framework.meta.validation;

import com.auraboot.framework.meta.dto.CrossFieldRule;
import com.auraboot.framework.meta.dto.RuleOverride;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges model-level rules with command-level overrides.
 *
 * Merge strategy:
 * - override with disabled=true → remove rule
 * - override with matching id → replace rule entirely
 * - override with new id → append as new rule
 *
 * disabled takes precedence over assert (if both set, rule is removed).
 */
public final class RuleMerger {

    private RuleMerger() {}

    public static List<CrossFieldRule> merge(
            List<CrossFieldRule> modelRules,
            List<RuleOverride> overrides) {

        if (overrides == null || overrides.isEmpty()) {
            return new ArrayList<>(modelRules);
        }

        // Build override map preserving insertion order
        Map<String, RuleOverride> overrideMap = new LinkedHashMap<>();
        for (RuleOverride o : overrides) {
            overrideMap.put(o.getId(), o);
        }

        List<CrossFieldRule> result = new ArrayList<>();

        // Process model rules: apply matching overrides
        for (CrossFieldRule rule : modelRules) {
            RuleOverride override = overrideMap.remove(rule.getId());
            if (override == null) {
                // No override — keep original
                result.add(rule);
            } else if (Boolean.TRUE.equals(override.getDisabled())) {
                // Disabled — skip (remove rule)
            } else {
                // Replace with override
                result.add(override);
            }
        }

        // Append remaining overrides (new rules not in model)
        for (RuleOverride override : overrideMap.values()) {
            if (!Boolean.TRUE.equals(override.getDisabled())) {
                result.add(override);
            }
        }

        return result;
    }
}
