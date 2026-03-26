package com.auraboot.module.finance.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VoucherTemplateResolverTest {

    private VoucherTemplateResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new VoucherTemplateResolver();
    }

    @Test
    void testResolveSimpleFieldRef() {
        Map<String, Object> payload = Map.of("pe_wo_total_amount", new BigDecimal("1000.00"));
        BigDecimal result = resolver.resolveAmount("#payload['pe_wo_total_amount']", payload);
        assertEquals(new BigDecimal("1000.0000"), result);
    }

    @Test
    void testResolveNumericField() {
        Map<String, Object> payload = Map.of("amount", 500.50);
        BigDecimal result = resolver.resolveAmount("#payload['amount']", payload);
        assertEquals(0, new BigDecimal("500.5000").compareTo(result));
    }

    @Test
    void testResolveWithMath() {
        Map<String, Object> payload = Map.of("qty", 10, "price", new BigDecimal("25.50"));
        BigDecimal result = resolver.resolveAmount("#payload['qty'] * #payload['price']", payload);
        assertEquals(0, new BigDecimal("255.0000").compareTo(result));
    }

    @Test
    void testConditionTrue() {
        Map<String, Object> payload = Map.of("pe_wo_type", "sales_out");
        assertTrue(resolver.evaluateCondition("#payload['pe_wo_type'] == 'sales_out'", payload));
    }

    @Test
    void testConditionFalse() {
        Map<String, Object> payload = Map.of("pe_wo_type", "sales_out");
        assertFalse(resolver.evaluateCondition("#payload['pe_wo_type'] == 'purchase_in'", payload));
    }

    @Test
    void testNullSafetyAmount() {
        Map<String, Object> payload = Map.of("other_field", "value");
        BigDecimal result = resolver.resolveAmount("#payload['missing_field']", payload);
        assertEquals(BigDecimal.ZERO, result);
    }

    @Test
    void testNullAmountExpr() {
        assertEquals(BigDecimal.ZERO, resolver.resolveAmount(null, Map.of()));
    }

    @Test
    void testBlankAmountExpr() {
        assertEquals(BigDecimal.ZERO, resolver.resolveAmount("  ", Map.of()));
    }

    @Test
    void testNullConditionExpr() {
        assertFalse(resolver.evaluateCondition(null, Map.of()));
    }

    @Test
    void testNullPayload() {
        assertEquals(BigDecimal.ZERO, resolver.resolveAmount("#payload['x']", null));
        assertFalse(resolver.evaluateCondition("#payload['x'] == 'y'", null));
        assertEquals("", resolver.resolveString("'hello'", null));
    }

    @Test
    void testResolveStringExpression() {
        Map<String, Object> payload = Map.of("code", "SO-001");
        String result = resolver.resolveString("'Sales delivery: ' + #payload['code']", payload);
        assertEquals("Sales delivery: SO-001", result);
    }

    @Test
    void testResolveStringNull() {
        assertEquals("", resolver.resolveString(null, Map.of()));
        assertEquals("", resolver.resolveString("", Map.of()));
    }

    @Test
    void testDecimalPrecision() {
        Map<String, Object> payload = Map.of("amount", new BigDecimal("100.123456789"));
        BigDecimal result = resolver.resolveAmount("#payload['amount']", payload);
        assertEquals(4, result.scale());
        assertEquals(new BigDecimal("100.1235"), result);
    }

    @Test
    void testSpelSecurityBlocksTypeReferences() {
        // T(java.lang.Runtime) should be blocked to prevent RCE
        Map<String, Object> payload = Map.of("x", "1");
        BigDecimal result = resolver.resolveAmount(
                "T(java.lang.Runtime).getRuntime().exec('whoami')", payload);
        assertEquals(BigDecimal.ZERO, result, "Type references should be blocked, returning ZERO");
    }

    @Test
    void testSpelSecurityBlocksStringClass() {
        Map<String, Object> payload = Map.of("x", "1");
        String result = resolver.resolveString("T(java.lang.String).valueOf(42)", payload);
        assertEquals("", result, "Type references should be blocked in string expressions too");
    }

    @Test
    void testSpelSecurityBlocksConditionTypeRef() {
        Map<String, Object> payload = Map.of("x", "1");
        boolean result = resolver.evaluateCondition(
                "T(java.lang.System).getenv('path') != null", payload);
        assertFalse(result, "Type references should be blocked in condition expressions");
    }
}
