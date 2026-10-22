package com.auraboot.framework.rag.eval;

import java.util.List;

/**
 * Versioned bundle of golden queries. Loaded from
 * {@code classpath:rag-eval/golden-queries.json}.
 */
public record GoldenQuerySet(
        String version,
        String description,
        List<GoldenQuery> queries
) {

    public GoldenQuerySet {
        queries = queries == null ? List.of() : List.copyOf(queries);
        description = description == null ? "" : description;
    }
}
