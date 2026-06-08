package com.auraboot.framework.eventpolicy.executor.handler;

import com.auraboot.framework.bpm.service.ProcessEngineService;
import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.eventpolicy.model.ResolvedActionPlan;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit test for {@link StartProcessActionHandler} — starts a BPM process with the right definition /
 * business key / variables. The real-stack IT needs a deployed process definition, so it is a
 * documented follow-on (gap tracker); this verifies the ProcessEngineService call contract.
 */
class StartProcessActionHandlerTest {

    private final ProcessEngineService processEngineService = mock(ProcessEngineService.class);
    private final StartProcessActionHandler handler = new StartProcessActionHandler(processEngineService);

    private DecisionContext ctx(Map<String, Object> record) {
        return DecisionContext.builder().scope(Scope.RECORD, record).build();
    }

    private ResolvedActionPlan plan(Map<String, Object> payload) {
        return new ResolvedActionPlan("R-1", "START_PROCESS", "BPM", 10, payload, "idem-1");
    }

    @Test
    void startsProcessWithBusinessKeyAndVariables() {
        var record = Map.<String, Object>of("entityCode", "complaint", "recordId", "CMP-9");
        handler.execute(plan(Map.of("processDefinitionId", "approval_v1",
                "variables", Map.of("level", "HIGH"))), ctx(record));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.forClass(Map.class);
        verify(processEngineService).startProcess(eq("approval_v1"), eq("CMP-9"), vars.capture());
        assertThat(vars.getValue()).containsEntry("level", "HIGH").containsEntry("recordId", "CMP-9");
    }

    @Test
    void explicitBusinessKeyOverridesRecordId() {
        var record = Map.<String, Object>of("recordId", "CMP-9");
        handler.execute(plan(Map.of("processDefinitionId", "p1", "businessKey", "BK-1")), ctx(record));
        verify(processEngineService).startProcess(eq("p1"), eq("BK-1"), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void throwsWhenProcessDefinitionMissing() {
        assertThatThrownBy(() -> handler.execute(plan(Map.of("variables", Map.of())), ctx(Map.of("recordId", "X"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
