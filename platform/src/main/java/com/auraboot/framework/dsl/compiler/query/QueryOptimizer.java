package com.auraboot.framework.dsl.compiler.query;

import com.auraboot.framework.dsl.compiler.DslCompiler;
import com.auraboot.framework.dsl.compiler.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/**
 * Compiles a complex DSL query definition (multi-table joins, aggregations)
 * into an optimized SQL execution plan with index and pagination hints.
 *
 * <p>Input config keys:
 * <ul>
 *   <li>{@code tables} — list of table names involved</li>
 *   <li>{@code joins} — list of join specifications ({@code leftTable, rightTable, on})</li>
 *   <li>{@code aggregations} — list of aggregation specs ({@code function, field, groupBy})</li>
 *   <li>{@code filters} — list of filter conditions</li>
 *   <li>{@code pageSize} — pagination size (default 50)</li>
 *   <li>{@code sortFields} — list of sort field names</li>
 * </ul>
 */
@Slf4j
@Component
public class QueryOptimizer implements DslCompiler {

    public static final String TYPE = "query";
    private static final int DEFAULT_PAGE_SIZE = 50;

    @Override
    public String supportedType() {
        return TYPE;
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompiledPlan compile(DslDefinition definition) {
        Objects.requireNonNull(definition, "definition must not be null");

        Map<String, Object> cfg = definition.getConfig() != null ? definition.getConfig() : Map.of();
        List<String> tables = toStringList(cfg.get("tables"));
        List<Map<String, Object>> joins = cfg.get("joins") instanceof List<?> l
                ? (List<Map<String, Object>>) l : List.of();
        List<Map<String, Object>> aggregations = cfg.get("aggregations") instanceof List<?> l
                ? (List<Map<String, Object>>) l : List.of();
        List<Map<String, Object>> filters = cfg.get("filters") instanceof List<?> l
                ? (List<Map<String, Object>>) l : List.of();
        int pageSize = toInt(cfg.get("pageSize"), DEFAULT_PAGE_SIZE);
        List<String> sortFields = toStringList(cfg.get("sortFields"));

        List<CompiledStep> steps = new ArrayList<>();
        int order = 0;

        // 1. Build index suggestions
        List<String> indexSuggestions = buildIndexSuggestions(tables, joins, filters, sortFields);
        steps.add(CompiledStep.builder()
                .name("analyze-indexes")
                .type(StepType.CACHE_LOOKUP)
                .order(order++)
                .parameters(Map.of("indexSuggestions", indexSuggestions))
                .costWeight(0.2)
                .build());

        // 2. Execute main query
        Map<String, Object> queryParams = new LinkedHashMap<>();
        queryParams.put("tables", tables);
        queryParams.put("joins", joins);
        queryParams.put("filters", filters);
        queryParams.put("pageSize", pageSize);
        queryParams.put("sortFields", sortFields);

        steps.add(CompiledStep.builder()
                .name("execute-optimized-query")
                .type(StepType.QUERY_EXECUTE)
                .order(order++)
                .parameters(queryParams)
                .costWeight(tables.size() * 2.0 + joins.size() * 1.5)
                .build());

        // 3. Apply aggregations if present
        if (!aggregations.isEmpty()) {
            steps.add(CompiledStep.builder()
                    .name("apply-aggregations")
                    .type(StepType.AGGREGATE)
                    .order(order++)
                    .parameters(Map.of("aggregations", aggregations))
                    .costWeight(aggregations.size() * 1.0)
                    .build());
        }

        // 4. Transform results
        steps.add(CompiledStep.builder()
                .name("transform-results")
                .type(StepType.TRANSFORM)
                .order(order)
                .parameters(Map.of("pageSize", pageSize))
                .costWeight(0.5)
                .build());

        Map<String, Object> hints = new LinkedHashMap<>();
        hints.put("tableCount", tables.size());
        hints.put("joinCount", joins.size());
        hints.put("filterCount", filters.size());
        hints.put("aggregationCount", aggregations.size());
        hints.put("indexSuggestions", indexSuggestions);
        hints.put("pageSize", pageSize);

        // Parallel if multiple independent tables; batch if many joins
        ExecutionStrategy strategy;
        if (joins.size() > 3) {
            strategy = ExecutionStrategy.BATCH;
        } else if (tables.size() > 2 && joins.isEmpty()) {
            strategy = ExecutionStrategy.PARALLEL;
        } else {
            strategy = ExecutionStrategy.SEQUENTIAL;
        }

        return CompiledPlan.builder()
                .planId("query-" + (definition.getModelCode() != null ? definition.getModelCode() : "adhoc")
                        + "-" + definition.getVersion())
                .compilerName(TYPE)
                .steps(steps)
                .optimizationHints(hints)
                .strategy(strategy)
                .compiledAt(Instant.now())
                .build();
    }

    // --- helpers ---

    /**
     * Generate index suggestions based on join columns, filter columns, and sort columns.
     */
    @SuppressWarnings("unchecked")
    private List<String> buildIndexSuggestions(
            List<String> tables,
            List<Map<String, Object>> joins,
            List<Map<String, Object>> filters,
            List<String> sortFields) {

        Set<String> suggestions = new LinkedHashSet<>();

        // Suggest indexes on join columns
        for (Map<String, Object> join : joins) {
            Object on = join.get("on");
            if (on instanceof String onStr) {
                suggestions.add("INDEX on join column: " + onStr);
            }
        }

        // Suggest indexes on filter columns
        for (Map<String, Object> filter : filters) {
            Object field = filter.get("field");
            if (field instanceof String fieldStr) {
                suggestions.add("INDEX on filter column: " + fieldStr);
            }
        }

        // Suggest indexes on sort columns
        for (String sortField : sortFields) {
            suggestions.add("INDEX on sort column: " + sortField);
        }

        return List.copyOf(suggestions);
    }

    @SuppressWarnings("unchecked")
    private List<String> toStringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .toList();
        }
        return List.of();
    }

    private int toInt(Object value, int defaultValue) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        return defaultValue;
    }
}
