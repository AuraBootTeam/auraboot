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

@ExtendWith(MockitoExtension.class)
class VoucherGenerationServiceTest {

    @Mock
    private DynamicDataService dynamicDataService;

    private VoucherTemplateResolver templateResolver;
    private VoucherGenerationService service;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(1L, 1L, "test-user-pid", "test-user");
        templateResolver = new VoucherTemplateResolver(); // Real SpEL resolver
        TenantClock tenantClock = mock(TenantClock.class);
        lenient().when(tenantClock.businessDate(any())).thenReturn(LocalDate.now());
        service = new VoucherGenerationService(templateResolver, dynamicDataService, tenantClock);
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void testGenerateSimpleVoucher() {
        // Template with 2 lines: debit 1000, credit 1000
        Map<String, Object> template = Map.of(
            "pid", "tpl-001",
            "fac_vt_code", "TPL-SALES",
            "fac_vt_name", "Sales Revenue"
        );
        List<Map<String, Object>> templateLines = List.of(
            createTemplateLine("1122", "debit", "#payload['amount']", null),
            createTemplateLine("6001", "credit", "#payload['amount']", null)
        );
        Map<String, Object> payload = Map.of("amount", new BigDecimal("1000.00"));

        // Mock: create returns a record with pid
        when(dynamicDataService.create(eq("fac_journal_entry"), any()))
            .thenReturn(Map.of("pid", "je-001"));
        when(dynamicDataService.batchCreate(eq("fac_journal_entry_line"), any()))
            .thenReturn(new DynamicBatchResponse());

        String jeId = service.generateVoucher(template, templateLines, payload,
            "pe_sales_order", "so-001", "SO-20260223-001");

        assertEquals("je-001", jeId);

        // Verify header created
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> headerCaptor = ArgumentCaptor.forClass(Map.class);
        verify(dynamicDataService).create(eq("fac_journal_entry"), headerCaptor.capture());
        Map<String, Object> header = headerCaptor.getValue();
        assertEquals("draft", header.get("fac_je_status"));
        assertEquals(new BigDecimal("1000.0000"), header.get("fac_je_total_debit"));
        assertEquals(new BigDecimal("1000.0000"), header.get("fac_je_total_credit"));
        assertEquals("pe_sales_order", header.get("fac_je_source_type"));
        assertEquals("so-001", header.get("fac_je_source_id"));
        assertEquals("SO-20260223-001", header.get("fac_je_source_code"));
        assertEquals("Sales Revenue", header.get("fac_je_description"));
        assertEquals("tpl-001", header.get("fac_je_template_id"));

        // Verify 2 lines created
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> linesCaptor = ArgumentCaptor.forClass(List.class);
        verify(dynamicDataService).batchCreate(eq("fac_journal_entry_line"), linesCaptor.capture());
        List<Map<String, Object>> lines = linesCaptor.getValue();
        assertEquals(2, lines.size());

        // Verify debit line
        Map<String, Object> debitLine = lines.get(0);
        assertEquals(new BigDecimal("1000.0000"), debitLine.get("fac_jel_debit"));
        assertEquals(BigDecimal.ZERO, debitLine.get("fac_jel_credit"));
        assertEquals("je-001", debitLine.get("fac_jel_entry_id"));

        // Verify credit line
        Map<String, Object> creditLine = lines.get(1);
        assertEquals(BigDecimal.ZERO, creditLine.get("fac_jel_debit"));
        assertEquals(new BigDecimal("1000.0000"), creditLine.get("fac_jel_credit"));
        assertEquals("je-001", creditLine.get("fac_jel_entry_id"));
    }

    @Test
    void testGenerateMultiLineVoucher() {
        Map<String, Object> template = Map.of("pid", "tpl-002", "fac_vt_name", "Multi");
        List<Map<String, Object>> templateLines = List.of(
            createTemplateLine("1001", "debit", "#payload['cash']", null),
            createTemplateLine("1002", "debit", "#payload['bank']", null),
            createTemplateLine("6001", "credit", "#payload['cash'] + #payload['bank']", null)
        );
        Map<String, Object> payload = new HashMap<>();
        payload.put("cash", new BigDecimal("300.00"));
        payload.put("bank", new BigDecimal("700.00"));

        when(dynamicDataService.create(eq("fac_journal_entry"), any()))
            .thenReturn(Map.of("pid", "je-002"));
        when(dynamicDataService.batchCreate(eq("fac_journal_entry_line"), any()))
            .thenReturn(new DynamicBatchResponse());

        String jeId = service.generateVoucher(template, templateLines, payload,
            "pe_payment", "pay-001", "PAY-001");

        assertEquals("je-002", jeId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> headerCaptor = ArgumentCaptor.forClass(Map.class);
        verify(dynamicDataService).create(eq("fac_journal_entry"), headerCaptor.capture());
        Map<String, Object> header = headerCaptor.getValue();
        assertEquals(new BigDecimal("1000.0000"), header.get("fac_je_total_debit"));
        assertEquals(new BigDecimal("1000.0000"), header.get("fac_je_total_credit"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> linesCaptor = ArgumentCaptor.forClass(List.class);
        verify(dynamicDataService).batchCreate(eq("fac_journal_entry_line"), linesCaptor.capture());
        assertEquals(3, linesCaptor.getValue().size());
    }

    @Test
    void testDebitCreditMismatch() {
        Map<String, Object> template = Map.of("pid", "tpl-003", "fac_vt_name", "Bad");
        List<Map<String, Object>> templateLines = List.of(
            createTemplateLine("1001", "debit", "#payload['a']", null),
            createTemplateLine("6001", "credit", "#payload['b']", null)
        );
        Map<String, Object> payload = Map.of(
            "a", new BigDecimal("1000.00"),
            "b", new BigDecimal("500.00")  // Mismatched!
        );

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            service.generateVoucher(template, templateLines, payload, "test", "1", "T-001"));
        assertTrue(ex.getMessage().contains("not balanced"));
        assertTrue(ex.getMessage().contains("debit="));
        assertTrue(ex.getMessage().contains("credit="));
    }

    @Test
    void testZeroAmountLineSkipped() {
        Map<String, Object> template = Map.of("pid", "tpl-004", "fac_vt_name", "Partial");
        List<Map<String, Object>> templateLines = List.of(
            createTemplateLine("1001", "debit", "#payload['amount']", null),
            createTemplateLine("1002", "debit", "#payload['zero_amount']", null),  // Will be 0
            createTemplateLine("6001", "credit", "#payload['amount']", null)
        );
        Map<String, Object> payload = new HashMap<>();
        payload.put("amount", new BigDecimal("500.00"));
        payload.put("zero_amount", BigDecimal.ZERO);

        when(dynamicDataService.create(eq("fac_journal_entry"), any()))
            .thenReturn(Map.of("pid", "je-004"));
        when(dynamicDataService.batchCreate(eq("fac_journal_entry_line"), any()))
            .thenReturn(new DynamicBatchResponse());

        service.generateVoucher(template, templateLines, payload, "test", "1", "T-001");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> linesCaptor = ArgumentCaptor.forClass(List.class);
        verify(dynamicDataService).batchCreate(eq("fac_journal_entry_line"), linesCaptor.capture());
        assertEquals(2, linesCaptor.getValue().size()); // Zero line skipped
    }

    @Test
    void testAllZeroLinesReturnsNull() {
        Map<String, Object> template = Map.of("pid", "tpl-005", "fac_vt_name", "All Zero");
        List<Map<String, Object>> templateLines = List.of(
            createTemplateLine("1001", "debit", "#payload['missing']", null),
            createTemplateLine("6001", "credit", "#payload['also_missing']", null)
        );
        Map<String, Object> payload = Map.of("something_else", "value");

        String result = service.generateVoucher(template, templateLines, payload, "test", "1", "T-001");

        assertNull(result);
        verify(dynamicDataService, never()).create(any(), any());
    }

    @Test
    void testEmptyTemplateLinesReturnsNull() {
        Map<String, Object> template = Map.of("pid", "tpl-006", "fac_vt_name", "Empty");
        String result = service.generateVoucher(template, List.of(), Map.of(), "test", "1", "T-001");
        assertNull(result);
        verify(dynamicDataService, never()).create(any(), any());
    }

    @Test
    void testNullTemplateLinesReturnsNull() {
        Map<String, Object> template = Map.of("pid", "tpl-007", "fac_vt_name", "Null");
        String result = service.generateVoucher(template, null, Map.of(), "test", "1", "T-001");
        assertNull(result);
    }

    @Test
    void testAutoGeneratedCode() {
        String code = service.generateJournalEntryCode();
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        assertTrue(code.startsWith("JE-" + today + "-"));
        // JE- (3) + yyyyMMdd (8) + - (1) + XXXX (4) = 16
        assertEquals(16, code.length());
    }

    @Test
    void testResolveId_pid() {
        Map<String, Object> record = Map.of("pid", "abc-123", "id", 42);
        assertEquals("abc-123", VoucherGenerationService.resolveId(record));
    }

    @Test
    void testResolveId_idFallback() {
        Map<String, Object> record = Map.of("id", 42);
        assertEquals("42", VoucherGenerationService.resolveId(record));
    }

    @Test
    void testResolveId_null() {
        assertNull(VoucherGenerationService.resolveId(null));
        assertNull(VoucherGenerationService.resolveId(Map.of()));
    }

    @Test
    void testDescriptionExpression() {
        Map<String, Object> template = Map.of("pid", "tpl-010", "fac_vt_name", "Desc Test");
        List<Map<String, Object>> templateLines = List.of(
            createTemplateLine("1001", "debit", "#payload['amt']",
                "'Invoice: ' + #payload['code']"),
            createTemplateLine("6001", "credit", "#payload['amt']", null)
        );
        Map<String, Object> payload = new HashMap<>();
        payload.put("amt", new BigDecimal("200.00"));
        payload.put("code", "INV-001");

        when(dynamicDataService.create(eq("fac_journal_entry"), any()))
            .thenReturn(Map.of("pid", "je-010"));
        when(dynamicDataService.batchCreate(eq("fac_journal_entry_line"), any()))
            .thenReturn(new DynamicBatchResponse());

        service.generateVoucher(template, templateLines, payload, "test", "1", "T-001");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> linesCaptor = ArgumentCaptor.forClass(List.class);
        verify(dynamicDataService).batchCreate(eq("fac_journal_entry_line"), linesCaptor.capture());
        List<Map<String, Object>> lines = linesCaptor.getValue();

        // Debit line has description resolved
        assertEquals("Invoice: INV-001", lines.get(0).get("fac_jel_description"));
        // Credit line has empty description (null expr)
        assertEquals("", lines.get(1).get("fac_jel_description"));
    }

    // Helper to create template line maps
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
