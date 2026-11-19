package com.auraboot.framework.plugin.extension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded request-scoped query. Field codes are resolved and authorized by the host. */
public record DataPageQuery(Map<String, Object> exactFilters,
                            Map<String, List<Object>> anyOfFilters,
                            List<Sort> sorts,
                            int page,
                            int size,
                            String keyword,
                            Map<String, TimeRange> timeRanges) {
    public static final int MAX_SIZE = 1_000;

    /** Preserve existing plugin callers; keyword search is opt-in and uses host searchable metadata. */
    public DataPageQuery(Map<String,Object> exactFilters,Map<String,List<Object>> anyOfFilters,
                         List<Sort> sorts,int page,int size) {
        this(exactFilters,anyOfFilters,sorts,page,size,"",Map.of());
    }

    /** Preserve the keyword-query binary contract used by installed plugins. */
    public DataPageQuery(Map<String,Object> exactFilters,Map<String,List<Object>> anyOfFilters,
                         List<Sort> sorts,int page,int size,String keyword) {
        this(exactFilters,anyOfFilters,sorts,page,size,keyword,Map.of());
    }

    public DataPageQuery {
        keyword = keyword == null ? "" : keyword.trim();
        if (page < 1 || size < 1 || size > MAX_SIZE
                || (long) (page - 1) * size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid bounded page");
        }
        Map<String, Object> exact = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(exactFilters, "exactFilters")));
        Map<String, List<Object>> candidates = new LinkedHashMap<>();
        Objects.requireNonNull(anyOfFilters, "anyOfFilters").forEach((field, values) -> {
            List<Object> copy = List.copyOf(values);
            if (copy.isEmpty() || copy.size() > MAX_SIZE || exact.containsKey(field)) {
                throw new IllegalArgumentException("Invalid candidate filter");
            }
            candidates.put(field, copy);
        });
        anyOfFilters = Map.copyOf(candidates);
        exactFilters = exact;
        timeRanges = Map.copyOf(Objects.requireNonNull(timeRanges,"timeRanges"));
        timeRanges.keySet().forEach(field -> {
            validateField(field);
            if (exact.containsKey(field) || candidates.containsKey(field)) throw new IllegalArgumentException("Overlapping time filter");
        });
        if (exactFilters.size() + anyOfFilters.size() + timeRanges.size() > 100) {
            throw new IllegalArgumentException("Too many query filters");
        }
        exactFilters.keySet().forEach(DataPageQuery::validateField);
        anyOfFilters.keySet().forEach(DataPageQuery::validateField);
        sorts = List.copyOf(Objects.requireNonNull(sorts, "sorts"));
        if (sorts.size() > 10 || sorts.stream().map(Sort::field).distinct().count() != sorts.size()) {
            throw new IllegalArgumentException("Invalid query ordering");
        }
    }

    private static void validateField(String field) {
        if (field == null || !field.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid query field code");
        }
    }

    /** The host adds a public PID tie-breaker when the caller has not provided one. */
    public record Sort(String field, boolean descending) {
        public Sort { validateField(field); }
    }

    /** A finite half-open instant interval; the host binds both values before database paging. */
    public record TimeRange(java.time.Instant fromInclusive, java.time.Instant untilExclusive) {
        public TimeRange {
            Objects.requireNonNull(fromInclusive,"fromInclusive");
            Objects.requireNonNull(untilExclusive,"untilExclusive");
            if (!fromInclusive.isBefore(untilExclusive)) throw new IllegalArgumentException("Invalid time range");
        }
    }
}
