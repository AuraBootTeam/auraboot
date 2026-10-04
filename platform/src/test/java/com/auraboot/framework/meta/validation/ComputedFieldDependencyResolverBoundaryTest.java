package com.auraboot.framework.meta.validation;

import com.auraboot.framework.meta.dto.FieldDefinition;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ComputedFieldDependencyResolverBoundaryTest {
    private final ComputedFieldDependencyResolver resolver = new ComputedFieldDependencyResolver();

    @Test
    void emptyAndSingleFieldInputsNeedNoDependencyGraph() {
        assertEquals(List.of(), resolver.resolveExecutionOrder(Map.of(), null));
        assertEquals(List.of(Map.entry("total", "amount")), resolver.resolveExecutionOrder(Map.of("total", "amount"), null));
    }

    @Test
    void blankExpressionsHaveNoReferencesAndKeywordsAreExcluded() {
        assertEquals(Set.of(), ComputedFieldDependencyResolver.extractFieldReferencesFromExpression(null));
        assertEquals(Set.of(), ComputedFieldDependencyResolver.extractFieldReferencesFromExpression("  "));
        assertEquals(Set.of("amount", "tax"), ComputedFieldDependencyResolver.extractFieldReferencesFromExpression(
            "max(amount, tax) + abs(amount) and true or false or null"));
    }

    @Test
    void inferenceWorksWithoutDefinitionsAndDoesNotMutateInput() {
        var fields = new LinkedHashMap<String, String>();
        fields.put("total", "amount * 2");
        fields.put("amount", "10");
        var before = new LinkedHashMap<>(fields);
        assertEquals(List.of(Map.entry("amount", "10"), Map.entry("total", "amount * 2")),
            resolver.resolveExecutionOrder(fields, null));
        assertEquals(before, fields);
    }

    @Test
    void emptyExplicitDependenciesAllowInferenceAndExternalReferencesAreIgnored() {
        var definitions = List.of(FieldDefinition.builder().code("total").computeDependencies(List.of()).build());
        assertEquals(List.of("amount", "total"), resolver.resolveExecutionOrder(
            Map.of("amount", "10", "total", "amount + externalTax"), definitions).stream().map(Map.Entry::getKey).toList());
    }
}
