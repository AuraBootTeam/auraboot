package com.auraboot.framework.meta.validation;

import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.exception.MetaServiceException;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Resolves execution order for computed fields using topological sort.
 * Supports explicit dependsOn declarations and auto-inference from SpEL expressions.
 */
public class ComputedFieldDependencyResolver {

    // Matches identifiers in SpEL expressions (field names)
    // Excludes: numbers, string literals, known function names
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\b");
    private static final Set<String> KNOWN_FUNCTIONS = Set.of(
        "len", "duration", "now", "abs", "min", "max", "sum", "avg", "count",
        "concat", "upper", "lower", "trim", "substring", "replace",
        "true", "false", "null", "and", "or", "not",
        "T", "new" // SpEL keywords to exclude
    );

    /**
     * Sort computed fields by dependency order (Kahn's algorithm).
     *
     * @param computedFields map of fieldCode → SpEL expression
     * @param fieldDefinitions model field definitions (for explicit dependsOn)
     * @return ordered list of entries, ready for sequential execution
     */
    public List<Map.Entry<String, String>> resolveExecutionOrder(
            Map<String, String> computedFields,
            List<FieldDefinition> fieldDefinitions) {

        if (computedFields.size() <= 1) {
            return new ArrayList<>(computedFields.entrySet());
        }

        // Build dependency lookup from field definitions
        Map<String, List<String>> explicitDeps = new HashMap<>();
        if (fieldDefinitions != null) {
            for (FieldDefinition fd : fieldDefinitions) {
                if (fd.getComputeDependencies() != null && !fd.getComputeDependencies().isEmpty()) {
                    explicitDeps.put(fd.getCode(), fd.getComputeDependencies());
                }
            }
        }

        Set<String> computedFieldCodes = computedFields.keySet();

        // Build adjacency: fieldCode → set of computed fields it depends on
        Map<String, Set<String>> dependencies = new HashMap<>();
        for (Map.Entry<String, String> entry : computedFields.entrySet()) {
            String fieldCode = entry.getKey();
            String expression = entry.getValue();

            Set<String> deps;
            if (explicitDeps.containsKey(fieldCode)) {
                // Explicit dependsOn takes precedence
                deps = new HashSet<>(explicitDeps.get(fieldCode));
            } else {
                // Auto-infer from expression
                deps = extractFieldReferencesFromExpression(expression);
            }

            // Only keep dependencies that are themselves computed fields
            deps.retainAll(computedFieldCodes);
            dependencies.put(fieldCode, deps);
        }

        // Kahn's algorithm — topological sort
        return topologicalSort(computedFields, dependencies);
    }

    private List<Map.Entry<String, String>> topologicalSort(
            Map<String, String> computedFields,
            Map<String, Set<String>> dependencies) {

        // Compute in-degree
        Map<String, Integer> inDegree = new HashMap<>();
        for (String field : computedFields.keySet()) {
            inDegree.put(field, 0);
        }
        for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
            for (String dep : entry.getValue()) {
                if (inDegree.containsKey(dep)) {
                    // dep is depended upon by entry.key — but in-degree is for entry.key
                }
            }
        }
        // Recalculate: in-degree of X = number of fields X depends on (that are computed)
        for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
            inDegree.put(entry.getKey(), entry.getValue().size());
        }

        // Start with fields that have no computed dependencies
        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        List<String> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            String field = queue.poll();
            sorted.add(field);

            // Reduce in-degree for fields that depend on this one
            for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
                if (entry.getValue().contains(field)) {
                    int newDegree = inDegree.get(entry.getKey()) - 1;
                    inDegree.put(entry.getKey(), newDegree);
                    if (newDegree == 0) {
                        queue.add(entry.getKey());
                    }
                }
            }
        }

        // Cycle detection
        if (sorted.size() != computedFields.size()) {
            Set<String> remaining = new HashSet<>(computedFields.keySet());
            remaining.removeAll(sorted);
            throw new MetaServiceException(
                "Circular dependency detected among computed fields: " + remaining);
        }

        // Convert back to entries in sorted order
        return sorted.stream()
            .map(f -> Map.entry(f, computedFields.get(f)))
            .collect(Collectors.toList());
    }

    /**
     * Extract field references from a SpEL expression string.
     * Uses regex to find identifiers, filters out known functions/keywords.
     */
    public static Set<String> extractFieldReferencesFromExpression(String expression) {
        if (expression == null || expression.isBlank()) {
            return Set.of();
        }

        Set<String> refs = new HashSet<>();
        Matcher matcher = IDENTIFIER_PATTERN.matcher(expression);
        while (matcher.find()) {
            String identifier = matcher.group(1);
            if (!KNOWN_FUNCTIONS.contains(identifier)) {
                refs.add(identifier);
            }
        }
        return refs;
    }
}
