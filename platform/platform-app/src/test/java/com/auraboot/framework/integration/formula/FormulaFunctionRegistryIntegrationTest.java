package com.auraboot.framework.integration.formula;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.formula.FormulaFunctionRegistry;
import com.auraboot.framework.meta.service.VirtualFieldEngine;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for FormulaFunctionRegistry and VirtualFieldEngine.
 * Tests that formula functions are properly registered and evaluable via SpEL.
 */
@Slf4j
@DisplayName("FormulaFunctionRegistry - Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FormulaFunctionRegistryIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private FormulaFunctionRegistry functionRegistry;

    @Autowired
    private VirtualFieldEngine virtualFieldEngine;

    // ==================== Registry Tests ====================

    @Test
    @Order(1)
    @DisplayName("All built-in functions are registered")
    void test01_allFunctionsRegistered() {
        List<FormulaFunctionRegistry.FunctionInfo> functions = functionRegistry.getAllFunctions();

        assertNotNull(functions);
        assertFalse(functions.isEmpty(), "Should have registered functions");

        // Verify key function categories exist
        List<String> names = functions.stream()
                .map(FormulaFunctionRegistry.FunctionInfo::name)
                .toList();

        // Text functions
        assertTrue(names.contains("concat"), "Should have CONCAT");
        assertTrue(names.contains("upper"), "Should have UPPER");
        assertTrue(names.contains("lower"), "Should have LOWER");
        assertTrue(names.contains("trim"), "Should have TRIM");
        assertTrue(names.contains("len"), "Should have LEN");

        // Math functions
        assertTrue(names.contains("round"), "Should have ROUND");
        assertTrue(names.contains("sum"), "Should have SUM");
        assertTrue(names.contains("avg"), "Should have AVG");
        assertTrue(names.contains("min"), "Should have MIN");
        assertTrue(names.contains("max"), "Should have MAX");

        // Date functions
        assertTrue(names.contains("now"), "Should have NOW");
        assertTrue(names.contains("today"), "Should have TODAY");
        assertTrue(names.contains("date_add"), "Should have DATE_ADD");
        assertTrue(names.contains("date_diff"), "Should have DATE_DIFF");

        // Logical functions
        assertTrue(names.contains("if"), "Should have if");
        assertTrue(names.contains("isnull"), "Should have ISNULL");
        assertTrue(names.contains("and"), "Should have AND");
        assertTrue(names.contains("or"), "Should have or");

        log.info("Registered {} functions total", functions.size());
    }

    @Test
    @Order(2)
    @DisplayName("Filter functions by category - text")
    void test02_filterByCategory() {
        List<FormulaFunctionRegistry.FunctionInfo> textFunctions =
                functionRegistry.getFunctionsByCategory("text");

        assertNotNull(textFunctions);
        assertFalse(textFunctions.isEmpty(), "Should have text functions");
        assertTrue(textFunctions.stream()
                .allMatch(f -> "text".equals(f.category())));

        log.info("Found {} text functions", textFunctions.size());
    }

    @Test
    @Order(3)
    @DisplayName("Filter functions by category - math")
    void test03_filterMathCategory() {
        List<FormulaFunctionRegistry.FunctionInfo> mathFunctions =
                functionRegistry.getFunctionsByCategory("math");

        assertNotNull(mathFunctions);
        assertFalse(mathFunctions.isEmpty(), "Should have math functions");
        assertTrue(mathFunctions.stream()
                .allMatch(f -> "math".equals(f.category())));
    }

    @Test
    @Order(4)
    @DisplayName("Filter functions by category - date")
    void test04_filterDateCategory() {
        List<FormulaFunctionRegistry.FunctionInfo> dateFunctions =
                functionRegistry.getFunctionsByCategory("date");

        assertNotNull(dateFunctions);
        assertFalse(dateFunctions.isEmpty(), "Should have date functions");
    }

    @Test
    @Order(5)
    @DisplayName("Filter non-existent category returns empty list")
    void test05_filterNonExistentCategory() {
        List<FormulaFunctionRegistry.FunctionInfo> result =
                functionRegistry.getFunctionsByCategory("nonexistent_category");

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @Order(6)
    @DisplayName("FunctionInfo contains expected metadata")
    void test06_functionInfoMetadata() {
        List<FormulaFunctionRegistry.FunctionInfo> all = functionRegistry.getAllFunctions();

        FormulaFunctionRegistry.FunctionInfo concat = all.stream()
                .filter(f -> "concat".equals(f.name()))
                .findFirst()
                .orElse(null);

        assertNotNull(concat, "CONCAT function should exist");
        assertEquals("concat", concat.name());
        assertNotNull(concat.description());
        assertFalse(concat.description().isEmpty());
        assertEquals("text", concat.category());
        assertNotNull(concat.example());
        assertNotNull(concat.parameterTypes());
    }

    // ==================== VirtualFieldEngine Expression Evaluation ====================

    @Test
    @Order(10)
    @DisplayName("Evaluate simple arithmetic expression")
    void test10_evaluateArithmetic() {
        Object result = virtualFieldEngine.evaluate("1 + 2 * 3", Map.of());
        assertNotNull(result);
        log.info("Arithmetic result: {}", result);
    }

    @Test
    @Order(11)
    @DisplayName("Evaluate expression with context variables")
    void test11_evaluateWithContext() {
        Map<String, Object> context = Map.of(
                "price", 100.0,
                "quantity", 5
        );

        Object result = virtualFieldEngine.evaluate(
                "#price * #quantity", context);

        assertNotNull(result);
        log.info("Context expression result: {}", result);
    }

    @Test
    @Order(12)
    @DisplayName("Evaluate string concatenation expression")
    void test12_evaluateStringExpression() {
        Map<String, Object> context = Map.of(
                "firstName", "John",
                "lastName", "Doe"
        );

        Object result = virtualFieldEngine.evaluate(
                "#firstName + ' ' + #lastName", context);

        assertNotNull(result);
        assertEquals("John Doe", result.toString());
    }

    @Test
    @Order(13)
    @DisplayName("Evaluate conditional expression")
    void test13_evaluateConditional() {
        Map<String, Object> context = Map.of("score", 85);

        Object result = virtualFieldEngine.evaluate(
                "#score >= 60 ? 'pass' : 'fail'", context);

        assertNotNull(result);
        assertEquals("pass", result.toString());
    }

    @Test
    @Order(14)
    @DisplayName("Invalid expression returns null")
    void test14_invalidExpression() {
        // VirtualFieldEngine catches SpEL parse exceptions and returns null
        Object result = virtualFieldEngine.evaluate("invalid $$$ expression", Map.of());
        assertNull(result, "Invalid SpEL expression should return null");
    }

    @Test
    @Order(15)
    @DisplayName("Empty expression with empty context")
    void test15_emptyExpression() {
        // Evaluating null or empty expression may throw or return null
        try {
            Object result = virtualFieldEngine.evaluate("", Map.of());
            // Either null or empty string is acceptable
            log.info("Empty expression result: {}", result);
        } catch (Exception e) {
            // Throwing is also acceptable
            log.info("Empty expression threw: {}", e.getMessage());
        }
    }
}
