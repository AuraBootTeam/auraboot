package com.auraboot.framework.meta.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for {@link QueryOperator#supportsDataType(String)}.
 *
 * <p>Guards the case-mismatch fix (AGENTS §9): the switch value is {@code dataType.toUpperCase()},
 * so the case labels must be uppercase. Previously they were lowercase, making every type-specific
 * arm unreachable and the method always fall through to {@code default} ({@code "any".equals(valueType)}).
 */
class QueryOperatorTest {

    @Test
    @DisplayName("a string operator supports the 'string' data type (regression: previously fell to default)")
    void stringOperatorSupportsStringDataType() {
        QueryOperator like = new QueryOperator("like", ":col LIKE :val", "string", "");
        assertThat(like.supportsDataType("string")).isTrue();
    }

    @Test
    @DisplayName("a comparison operator supports the 'number' data type")
    void comparisonOperatorSupportsNumberDataType() {
        QueryOperator gt = new QueryOperator("gt", ":col > :val", "number", "");
        assertThat(gt.supportsDataType("number")).isTrue();
    }

    @Test
    @DisplayName("case-insensitive: uppercase dataType is matched too (switch value is toUpperCase)")
    void uppercaseDataTypeMatches() {
        QueryOperator like = new QueryOperator("like", ":col LIKE :val", "string", "");
        assertThat(like.supportsDataType("STRING")).isTrue();
    }

    @Test
    @DisplayName("'any' valueType short-circuits to supporting every data type")
    void anyValueTypeSupportsEverything() {
        QueryOperator in = new QueryOperator("in", ":col IN :val", "any", "");
        assertThat(in.supportsDataType("string")).isTrue();
        assertThat(in.supportsDataType("number")).isTrue();
    }

    @Test
    @DisplayName("null dataType is not supported for a non-'any' operator")
    void nullDataTypeNotSupported() {
        QueryOperator like = new QueryOperator("like", ":col LIKE :val", "string", "");
        assertThat(like.supportsDataType(null)).isFalse();
    }
}
