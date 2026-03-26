package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.util.List;

/**
 * Represents the 'when' clause of a cross-field validation rule.
 * Supports declarative mode (field + operator), expression mode (expr),
 * and compound conditions (and, or, not).
 */
@Data
public class RuleCondition {

    // Declarative mode — field name to evaluate
    private String field;

    // Comparison operators (literal value or {"ref": "fieldCode"})
    private Object eq;
    private Object neq;
    private Object gt;
    private Object gte;
    private Object lt;
    private Object lte;
    private List<Object> in;
    private List<Object> notIn;

    // Expression mode (mutually exclusive with field)
    private String expr;

    // Compound conditions
    private List<RuleCondition> and;
    private List<RuleCondition> or;
    private RuleCondition not;
}
