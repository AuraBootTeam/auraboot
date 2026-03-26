package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.DocumentFlowService;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SpEL expression support in resolveFieldMapping().
 * Verifies arithmetic and simple expressions inside ${...} field mappings.
 */
@ExtendWith(MockitoExtension.class)
class CommandSideEffectSpelTest {

    @Mock
    private DynamicDataMapper dynamicDataMapper;
    @Mock
    private DynamicDataService dynamicDataService;
    @Mock
    private MetaModelService metaModelService;

    // Use real SpEL evaluator — this is the integration we're testing
    private final CommandSpelEvaluator spelEvaluator = new CommandSpelEvaluator();
    @Mock
    private DocumentFlowService documentFlowService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private CommandSideEffectExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new CommandSideEffectExecutor(
                dynamicDataMapper, dynamicDataService, metaModelService, spelEvaluator, documentFlowService, objectMapper
        );
    }

    @Test
    @DisplayName("Simple field reference ${fieldName} resolves from current record")
    void simpleFieldReference() {
        Map<String, Object> mapping = Map.of("target_amount", "${cc_contract_amount}");
        Map<String, Object> record = Map.of("cc_contract_amount", 1000000);

        Map<String, Object> result = executor.resolveFieldMapping(mapping, record);

        assertEquals(1000000, result.get("target_amount"));
    }

    @Test
    @DisplayName("${recordId} resolves to current record id")
    void recordIdReference() {
        Map<String, Object> mapping = Map.of("ref_id", "${recordId}");
        Map<String, Object> record = Map.of("id", "abc-123");

        Map<String, Object> result = executor.resolveFieldMapping(mapping, record);

        assertEquals("abc-123", result.get("ref_id"));
    }

    @Test
    @DisplayName("Arithmetic expression ${a + b} evaluates correctly")
    void arithmeticAddition() {
        Map<String, Object> mapping = Map.of(
                "total", "${cc_contract_amount + cc_change_amount}"
        );
        Map<String, Object> record = Map.of(
                "cc_contract_amount", 1000000,
                "cc_change_amount", 200000
        );

        Map<String, Object> result = executor.resolveFieldMapping(mapping, record);

        assertEquals(1200000, result.get("total"));
    }

    @Test
    @DisplayName("Arithmetic expression ${a - b} evaluates subtraction")
    void arithmeticSubtraction() {
        Map<String, Object> mapping = Map.of(
                "remaining", "${cc_contract_amount - cc_paid_amount}"
        );
        Map<String, Object> record = Map.of(
                "cc_contract_amount", 1000000,
                "cc_paid_amount", 300000
        );

        Map<String, Object> result = executor.resolveFieldMapping(mapping, record);

        assertEquals(700000, result.get("remaining"));
    }

    @Test
    @DisplayName("Arithmetic expression ${a * b} evaluates multiplication")
    void arithmeticMultiplication() {
        Map<String, Object> mapping = Map.of(
                "line_total", "${quantity * unit_price}"
        );
        Map<String, Object> record = Map.of(
                "quantity", 50,
                "unit_price", 200
        );

        Map<String, Object> result = executor.resolveFieldMapping(mapping, record);

        assertEquals(10000, result.get("line_total"));
    }

    @Test
    @DisplayName("Mixed field mapping: plain + reference + expression")
    void mixedFieldMapping() {
        Map<String, Object> mapping = new HashMap<>();
        mapping.put("status", "approved");
        mapping.put("ref_id", "${recordId}");
        mapping.put("total", "${amount + tax}");

        Map<String, Object> record = Map.of(
                "id", "rec-001",
                "amount", 10000,
                "tax", 1300
        );

        Map<String, Object> result = executor.resolveFieldMapping(mapping, record);

        assertEquals("approved", result.get("status"));
        assertEquals("rec-001", result.get("ref_id"));
        assertEquals(11300, result.get("total"));
    }

    @Test
    @DisplayName("Legacy $current.field format still works")
    void legacyCurrentFormat() {
        Map<String, Object> mapping = Map.of("target", "$current.some_field");
        Map<String, Object> record = Map.of("some_field", "value123");

        Map<String, Object> result = executor.resolveFieldMapping(mapping, record);

        assertEquals("value123", result.get("target"));
    }

    @Test
    @DisplayName("Null current record returns null for expressions")
    void nullCurrentRecord() {
        Map<String, Object> mapping = Map.of(
                "total", "${a + b}",
                "ref", "${recordId}"
        );

        Map<String, Object> result = executor.resolveFieldMapping(mapping, null);

        assertNull(result.get("total"));
        assertNull(result.get("ref"));
    }

    @Test
    @DisplayName("Invalid SpEL expression returns null gracefully")
    void invalidExpression() {
        Map<String, Object> mapping = Map.of(
                "bad", "${+++invalid}"
        );
        Map<String, Object> record = Map.of("x", 1);

        Map<String, Object> result = executor.resolveFieldMapping(mapping, record);

        assertNull(result.get("bad"));
    }

    @Test
    @DisplayName("Ternary expression evaluates correctly")
    void ternaryExpression() {
        Map<String, Object> mapping = Map.of(
                "bonus", "${amount > 5000 ? 500 : 100}"
        );
        Map<String, Object> record = Map.of("amount", 10000);

        Map<String, Object> result = executor.resolveFieldMapping(mapping, record);

        assertEquals(500, result.get("bonus"));
    }
}
