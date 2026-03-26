package com.auraboot.module.finance.engine;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.TenantClock;
import com.auraboot.framework.meta.dto.DynamicBatchResponse;
import com.auraboot.framework.meta.service.DynamicDataService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Edge case tests for VoucherTemplateResolver and VoucherGenerationService.
 * Complements the existing unit tests with scenarios for:
 * - Complex SpEL expressions (nested maps, list indexing)
 * - Decimal precision boundary cases (HALF_UP rounding at 4dp)
 * - Null payload fields during generation
 * - Voucher code uniqueness
 * - Large payloads
 * - String concatenation expressions
 */
@ExtendWith(MockitoExtension.class)
class VoucherEngineEdgeCaseTest {

    @Mock
    private DynamicDataService dynamicDataService;

    private VoucherTemplateResolver resolver;
    private VoucherGenerationService service;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(1L, 1L, "test-user-pid", "test-user");
        resolver = new VoucherTemplateResolver();
        TenantClock tenantClock = mock(TenantClock.class);
        lenient().when(tenantClock.businessDate(any())).thenReturn(LocalDate.now());
        service = new VoucherGenerationService(resolver, dynamicDataService, tenantClock);
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    // ========================================================================
    // 1. Complex SpEL — nested map access with list indexing + math
    // ========================================================================

    @Test
    void testTemplateWithComplexSpEL_nestedMapListAccess() {
        // Payload contains a nested list of maps (like order lines)
        Map<String, Object> line0 = new HashMap<>();
        line0.put("qty", 5);
        line0.put("price", new BigDecimal("20.50"));

        Map<String, Object> line1 = new HashMap<>();
        line1.put("qty", 3);
        line1.put("price", new BigDecimal("10.00"));

        Map<String, Object> payload = new HashMap<>();
        payload.put("lines", List.of(line0, line1));

        // Expression: #payload['lines'][0]['qty'] * #payload['lines'][0]['price']
        BigDecimal result = resolver.resolveAmount(
                "#payload['lines'][0]['qty'] * #payload['lines'][0]['price']", payload);

        // 5 * 20.50 = 102.50 -> scale 4 -> 102.5000
        assertEquals(0, new BigDecimal("102.5000").compareTo(result));
    }

    @Test
    void testTemplateWithComplexSpEL_secondLineAccess() {
        Map<String, Object> line0 = Map.of("qty", 5, "price", new BigDecimal("20.50"));
        Map<String, Object> line1 = Map.of("qty", 3, "price", new BigDecimal("10.00"));

        Map<String, Object> payload = new HashMap<>();
        payload.put("lines", List.of(line0, line1));

        // Access second element
        BigDecimal result = resolver.resolveAmount(
                "#payload['lines'][1]['qty'] * #payload['lines'][1]['price']", payload);

        // 3 * 10.00 = 30.00 -> 30.0000
        assertEquals(0, new BigDecimal("30.0000").compareTo(result));
    }

    @Test
    void testTemplateWithComplexSpEL_sumOfLines() {
        Map<String, Object> line0 = Map.of("qty", 5, "price", new BigDecimal("20.50"));
        Map<String, Object> line1 = Map.of("qty", 3, "price", new BigDecimal("10.00"));

        Map<String, Object> payload = new HashMap<>();
        payload.put("lines", List.of(line0, line1));

        // Sum two line totals
        BigDecimal result = resolver.resolveAmount(
                "#payload['lines'][0]['qty'] * #payload['lines'][0]['price'] "
                        + "+ #payload['lines'][1]['qty'] * #payload['lines'][1]['price']", payload);

        // (5 * 20.50) + (3 * 10.00) = 102.50 + 30.00 = 132.50
        assertEquals(0, new BigDecimal("132.5000").compareTo(result));
    }

    // ========================================================================
    // 2. Decimal precision — HALF_UP rounding boundary at 4 decimal places
    // ========================================================================

    @Test
    void testDecimalPrecision_roundDownBelow5() {
        // 99.99994 -> HALF_UP at 4dp -> 99.9999 (4th decimal stays)
        Map<String, Object> payload = Map.of("amount", new BigDecimal("99.99994"));
        BigDecimal result = resolver.resolveAmount("#payload['amount']", payload);
        assertEquals(new BigDecimal("99.9999"), result);
    }

    @Test
    void testDecimalPrecision_exactlyHalf() {
        // 50.00005 -> HALF_UP at 4dp -> 50.0001
        Map<String, Object> payload = Map.of("amount", new BigDecimal("50.00005"));
        BigDecimal result = resolver.resolveAmount("#payload['amount']", payload);
        assertEquals(new BigDecimal("50.0001"), result);
    }

    @Test
    void testDecimalPrecision_manyDecimalPlaces() {
        // 7 decimal places: 123.4567891 -> 123.4568
        Map<String, Object> payload = Map.of("amount", new BigDecimal("123.4567891"));
        BigDecimal result = resolver.resolveAmount("#payload['amount']", payload);
        assertEquals(new BigDecimal("123.4568"), result);
    }

    @Test
    void testDecimalPrecision_multiplicationResult() {
        // qty=7, price=3.3333 -> 7 * 3.3333 = 23.3331
        Map<String, Object> payload = new HashMap<>();
        payload.put("qty", 7);
        payload.put("price", new BigDecimal("3.3333"));

        BigDecimal result = resolver.resolveAmount(
                "#payload['qty'] * #payload['price']", payload);

        assertEquals(4, result.scale());
        assertEquals(0, new BigDecimal("23.3331").compareTo(result));
    }

    // ========================================================================
    // 3. Null payload fields in VoucherGenerationService
    // ========================================================================

    @Test
    void testNullPayloadFieldsInGeneration_resolveToZeroAndSkipLines() {
        // Template has 2 lines, but payload has null values -> amount resolves to 0 -> lines skipped
        Map<String, Object> template = Map.of("pid", "tpl-null", "fac_vt_name", "Null fields test");
        List<Map<String, Object>> templateLines = List.of(
                createTemplateLine("1001", "debit", "#payload['amount']", null),
                createTemplateLine("6001", "credit", "#payload['amount']", null)
        );

        // Payload where 'amount' is explicitly null
        Map<String, Object> payload = new HashMap<>();
        payload.put("amount", null);
        payload.put("other", "irrelevant");

        // Both lines resolve to 0 -> all skipped -> returns null
        String result = service.generateVoucher(template, templateLines, payload,
                "test", "1", "T-NULL-001");

        assertNull(result, "Should return null when all lines resolve to zero due to null fields");
        verify(dynamicDataService, never()).create(any(), any());
    }

    @Test
    void testNullPayloadFieldsInGeneration_mixedNullAndValid() {
        // One line has valid amount, other references null -> unbalanced should throw
        Map<String, Object> template = Map.of("pid", "tpl-mix", "fac_vt_name", "Mixed null");
        List<Map<String, Object>> templateLines = List.of(
                createTemplateLine("1001", "debit", "#payload['amount']", null),
                createTemplateLine("6001", "credit", "#payload['missing']", null)
        );

        Map<String, Object> payload = new HashMap<>();
        payload.put("amount", new BigDecimal("500.00"));
        // 'missing' not in payload -> resolves to 0 -> credit line skipped
        // Only debit line remains -> debit=500, credit=0 -> unbalanced

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                service.generateVoucher(template, templateLines, payload, "test", "1", "T-MIX-001"));
        assertTrue(ex.getMessage().contains("not balanced"));
    }

    @Test
    void testNullPayloadFieldsInGeneration_nullDescriptionExpr() {
        // Description expression references a null field -> should handle gracefully
        Map<String, Object> template = Map.of("pid", "tpl-desc", "fac_vt_name", "Desc null");
        List<Map<String, Object>> templateLines = List.of(
                createTemplateLine("1001", "debit", "#payload['amount']",
                        "'Ref: ' + #payload['ref_code']"),
                createTemplateLine("6001", "credit", "#payload['amount']", null)
        );

        Map<String, Object> payload = new HashMap<>();
        payload.put("amount", new BigDecimal("200.00"));
        payload.put("ref_code", null);

        when(dynamicDataService.create(eq("fac_journal_entry"), any()))
                .thenReturn(Map.of("pid", "je-desc"));
        when(dynamicDataService.batchCreate(eq("fac_journal_entry_line"), any()))
                .thenReturn(new DynamicBatchResponse());

        String jeId = service.generateVoucher(template, templateLines, payload,
                "test", "1", "T-DESC-001");

        assertEquals("je-desc", jeId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> linesCaptor = ArgumentCaptor.forClass(List.class);
        verify(dynamicDataService).batchCreate(eq("fac_journal_entry_line"), linesCaptor.capture());
        List<Map<String, Object>> lines = linesCaptor.getValue();

        // Description with null concat either resolves to "Ref: null" or "" (on exception)
        String desc = (String) lines.get(0).get("fac_jel_description");
        assertNotNull(desc, "Description should not be Java null");
    }

    // ========================================================================
    // 4. Voucher code uniqueness — 2 codes same day differ in UUID segment
    // ========================================================================

    @Test
    void testVoucherCodeUniquePerDay() {
        String code1 = service.generateJournalEntryCode();
        String code2 = service.generateJournalEntryCode();

        String todayPrefix = "JE-" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-";

        // Both start with same date prefix
        assertTrue(code1.startsWith(todayPrefix),
                "Code1 should start with today's date prefix: " + code1);
        assertTrue(code2.startsWith(todayPrefix),
                "Code2 should start with today's date prefix: " + code2);

        // But differ in UUID segment (extremely unlikely to collide)
        assertNotEquals(code1, code2,
                "Two generated codes should differ in UUID segment");

        // Verify format: JE-yyyyMMdd-XXXX (16 chars total)
        assertEquals(16, code1.length());
        assertEquals(16, code2.length());

        // UUID segment is uppercase hex
        String uuid1 = code1.substring(todayPrefix.length());
        String uuid2 = code2.substring(todayPrefix.length());
        assertTrue(uuid1.matches("[0-9A-F]{4}"), "UUID segment should be 4 uppercase hex chars: " + uuid1);
        assertTrue(uuid2.matches("[0-9A-F]{4}"), "UUID segment should be 4 uppercase hex chars: " + uuid2);
    }

    // ========================================================================
    // 5. Large payload — 50+ fields all resolved correctly
    // ========================================================================

    @Test
    void testLargePayloadResolution_50Fields() {
        Map<String, Object> payload = new HashMap<>();
        for (int i = 0; i < 50; i++) {
            payload.put("field_" + i, new BigDecimal(String.valueOf(i + 1)));
        }

        // Resolve the first field
        BigDecimal first = resolver.resolveAmount("#payload['field_0']", payload);
        assertEquals(0, new BigDecimal("1.0000").compareTo(first));

        // Resolve the last field
        BigDecimal last = resolver.resolveAmount("#payload['field_49']", payload);
        assertEquals(0, new BigDecimal("50.0000").compareTo(last));

        // Resolve a math expression across fields
        BigDecimal sum = resolver.resolveAmount(
                "#payload['field_0'] + #payload['field_49']", payload);
        assertEquals(0, new BigDecimal("51.0000").compareTo(sum));
    }

    @Test
    void testLargePayloadResolution_stringFields() {
        Map<String, Object> payload = new HashMap<>();
        for (int i = 0; i < 50; i++) {
            payload.put("name_" + i, "Item-" + i);
        }

        String result = resolver.resolveString(
                "#payload['name_0'] + ' and ' + #payload['name_49']", payload);
        assertEquals("Item-0 and Item-49", result);
    }

    @Test
    void testLargePayloadInGeneration() {
        // Build a payload with 50+ fields, but only 'amount' is used by template
        Map<String, Object> payload = new HashMap<>();
        payload.put("amount", new BigDecimal("1500.00"));
        for (int i = 0; i < 55; i++) {
            payload.put("extra_field_" + i, "value_" + i);
        }

        Map<String, Object> template = Map.of("pid", "tpl-large", "fac_vt_name", "Large Payload");
        List<Map<String, Object>> templateLines = List.of(
                createTemplateLine("1001", "debit", "#payload['amount']", null),
                createTemplateLine("6001", "credit", "#payload['amount']", null)
        );

        when(dynamicDataService.create(eq("fac_journal_entry"), any()))
                .thenReturn(Map.of("pid", "je-large"));
        when(dynamicDataService.batchCreate(eq("fac_journal_entry_line"), any()))
                .thenReturn(new DynamicBatchResponse());

        String jeId = service.generateVoucher(template, templateLines, payload,
                "test", "1", "T-LARGE");

        assertEquals("je-large", jeId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> headerCaptor = ArgumentCaptor.forClass(Map.class);
        verify(dynamicDataService).create(eq("fac_journal_entry"), headerCaptor.capture());
        assertEquals(new BigDecimal("1500.0000"), headerCaptor.getValue().get("fac_je_total_debit"));
        assertEquals(new BigDecimal("1500.0000"), headerCaptor.getValue().get("fac_je_total_credit"));
    }

    // ========================================================================
    // 6. String concatenation — multiple concat in description expression
    // ========================================================================

    @Test
    void testStringConcatenationInTemplate_multipleConcat() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("doc_type", "Sales Order");
        payload.put("doc_code", "SO-2026-001");
        payload.put("customer", "Acme Corp");
        payload.put("amount", new BigDecimal("5000.00"));

        String result = resolver.resolveString(
                "'[' + #payload['doc_type'] + '] ' + #payload['doc_code'] "
                        + "+ ' - ' + #payload['customer'] + ' ($' + #payload['amount'] + ')'",
                payload);

        assertEquals("[Sales Order] SO-2026-001 - Acme Corp ($5000.00)", result);
    }

    @Test
    void testStringConcatenationInTemplate_withMathResult() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("qty", 10);
        payload.put("price", new BigDecimal("25.00"));

        String result = resolver.resolveString(
                "'Total: ' + (#payload['qty'] * #payload['price']) + ' units: ' + #payload['qty']",
                payload);

        assertEquals("Total: 250.00 units: 10", result);
    }

    @Test
    void testStringConcatenationInGeneration_descriptionWithMultipleFields() {
        Map<String, Object> template = Map.of("pid", "tpl-concat", "fac_vt_name", "Concat Test");
        List<Map<String, Object>> templateLines = List.of(
                createTemplateLine("1001", "debit", "#payload['amount']",
                        "'SO ' + #payload['so_code'] + ' / Customer: ' + #payload['customer']"),
                createTemplateLine("6001", "credit", "#payload['amount']",
                        "'Revenue for ' + #payload['so_code']")
        );

        Map<String, Object> payload = new HashMap<>();
        payload.put("amount", new BigDecimal("800.00"));
        payload.put("so_code", "SO-042");
        payload.put("customer", "Beta Inc");

        when(dynamicDataService.create(eq("fac_journal_entry"), any()))
                .thenReturn(Map.of("pid", "je-concat"));
        when(dynamicDataService.batchCreate(eq("fac_journal_entry_line"), any()))
                .thenReturn(new DynamicBatchResponse());

        service.generateVoucher(template, templateLines, payload, "test", "1", "T-CONCAT");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> linesCaptor = ArgumentCaptor.forClass(List.class);
        verify(dynamicDataService).batchCreate(eq("fac_journal_entry_line"), linesCaptor.capture());
        List<Map<String, Object>> lines = linesCaptor.getValue();

        assertEquals("SO SO-042 / Customer: Beta Inc", lines.get(0).get("fac_jel_description"));
        assertEquals("Revenue for SO-042", lines.get(1).get("fac_jel_description"));
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private Map<String, Object> createTemplateLine(String accountCode, String direction,
                                                     String amountExpr, String descExpr) {
        Map<String, Object> line = new HashMap<>();
        line.put("fac_vtl_account_code", accountCode);
        line.put("fac_vtl_direction", direction);
        line.put("fac_vtl_amount_expr", amountExpr);
        line.put("fac_vtl_description_expr", descExpr);
        return line;
    }
}
