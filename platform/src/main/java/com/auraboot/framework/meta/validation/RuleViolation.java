package com.auraboot.framework.meta.validation;

/**
 * A single rule violation produced by CrossFieldRuleEngine.
 */
public record RuleViolation(
    String ruleId,
    String targetField,
    String message,
    String severity
) {}
