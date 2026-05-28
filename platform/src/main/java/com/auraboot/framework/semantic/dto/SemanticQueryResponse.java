package com.auraboot.framework.semantic.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Response of {@code POST /api/semantic/query}.
 *
 * <p>Mirrors PRD 16 §6.1 schema. Includes rows + diagnostic metadata
 * (sqlFingerprint, durationMs, cacheHit) for observability.
 */
@Data
@NoArgsConstructor
public class SemanticQueryResponse {

    private String queryId;

    private List<Map<String, Object>> rows = new ArrayList<>();

    private int rowcount;

    private long durationMs;

    private boolean cacheHit;

    private String sqlFingerprint;

    private Set<String> referencedColumns;

    /** For {@code /sql} debug endpoint: SQL text (may be null on {@code /query}). */
    private String sql;

    /** For {@code /sql} debug endpoint: positional params (may be null on {@code /query}). */
    private List<Object> params;

    private List<String> warnings = new ArrayList<>();
}
