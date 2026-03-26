package com.auraboot.framework.meta.validation;

import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.exception.MetaServiceException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ComputedFieldDependencyResolverTest {

    private final ComputedFieldDependencyResolver resolver = new ComputedFieldDependencyResolver();

    @Test
    void linearChain_sortsCorrectly() {
        // C depends on B, B depends on A → execution order: A, B, C
        var fields = Map.of(
            "C", "B + 1",
            "A", "10",
            "B", "A * 2"
        );
        var fieldDefs = List.of(
            fieldDef("C", List.of("B")),
            fieldDef("A", List.of()),
            fieldDef("B", List.of("A"))
        );
        var result = resolver.resolveExecutionOrder(fields, fieldDefs);
        assertEquals(List.of("A", "B", "C"), result.stream().map(Map.Entry::getKey).toList());
    }

    @Test
    void noDependencies_preservesAll() {
        var fields = new LinkedHashMap<String, String>();
        fields.put("X", "10");
        fields.put("Y", "20");
        var result = resolver.resolveExecutionOrder(fields, List.of());
        assertEquals(2, result.size());
    }

    @Test
    void diamondDependency_sortsCorrectly() {
        // D depends on B,C; B depends on A; C depends on A → A first, then B,C, then D
        var fields = Map.of(
            "D", "B + C",
            "B", "A * 2",
            "C", "A * 3",
            "A", "10"
        );
        var fieldDefs = List.of(
            fieldDef("D", List.of("B", "C")),
            fieldDef("B", List.of("A")),
            fieldDef("C", List.of("A")),
            fieldDef("A", List.of())
        );
        var result = resolver.resolveExecutionOrder(fields, fieldDefs);
        var order = result.stream().map(Map.Entry::getKey).toList();
        // A must come before B and C; B and C must come before D
        assertTrue(order.indexOf("A") < order.indexOf("B"));
        assertTrue(order.indexOf("A") < order.indexOf("C"));
        assertTrue(order.indexOf("B") < order.indexOf("D"));
        assertTrue(order.indexOf("C") < order.indexOf("D"));
    }

    @Test
    void circularDependency_throwsException() {
        var fields = Map.of(
            "A", "B + 1",
            "B", "A + 1"
        );
        var fieldDefs = List.of(
            fieldDef("A", List.of("B")),
            fieldDef("B", List.of("A"))
        );
        var ex = assertThrows(MetaServiceException.class,
            () -> resolver.resolveExecutionOrder(fields, fieldDefs));
        assertTrue(ex.getMessage().contains("Circular dependency"));
    }

    @Test
    void explicitDependsOn_takesPrecedence() {
        // Expression mentions "tax" but dependsOn says ["base"]
        var fields = Map.of(
            "total", "base + tax",
            "base", "100"
        );
        var fieldDefs = List.of(
            fieldDef("total", List.of("base")), // explicit: only base
            fieldDef("base", List.of())
        );
        var result = resolver.resolveExecutionOrder(fields, fieldDefs);
        var order = result.stream().map(Map.Entry::getKey).toList();
        assertTrue(order.indexOf("base") < order.indexOf("total"));
    }

    @Test
    void autoInference_extractsFieldReferences() {
        var refs = ComputedFieldDependencyResolver.extractFieldReferencesFromExpression(
            "unitPrice * quantity + tax");
        assertTrue(refs.contains("unitPrice"));
        assertTrue(refs.contains("quantity"));
        assertTrue(refs.contains("tax"));
    }

    @Test
    void autoInference_ignoresNumbers() {
        var refs = ComputedFieldDependencyResolver.extractFieldReferencesFromExpression(
            "amount * 1.5 + 100");
        assertTrue(refs.contains("amount"));
        assertFalse(refs.contains("1"));
        assertFalse(refs.contains("100"));
    }

    // --- Helper ---

    private FieldDefinition fieldDef(String code, List<String> deps) {
        return FieldDefinition.builder()
            .code(code)
            .computeDependencies(deps.isEmpty() ? null : deps)
            .build();
    }
}
