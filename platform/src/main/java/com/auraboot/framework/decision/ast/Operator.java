package com.auraboot.framework.decision.ast;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Whitelisted comparison operators (docs/1.md §14.5). Anything outside this set is
 * rejected at validation; there is no free-form operator string.
 *
 * <p>{@code arity} classifies how the right-hand operand is used:
 * <ul>
 *   <li>{@link Arity#BINARY} — single right operand (EQ, GT, CONTAINS_TEXT…)</li>
 *   <li>{@link Arity#RANGE} — right operand is a 2-element list (BETWEEN)</li>
 *   <li>{@link Arity#SET} — right operand is a list (IN, NOT_IN)</li>
 *   <li>{@link Arity#UNARY} — no right operand (IS_NULL, IS_EMPTY, CHANGED…)</li>
 * </ul>
 */
public enum Operator {
    EQ("EQ", Arity.BINARY),
    NE("NE", Arity.BINARY),
    GT("GT", Arity.BINARY),
    GTE("GTE", Arity.BINARY),
    LT("LT", Arity.BINARY),
    LTE("LTE", Arity.BINARY),
    IN("IN", Arity.SET),
    NOT_IN("NOT_IN", Arity.SET),
    BETWEEN("BETWEEN", Arity.RANGE),
    CONTAINS_TEXT("CONTAINS_TEXT", Arity.BINARY),
    CONTAINS_ELEMENT("CONTAINS_ELEMENT", Arity.BINARY),
    STARTS_WITH("STARTS_WITH", Arity.BINARY),
    ENDS_WITH("ENDS_WITH", Arity.BINARY),
    IS_NULL("IS_NULL", Arity.UNARY),
    IS_NOT_NULL("IS_NOT_NULL", Arity.UNARY),
    IS_EMPTY("IS_EMPTY", Arity.UNARY),
    IS_NOT_EMPTY("IS_NOT_EMPTY", Arity.UNARY),
    CHANGED("CHANGED", Arity.UNARY),
    /** Regex match — disabled by default, gated behind explicit enablement + length limit. */
    MATCHES("MATCHES", Arity.BINARY);

    public enum Arity { UNARY, BINARY, RANGE, SET }

    private final String code;
    private final Arity arity;

    Operator(String code, Arity arity) {
        this.code = code;
        this.arity = arity;
    }

    @JsonValue
    public String code() {
        return code;
    }

    public Arity arity() {
        return arity;
    }

    @JsonCreator
    public static Operator fromCode(String code) {
        for (Operator o : values()) {
            if (o.code.equalsIgnoreCase(code)) {
                return o;
            }
        }
        throw new IllegalArgumentException("Unknown decision operator: " + code);
    }
}
