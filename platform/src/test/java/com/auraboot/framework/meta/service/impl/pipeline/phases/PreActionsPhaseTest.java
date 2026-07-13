package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.bpm.rule.DroolsEngineService;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.meta.service.impl.pipeline.RecordSnapshotReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PreActionsPhase}. Verifies contextLookup, placeholder
 * resolution, and BusinessException propagation on rule failure.
 */
@ExtendWith(MockitoExtension.class)
class PreActionsPhaseTest {

    @Mock
    private DroolsEngineService droolsEngineService;

    @Mock
    private DynamicDataService dynamicDataService;

    @Mock
    private RecordSnapshotReader snapshotReader;

    @InjectMocks
    private PreActionsPhase phase;

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void execute_ruleInvalid_throwsBusinessException() {
        Map<String, Object> lookup = Map.of(
                "modelCode", "wd_leave_balance",
                "filters", List.of(Map.of("field", "wd_bal_employee", "op", "=", "value", "${payload.wd_req_applicant}")),
                "exposeAs", "balance"
        );
        Map<String, Object> action = Map.of(
                "type", "bpm:run-rule",
                "ruleCode", "wd_leave_validation",
                "contextLookup", List.of(lookup),
                "facts", Map.of(
                        "type", "${payload.wd_req_type}",
                        "days", "${payload.wd_req_days}",
                        "balanceRemaining", "${balance.wd_bal_annual_remaining}"
                )
        );

        Map<String, Object> payload = Map.of(
                "wd_req_applicant", "emp-1",
                "wd_req_type", "annual",
                "wd_req_days", 10
        );

        CommandPipelineContext ctx = CommandPipelineContext.builder()
                .execConfig(new HashMap<>(Map.of("preActions", List.of(action))))
                .payload(new HashMap<>(payload))
                .build();

        PaginationResult<Map<String, Object>> lookupResult =
                PaginationResult.of(List.of(Map.of("wd_bal_annual_remaining", 2)), 1L, 1, 1);
        when(dynamicDataService.list(eq("wd_leave_balance"), any(DynamicQueryRequest.class)))
                .thenReturn(lookupResult);

        when(droolsEngineService.evaluate(eq("wd_leave_validation"), any()))
                .thenReturn(Map.of("valid", false, "reason", "annual_leave_insufficient"));

        assertThatThrownBy(() -> phase.execute(ctx))
                .isInstanceOf(BusinessException.class)
                // The rule reports a bare reason; the phase must throw the i18n key so the
                // handler can localize it instead of leaking the raw code to the UI.
                .hasMessage("$i18n:error.wd_leave_validation.annual_leave_insufficient");

        ArgumentCaptor<Map<String, Object>> factsCaptor = ArgumentCaptor.forClass((Class) Map.class);
        org.mockito.Mockito.verify(droolsEngineService).evaluate(any(), factsCaptor.capture());
        Map<String, Object> facts = factsCaptor.getValue();
        assertThat(facts).containsEntry("type", "annual");
        assertThat(facts).containsEntry("days", 10);
        assertThat(facts).containsEntry("balanceRemaining", 2);
    }

    @Test
    void execute_noPreActions_noop() {
        CommandPipelineContext ctx = CommandPipelineContext.builder()
                .execConfig(new HashMap<>())
                .payload(new HashMap<>())
                .build();
        phase.execute(ctx);
        // No exception, no collaborator interactions required.
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void execute_sizePlaceholder_resolvesCollectionSizeForRuleFacts() {
        Map<String, Object> action = Map.of(
                "type", "bpm:run-rule",
                "ruleCode", "wd_leave_validation",
                "facts", Map.of(
                        "type", "${payload.wd_req_type}",
                        "days", "${payload.wd_req_days}",
                        "attachmentCount", "${payload.wd_req_attachments.size}"
                )
        );

        Map<String, Object> payload = Map.of(
                "wd_req_type", "sick",
                "wd_req_days", 3,
                "wd_req_attachments", List.of(
                        Map.of("name", "diagnosis.pdf"),
                        Map.of("name", "receipt.pdf")
                )
        );

        CommandPipelineContext ctx = CommandPipelineContext.builder()
                .execConfig(new HashMap<>(Map.of("preActions", List.of(action))))
                .payload(new HashMap<>(payload))
                .build();

        when(droolsEngineService.evaluate(eq("wd_leave_validation"), any()))
                .thenReturn(Map.of("valid", true));

        phase.execute(ctx);

        ArgumentCaptor<Map<String, Object>> factsCaptor = ArgumentCaptor.forClass((Class) Map.class);
        org.mockito.Mockito.verify(droolsEngineService).evaluate(any(), factsCaptor.capture());
        assertThat(factsCaptor.getValue()).containsEntry("attachmentCount", 2);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void execute_currentRecordPlaceholder_loadsTargetSnapshotBeforeFieldMap() {
        Map<String, Object> lookup = Map.of(
                "modelCode", "wd_leave_balance",
                "filters", List.of(Map.of(
                        "field", "wd_bal_employee",
                        "op", "=",
                        "value", "${currentRecord.wd_req_applicant}"
                )),
                "exposeAs", "balance"
        );
        Map<String, Object> action = Map.of(
                "type", "bpm:run-rule",
                "ruleCode", "wd_leave_validation",
                "contextLookup", List.of(lookup),
                "facts", Map.of(
                        "type", "${currentRecord.wd_req_type}",
                        "days", "${currentRecord.wd_req_days}",
                        "balanceRemaining", "${balance.wd_bal_annual_remaining}",
                        "attachmentCount", "${currentRecord.wd_req_attachments.size}"
                )
        );

        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setTargetRecordId("REQ-1");
        CommandDefinition command = new CommandDefinition();
        command.setModelCode("wd_leave_request");

        Map<String, Object> currentRecord = Map.of(
                "wd_req_applicant", "emp-1",
                "wd_req_type", "annual",
                "wd_req_days", 64,
                "wd_req_attachments", List.of()
        );

        CommandPipelineContext ctx = CommandPipelineContext.builder()
                .tenantId(100L)
                .request(request)
                .command(command)
                .execConfig(new HashMap<>(Map.of("preActions", List.of(action))))
                .payload(new HashMap<>())
                .build();

        when(snapshotReader.readRecordSnapshot(100L, "wd_leave_request", "REQ-1"))
                .thenReturn(currentRecord);
        when(dynamicDataService.list(eq("wd_leave_balance"), any(DynamicQueryRequest.class)))
                .thenReturn(PaginationResult.of(
                        List.of(Map.of("wd_bal_annual_remaining", 2)),
                        1L,
                        1,
                        1
                ));
        when(droolsEngineService.evaluate(eq("wd_leave_validation"), any()))
                .thenReturn(Map.of("valid", true));

        phase.execute(ctx);

        ArgumentCaptor<Map<String, Object>> factsCaptor = ArgumentCaptor.forClass((Class) Map.class);
        org.mockito.Mockito.verify(droolsEngineService).evaluate(any(), factsCaptor.capture());
        assertThat(factsCaptor.getValue())
                .containsEntry("type", "annual")
                .containsEntry("days", 64)
                .containsEntry("balanceRemaining", 2)
                .containsEntry("attachmentCount", 0);
        assertThat(ctx.getBeforeSnapshot()).isEqualTo(currentRecord);
    }
}
