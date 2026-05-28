package com.auraboot.framework.chatbi.v2.dto;

/**
 * Comparison / set operators emitted by the LLM intent translator.
 *
 * <p>PRD 17 §3.1 KEYWORD list collapsed into structured form.
 * Maps 1:1 to {@code SemanticQueryRequest.Filter.op} string values
 * ({@code eq, ne, gt, gte, lt, lte, in, not_in, like, between}).
 */
public enum Operator {
    EQ("eq"),
    NE("ne"),
    GT("gt"),
    GTE("gte"),
    LT("lt"),
    LTE("lte"),
    IN("in"),
    NOT_IN("not_in"),
    LIKE("like"),
    BETWEEN("between");

    private final String wire;

    Operator(String wire) {
        this.wire = wire;
    }

    /** Wire format consumed by {@code SemanticQueryRequest.Filter#op}. */
    public String wire() {
        return wire;
    }

    /** Parses a wire string or canonical-form symbol; returns null for unknown. */
    public static Operator fromWire(String s) {
        if (s == null) {
            return null;
        }
        String n = s.trim().toLowerCase();
        switch (n) {
            case "=":
            case "==":
            case "eq":
                return EQ;
            case "!=":
            case "<>":
            case "ne":
                return NE;
            case ">":
            case "gt":
                return GT;
            case ">=":
            case "gte":
                return GTE;
            case "<":
            case "lt":
                return LT;
            case "<=":
            case "lte":
                return LTE;
            case "in":
                return IN;
            case "not_in":
            case "not-in":
            case "notin":
                return NOT_IN;
            case "like":
                return LIKE;
            case "between":
                return BETWEEN;
            default:
                return null;
        }
    }
}
