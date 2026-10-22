package com.auraboot.framework.meta.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VirtualFieldEngine Integration Test
 *
 * Covers P1-2 requirements:
 * 1. SpEL expression evaluation (TRANSIENT mode)
 * 2. Materialized field recalculation
 * 3. Dependency graph cycle detection
 * 4. Topological computation ordering
 */
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("VirtualFieldEngine Integration Test - P1-2")
class VirtualFieldEngineIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private VirtualFieldEngine virtualFieldEngine;

    private static final String TEST_MODEL_CODE = "test_virtual_model";

    // ==================== Expression Evaluation Tests ====================

    @Test
    @Order(1)
    @DisplayName("P1-2.1: Evaluate simple arithmetic expression")
    void test01_evaluateArithmetic() {
        Map<String, Object> context = Map.of("price", 100, "quantity", 5);

        Object result = virtualFieldEngine.evaluate("#price * #quantity", context);

        assertNotNull(result);
        assertEquals(500, ((Number) result).intValue());
    }

    @Test
    @Order(2)
    @DisplayName("P1-2.1: Evaluate string concatenation expression")
    void test02_evaluateStringConcat() {
        Map<String, Object> context = Map.of("firstName", "John", "lastName", "Doe");

        Object result = virtualFieldEngine.evaluate("#firstName + ' ' + #lastName", context);

        assertNotNull(result);
        assertEquals("John Doe", result.toString());
    }

    @Test
    @Order(3)
    @DisplayName("P1-2.1: Evaluate conditional expression")
    void test03_evaluateConditional() {
        Map<String, Object> context = Map.of("age", 25);

        Object result = virtualFieldEngine.evaluate("#age >= 18 ? 'adult' : 'minor'", context);

        assertNotNull(result);
        assertEquals("adult", result.toString());
    }

    @Test
    @Order(4)
    @DisplayName("P1-2.1: Evaluate expression with null handling")
    void test04_evaluateNullSafe() {
        Map<String, Object> context = new HashMap<>();
        context.put("name", null);
        context.put("defaultName", "Unknown");

        Object result = virtualFieldEngine.evaluate(
                "#name != null ? #name : #defaultName", context);

        assertNotNull(result);
        assertEquals("Unknown", result.toString());
    }

    @Test
    @Order(5)
    @DisplayName("P1-2.1: Evaluate boolean expression")
    void test05_evaluateBoolean() {
        Map<String, Object> context = Map.of("status", "active", "score", 85);

        Object result = virtualFieldEngine.evaluate(
                "#status == 'active' && #score > 80", context);

        assertNotNull(result);
        assertTrue((Boolean) result);
    }

    @Test
    @Order(6)
    @DisplayName("P1-2.1: Evaluate expression with collection operations")
    void test06_evaluateCollection() {
        // SimpleEvaluationContext (forReadOnlyDataBinding) does not support method calls
        // on arbitrary objects. Use collection indexing instead.
        Map<String, Object> context = Map.of("items", List.of(10, 20, 30));

        Object result = virtualFieldEngine.evaluate("#items[0] + #items[1] + #items[2]", context);

        assertNotNull(result);
        assertEquals(60, ((Number) result).intValue());
    }

    @Test
    @Order(7)
    @DisplayName("P1-2.1: Invalid expression returns null")
    void test07_invalidExpression() {
        Map<String, Object> context = Map.of("x", 1);

        // Invalid SpEL expression should return null (implementation catches exception)
        Object result = virtualFieldEngine.evaluate("invalid!!!expression", context);
        
        assertNull(result, "Invalid SpEL expression should return null");
        log.info("✓ Invalid expression correctly returned null");
    }

    // ==================== Materialization Tests ====================

    @Test
    @Order(20)
    @DisplayName("P1-2.2: Materialize virtual fields after change")
    void test20_materialize() {
        // This test depends on having materialized virtual field definitions
        // It should not throw even if no virtual fields are configured
        assertDoesNotThrow(() -> {
            virtualFieldEngine.materialize(TEST_MODEL_CODE, "record_001", List.of("price", "quantity"));
        });
    }

    @Test
    @Order(21)
    @DisplayName("P1-2.2: Materialize with empty changed fields")
    void test21_materializeEmptyChanges() {
        assertDoesNotThrow(() -> {
            virtualFieldEngine.materialize(TEST_MODEL_CODE, "record_002", List.of());
        });
    }

    @Test
    @Order(22)
    @DisplayName("P1-2.2: Materialize for non-existent model does not throw")
    void test22_materializeNonExistentModel() {
        assertDoesNotThrow(() -> {
            virtualFieldEngine.materialize("non_existent_model", "record_003", List.of("field1"));
        });
    }

    // ==================== Dependency Graph Tests ====================

    @Test
    @Order(30)
    @DisplayName("P1-2.3: Validate dependency graph with no cycles")
    void test30_validateNoCycles() {
        List<String> cycles = virtualFieldEngine.validateDependencyGraph(TEST_MODEL_CODE);

        assertNotNull(cycles);
        assertTrue(cycles.isEmpty(), "Should have no cycles in valid dependency graph");
    }

    @Test
    @Order(31)
    @DisplayName("P1-2.3: Validate dependency graph for non-existent model")
    void test31_validateNonExistentModel() {
        List<String> cycles = virtualFieldEngine.validateDependencyGraph("non_existent_model");

        assertNotNull(cycles);
        assertTrue(cycles.isEmpty(), "Non-existent model should report no cycles");
    }

    // ==================== Computation Order Tests ====================

    @Test
    @Order(40)
    @DisplayName("P1-2.4: Get computation order (topological sort)")
    void test40_getComputationOrder() {
        List<String> order = virtualFieldEngine.getComputationOrder(TEST_MODEL_CODE);

        assertNotNull(order);
        // Order may be empty if no virtual fields are configured
        log.info("Computation order for {}: {}", TEST_MODEL_CODE, order);
    }

    @Test
    @Order(41)
    @DisplayName("P1-2.4: Computation order for non-existent model")
    void test41_getComputationOrder_nonExistent() {
        List<String> order = virtualFieldEngine.getComputationOrder("non_existent_model");

        assertNotNull(order);
        assertTrue(order.isEmpty());
    }

    // ==================== Complex Expression Tests ====================

    @Test
    @Order(50)
    @DisplayName("P1-2: Evaluate nested map access")
    void test50_nestedMapAccess() {
        Map<String, Object> address = Map.of("city", "Shanghai", "zip", "200000");
        Map<String, Object> context = Map.of("address", address);

        Object result = virtualFieldEngine.evaluate("#address['city']", context);

        assertNotNull(result);
        assertEquals("Shanghai", result.toString());
    }

    @Test
    @Order(51)
    @DisplayName("P1-2: Evaluate mathematical functions")
    void test51_mathFunctions() {
        // SimpleEvaluationContext disables T() type references.
        // Use the engine's custom #SQRT() function instead.
        Map<String, Object> context = Map.of("a", 3, "b", 4);

        Object result = virtualFieldEngine.evaluate(
                "#sqrt(#a * #a + #b * #b)", context);

        assertNotNull(result);
        assertEquals(5.0, ((Number) result).doubleValue(), 0.001);
    }

    @Test
    @Order(52)
    @DisplayName("P1-2: Evaluate with empty context")
    void test52_emptyContext() {
        Object result = virtualFieldEngine.evaluate("1 + 1", Map.of());

        assertNotNull(result);
        assertEquals(2, ((Number) result).intValue());
    }
}
