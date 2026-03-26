package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.util.List;

/**
 * Represents the 'assert' clause of a cross-field validation rule.
 * Declarative mode: field + one or more operators.
 * Expression mode: expr string (mutually exclusive with field).
 * Multiple operators per assert are allowed (e.g., gte + lte for range).
 */
@Data
public class RuleAssert {

    // Declarative mode — target field
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

    // Constraint operators
    private Boolean required;
    private Integer maxLength;
    private Integer minLength;
    private String pattern;

    // Expression mode (mutually exclusive with field)
    private String expr;
}
