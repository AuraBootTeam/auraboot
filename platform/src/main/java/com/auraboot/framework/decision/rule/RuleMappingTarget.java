package com.auraboot.framework.decision.rule;

/**
 * Target expression used by decision output mappings.
 */
public record RuleMappingTarget(
        Kind kind,
        String path
) {
    public enum Kind {
        ACTION_PARAM,
        FIELD,
        PROCESS_VARIABLE,
        SLA_FIELD,
        PERMISSION_CONTEXT
    }
}
