package com.auraboot.framework.decision.ast;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * An operand in a Condition AST (docs/1.md §14.4): {@code path}, {@code literal} or
 * {@code functionCall}. Deserialized polymorphically by the {@code type} discriminator.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Operand.PathOperand.class, name = "path"),
        @JsonSubTypes.Type(value = Operand.LiteralOperand.class, name = "literal"),
        @JsonSubTypes.Type(value = Operand.FunctionCallOperand.class, name = "functionCall")
})
public sealed interface Operand
        permits Operand.PathOperand, Operand.LiteralOperand, Operand.FunctionCallOperand {

    /** Declared data type of the operand (may be null for inferred literals). */
    DataType dataType();

    /**
     * A reference into the DecisionContext, e.g. scope=record path=data.amount. The source
     * is always explicit so {@code amount} is never ambiguous (docs/1.md §14.2).
     */
    record PathOperand(Scope scope, String path, DataType dataType) implements Operand {}

    /** A constant value. literal must carry a dataType (docs/1.md §14.8). */
    record LiteralOperand(Object value, DataType dataType) implements Operand {}

    /**
     * A whitelisted, pure function call (docs/1.md §14.9). No bean/reflection/IO/db/random/
     * clock access — time comes from {@code context.time.now}.
     */
    record FunctionCallOperand(String name, List<Operand> args, DataType returnType) implements Operand {
        @Override
        public DataType dataType() {
            return returnType;
        }
    }
}
