package com.auraboot.framework.meta.handler;

import com.auraboot.framework.bpm.service.BpmIntegrationService;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.service.CommandHandlerContext;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for BuiltinStartApprovalHandler.
 */
@ExtendWith(MockitoExtension.class)
class BuiltinStartApprovalHandlerTest {

    @Mock
    private BpmIntegrationService bpmIntegrationService;

    @Mock
    private DynamicDataService dynamicDataService;

    @Spy
    private ObjectMapper objectMapper;

    @InjectMocks
    private BuiltinStartApprovalHandler handler;

    // =========================================================
    // getHandlerName
    // =========================================================

    @Test
    void getHandlerName_returnsExpectedName() {
        assertThat(handler.getHandlerName()).isEqualTo("builtinStartApprovalHandler");
    }

    // =========================================================
    // execute() — input validation: recordId
    // =========================================================

    @Test
    void execute_missingRecordId_throwsBusinessException() {
        CommandHandlerContext ctx = CommandHandlerContext.builder()
                .commandCode("cmd001")
                .modelCode("cc_contract")
                .targetRecordId(null)
                .payload(null)
                .ruleConfig(null)
                .userId(1L)
                .build();

        assertThatThrownBy(() -> handler.execute(ctx))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void execute_blankRecordId_throwsBusinessException() {
        CommandHandlerContext ctx = CommandHandlerContext.builder()
                .commandCode("cmd001")
                .modelCode("cc_contract")
                .targetRecordId("  ")
                .payload(null)
                .ruleConfig(null)
                .userId(1L)
                .build();

        assertThatThrownBy(() -> handler.execute(ctx))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void execute_recordIdFromPayload_pid_usedWhenTargetMissing() {
        // targetRecordId is blank but payload has "pid"
        CommandHandlerContext ctx = CommandHandlerContext.builder()
                .commandCode("cmd001")
                .modelCode("cc_contract")
                .targetRecordId("")
                .payload(Map.of("pid", "pid-from-payload"))
                .ruleConfig(null)
                .userId(1L)
                .build();

        Map<String, Object> result = handler.execute(ctx);

        assertThat(result.get("handlerExecuted")).isEqualTo(true);
        // businessKey should contain the pid from payload
        assertThat(result.get("businessKey")).asString().contains("pid-from-payload");
    }

    // =========================================================
    // execute() — happy path with no config
    // =========================================================

    @Test
    void execute_noConfig_usesDefaults_startsProcess() {
        CommandHandlerContext ctx = CommandHandlerContext.builder()
                .commandCode("cmd_approve")
                .modelCode("cc_contract")
                .targetRecordId("rec-001")
                .payload(null)
                .ruleConfig(null)
                .userId(10L)
                .build();

        Map<String, Object> result = handler.execute(ctx);

        assertThat(result.get("handlerExecuted")).isEqualTo(true);
        assertThat(result.get("action")).isEqualTo("start_approval");
        assertThat(result.get("processKey")).isEqualTo("simple-approval");
        assertThat(result.get("businessKey")).isEqualTo("cc_contract:rec-001");

        // stateField not configured → dynamicDataService.update must NOT be called
        verify(dynamicDataService, never()).update(any(), any(), any());

        // BPM process must be started
        verify(bpmIntegrationService).startBusinessProcess(
                eq("simple-approval"),
                eq("cc_contract:rec-001"),
                any(),
                any()
        );
    }

    // =========================================================
    // execute() — config with processKey + stateField
    // =========================================================

    @Test
    void execute_withStateField_updatesRecordStatus() {
        String ruleConfig = "{\"approvalProcessKey\":\"contract-approval\"," +
                "\"stateField\":\"cc_contract_status\"," +
                "\"approvalTitle\":\"Contract Approval\"}";

        CommandHandlerContext ctx = CommandHandlerContext.builder()
                .commandCode("cmd_approve")
                .modelCode("cc_contract")
                .targetRecordId("rec-002")
                .payload(null)
                .ruleConfig(ruleConfig)
                .userId(5L)
                .build();

        Map<String, Object> result = handler.execute(ctx);

        assertThat(result.get("processKey")).isEqualTo("contract-approval");

        // stateField configured → update must be called with PENDING_APPROVAL
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> stateCaptor = ArgumentCaptor.forClass(Map.class);
        verify(dynamicDataService).update(eq("cc_contract"), eq("rec-002"), stateCaptor.capture());
        assertThat(stateCaptor.getValue().get("cc_contract_status")).isEqualTo("pending_approval");
    }

    @Test
    void execute_customProcessKey_passedToBpm() {
        String ruleConfig = "{\"approvalProcessKey\":\"my-custom-flow\"}";

        CommandHandlerContext ctx = CommandHandlerContext.builder()
                .commandCode("cmd_approve")
                .modelCode("order")
                .targetRecordId("ord-999")
                .payload(null)
                .ruleConfig(ruleConfig)
                .userId(1L)
                .build();

        handler.execute(ctx);

        verify(bpmIntegrationService).startBusinessProcess(
                eq("my-custom-flow"),
                eq("order:ord-999"),
                any(),
                any()
        );
    }

    // =========================================================
    // execute() — title template resolution
    // =========================================================

    @Test
    void execute_titleTemplate_substitutesPayloadFields() {
        String ruleConfig = "{\"approvalTitle\":\"Contract Approval: ${cc_contract_name}\"}";

        CommandHandlerContext ctx = CommandHandlerContext.builder()
                .commandCode("cmd_approve")
                .modelCode("cc_contract")
                .targetRecordId("rec-003")
                .payload(Map.of("cc_contract_name", "ACME Agreement 2026"))
                .ruleConfig(ruleConfig)
                .userId(1L)
                .build();

        handler.execute(ctx);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
        verify(bpmIntegrationService).startBusinessProcess(any(), any(), any(), titleCaptor.capture());
        assertThat(titleCaptor.getValue()).isEqualTo("Contract Approval: ACME Agreement 2026");
    }

    @Test
    void execute_titleTemplate_missingPayloadField_replacedWithEmpty() {
        String ruleConfig = "{\"approvalTitle\":\"Approval for ${missing_field}\"}";

        CommandHandlerContext ctx = CommandHandlerContext.builder()
                .commandCode("cmd_approve")
                .modelCode("cc_contract")
                .targetRecordId("rec-004")
                .payload(Map.of("other_field", "ignored"))
                .ruleConfig(ruleConfig)
                .userId(1L)
                .build();

        handler.execute(ctx);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
        verify(bpmIntegrationService).startBusinessProcess(any(), any(), any(), titleCaptor.capture());
        // ${missing_field} replaced by empty string
        assertThat(titleCaptor.getValue()).isEqualTo("Approval for ");
    }

    @Test
    void execute_nullTitleTemplate_emptyPayload_stripsPlaceholders() {
        // No approvalTitle in config → default "Approval: <modelCode>"
        // payload null → resolveTitle returns template unchanged (no ${} to strip)
        CommandHandlerContext ctx = CommandHandlerContext.builder()
                .commandCode("cmd_approve")
                .modelCode("order")
                .targetRecordId("ord-001")
                .payload(null)
                .ruleConfig(null)
                .userId(1L)
                .build();

        handler.execute(ctx);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
        verify(bpmIntegrationService).startBusinessProcess(any(), any(), any(), titleCaptor.capture());
        // default title = "Approval: order" (no placeholders → returned as-is with empty payload path)
        assertThat(titleCaptor.getValue()).isEqualTo("Approval: order");
    }

    // =========================================================
    // execute() — invalid ruleConfig JSON
    // =========================================================

    @Test
    void execute_invalidConfigJson_usesDefaults() {
        // Invalid JSON → parseConfig returns empty map → defaults are used
        CommandHandlerContext ctx = CommandHandlerContext.builder()
                .commandCode("cmd001")
                .modelCode("cc_contract")
                .targetRecordId("rec-005")
                .payload(null)
                .ruleConfig("NOT_VALID_JSON{{{")
                .userId(1L)
                .build();

        Map<String, Object> result = handler.execute(ctx);

        assertThat(result.get("processKey")).isEqualTo("simple-approval");
        assertThat(result.get("handlerExecuted")).isEqualTo(true);
    }

    // =========================================================
    // execute() — businessData includes payload
    // =========================================================

    @Test
    void execute_payloadMergedIntoBusinessData() {
        CommandHandlerContext ctx = CommandHandlerContext.builder()
                .commandCode("cmd_approve")
                .modelCode("cc_contract")
                .targetRecordId("rec-006")
                .payload(Map.of("cc_contract_amount", 50000))
                .ruleConfig(null)
                .userId(99L)
                .build();

        handler.execute(ctx);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(bpmIntegrationService).startBusinessProcess(any(), any(), dataCaptor.capture(), any());

        Map<String, Object> businessData = dataCaptor.getValue();
        assertThat(businessData.get("modelCode")).isEqualTo("cc_contract");
        assertThat(businessData.get("recordId")).isEqualTo("rec-006");
        assertThat(businessData.get("initiator")).isEqualTo("99");
        assertThat(businessData.get("cc_contract_amount")).isEqualTo(50000);
    }
}
