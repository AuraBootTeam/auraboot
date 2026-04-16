package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.bpm.rule.DroolsEngineService;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
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
                .hasMessageContaining("annual_leave_insufficient");

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
}
