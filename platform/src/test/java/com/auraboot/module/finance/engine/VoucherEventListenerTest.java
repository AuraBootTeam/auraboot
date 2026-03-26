package com.auraboot.module.finance.engine;

import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.module.meta.event.CommandCompletedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VoucherEventListenerTest {

    @Mock private DynamicDataService dynamicDataService;
    @Mock private VoucherGenerationService voucherGenerationService;

    private VoucherTemplateResolver templateResolver;
    private VoucherEventListener listener;

    @BeforeEach
    void setUp() {
        templateResolver = new VoucherTemplateResolver();
        listener = new VoucherEventListener(dynamicDataService, templateResolver, voucherGenerationService);
    }

    @Test
    void testMatchesTemplateByEventPattern() {
        // Setup: one active template matches the event
        Map<String, Object> template = createTemplate("TPL-001", "pe:confirm_warehouse_out:UPDATE", "active", null);
        mockTemplateQuery("pe:confirm_warehouse_out:UPDATE", List.of(template));
        mockTemplateLines("tpl-001", List.of(createTemplateLine()));

        when(voucherGenerationService.generateVoucher(any(), any(), any(), any(), any(), any()))
            .thenReturn("je-001");

        CommandCompletedEvent event = createEvent("pe:confirm_warehouse_out", "update");
        listener.onCommandCompleted(event);

        verify(voucherGenerationService).generateVoucher(eq(template), any(), any(), any(), any(), any());
    }

    @Test
    void testConditionFilterSkipsNonMatching() {
        // Template has condition that doesn't match
        Map<String, Object> template = createTemplate("TPL-002",
            "pe:confirm_warehouse_out:UPDATE", "active",
            "#payload['pe_wo_type'] == 'purchase_in'");  // Condition won't match
        mockTemplateQuery("pe:confirm_warehouse_out:UPDATE", List.of(template));

        // Event payload has type = SALES_OUT (doesn't match condition)
        CommandCompletedEvent event = new CommandCompletedEvent(
            1L, "rec-001", "pe_warehouse_out",
            Map.of("pe_wo_type", "sales_out"),
            "pe:confirm_warehouse_out", "update");
        listener.onCommandCompleted(event);

        verify(voucherGenerationService, never()).generateVoucher(any(), any(), any(), any(), any(), any());
    }

    @Test
    void testMultipleTemplatesForSameEvent() {
        Map<String, Object> tpl1 = createTemplate("TPL-A", "pe:confirm_warehouse_out:UPDATE", "active", null);
        Map<String, Object> tpl2 = createTemplate("TPL-B", "pe:confirm_warehouse_out:UPDATE", "active", null);
        mockTemplateQuery("pe:confirm_warehouse_out:UPDATE", List.of(tpl1, tpl2));
        mockTemplateLines("tpl-a", List.of(createTemplateLine()));
        mockTemplateLines("tpl-b", List.of(createTemplateLine()));

        when(voucherGenerationService.generateVoucher(any(), any(), any(), any(), any(), any()))
            .thenReturn("je-001");

        listener.onCommandCompleted(createEvent("pe:confirm_warehouse_out", "update"));

        verify(voucherGenerationService, times(2)).generateVoucher(any(), any(), any(), any(), any(), any());
    }

    @Test
    void testNoMatchingTemplate() {
        mockTemplateQuery("pe:some_command:CREATE", List.of());

        listener.onCommandCompleted(createEvent("pe:some_command", "create"));

        verify(voucherGenerationService, never()).generateVoucher(any(), any(), any(), any(), any(), any());
    }

    @Test
    void testFinanceEventsSkipped() {
        CommandCompletedEvent event = createEvent("fac:create_journal_entry", "create");
        listener.onCommandCompleted(event);

        // Should not even query templates
        verify(dynamicDataService, never()).list(any(), any());
    }

    @Test
    void testTemplateFailureDoesNotBlockOthers() {
        Map<String, Object> tpl1 = createTemplate("TPL-FAIL", "pe:cmd:UPDATE", "active", null);
        Map<String, Object> tpl2 = createTemplate("TPL-OK", "pe:cmd:UPDATE", "active", null);
        mockTemplateQuery("pe:cmd:UPDATE", List.of(tpl1, tpl2));
        mockTemplateLines("tpl-fail", List.of(createTemplateLine()));
        mockTemplateLines("tpl-ok", List.of(createTemplateLine()));

        // First template throws, second succeeds
        when(voucherGenerationService.generateVoucher(eq(tpl1), any(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("Template processing error"));
        when(voucherGenerationService.generateVoucher(eq(tpl2), any(), any(), any(), any(), any()))
            .thenReturn("je-002");

        listener.onCommandCompleted(createEvent("pe:cmd", "update"));

        // Second template should still be processed
        verify(voucherGenerationService).generateVoucher(eq(tpl2), any(), any(), any(), any(), any());
    }

    @Test
    void testTemplateWithConditionThatPasses() {
        Map<String, Object> template = createTemplate("TPL-COND",
            "pe:deliver:UPDATE", "active",
            "#payload['amount'] > 0");
        mockTemplateQuery("pe:deliver:UPDATE", List.of(template));
        mockTemplateLines("tpl-cond", List.of(createTemplateLine()));

        when(voucherGenerationService.generateVoucher(any(), any(), any(), any(), any(), any()))
            .thenReturn("je-003");

        CommandCompletedEvent event = new CommandCompletedEvent(
            1L, "rec-001", "pe_sales_order",
            Map.of("amount", 1000),
            "pe:deliver", "update");
        listener.onCommandCompleted(event);

        verify(voucherGenerationService).generateVoucher(any(), any(), any(), any(), any(), any());
    }

    // === Helpers ===

    private CommandCompletedEvent createEvent(String commandCode, String operationType) {
        return new CommandCompletedEvent(
            1L, "rec-001", "pe_model",
            Map.of("pe_so_code", "SO-001"),
            commandCode, operationType);
    }

    private Map<String, Object> createTemplate(String code, String eventPattern,
                                                 String status, String condition) {
        Map<String, Object> tpl = new HashMap<>();
        tpl.put("pid", code.toLowerCase());
        tpl.put("fac_vt_code", code);
        tpl.put("fac_vt_event_pattern", eventPattern);
        tpl.put("fac_vt_status", status);
        tpl.put("fac_vt_condition", condition);
        tpl.put("fac_vt_name", "Template " + code);
        return tpl;
    }

    private Map<String, Object> createTemplateLine() {
        Map<String, Object> line = new HashMap<>();
        line.put("fac_vtl_account_code", "1001");
        line.put("fac_vtl_direction", "debit");
        line.put("fac_vtl_amount_expr", "#payload['amount']");
        return line;
    }

    private void mockTemplateQuery(String eventPattern, List<Map<String, Object>> templates) {
        PaginationResult<Map<String, Object>> result = new PaginationResult<>();
        result.setRecords(templates);
        result.setTotal((long) templates.size());

        // Match any DynamicQueryRequest for the template model
        when(dynamicDataService.list(eq("fac_voucher_template"), any(DynamicQueryRequest.class)))
            .thenReturn(result);
    }

    private void mockTemplateLines(String templateId, List<Map<String, Object>> lines) {
        // Use lenient mock that returns lines for any template line query
        PaginationResult<Map<String, Object>> result = new PaginationResult<>();
        result.setRecords(lines);
        result.setTotal((long) lines.size());

        lenient().when(dynamicDataService.list(eq("fac_voucher_template_line"), any(DynamicQueryRequest.class)))
            .thenReturn(result);
    }
}
