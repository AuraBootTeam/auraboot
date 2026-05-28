package com.auraboot.framework.chatbi.v2.dto;

/**
 * A standalone filter intent extracted from one or more {@link SearchToken}s.
 *
 * <p>The compiler primarily uses {@code SemanticQueryRequest.Filter} for wire
 * output; this DTO is a typed intermediate when the Lexer needs to defer
 * filter binding (e.g. a VALUE token whose target DIMENSION is still being
 * resolved). PRD 17 §3.2 example.
 */
public record Filter(
        String field,
        Operator operator,
        Object value) {
}
