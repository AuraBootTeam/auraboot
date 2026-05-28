package com.auraboot.framework.chatbi.v2.dto;

/**
 * ChatBI v2 Token taxonomy. PRD 17 §3.1.
 *
 * <p>The 10 categories cover the full surface that {@code intent-translator}
 * LLM prompt may emit. Each Token category maps to a specific slot in
 * {@code SemanticQueryRequest} via {@link com.auraboot.framework.chatbi.v2.compiler.TokenCompiler}.
 *
 * <p>Stability: {@code METRIC}, {@code DIMENSION}, {@code OPERATOR}, {@code VALUE},
 * {@code KEYWORD}, {@code AGGREGATION}, {@code DATE_BUCKET}, {@code TIME_RANGE},
 * {@code TOP_N} are first-class. {@code COLUMN} is fallback for legacy direct
 * MetaField references and discouraged in v0.1.
 */
public enum TokenType {
    METRIC,
    DIMENSION,
    OPERATOR,
    VALUE,
    KEYWORD,
    AGGREGATION,
    DATE_BUCKET,
    TIME_RANGE,
    TOP_N,
    COLUMN
}
