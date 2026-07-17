package com.auraboot.framework.eventpolicy.executor.handler;

import com.auraboot.framework.bpm.service.ProcessEngineService;
import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.eventpolicy.executor.ActionExecutionException;
import com.auraboot.framework.eventpolicy.model.ResolvedActionPlan;
import com.auraboot.smart.framework.engine.model.instance.ProcessInstance;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link StartProcessActionHandler} — starts a BPM process with the right definition /
 * business key / variables. {@code StartProcessE2EIntegrationTest} covers the real deployed BPM engine
 * path; this verifies the ProcessEngineService call contract and structured result payload.
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
        var record = Map.<String, Object>of("entityCode", "complaint", "recordPid", "CMP-9");
        handler.execute(plan(Map.of("processDefinitionId", "approval_v1",
                "variables", Map.of("level", "HIGH"))), ctx(record));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.forClass(Map.class);
        verify(processEngineService).startProcess(eq("approval_v1"), eq("CMP-9"), vars.capture());
        assertThat(vars.getValue()).containsEntry("level", "HIGH").containsEntry("recordPid", "CMP-9");
    }

    @Test
    void returnsStructuredProcessStartResult() throws Exception {
        ProcessInstance instance = mock(ProcessInstance.class);
        when(instance.getInstanceId()).thenReturn("PI-1001");
        when(processEngineService.startProcess(eq("approval_v1"), eq("CMP-9"), anyMap()))
                .thenReturn(instance);

        var record = Map.<String, Object>of("entityCode", "complaint", "recordPid", "CMP-9");
        Map<String, Object> result = handler.executeWithResult(plan(Map.of("processDefinitionId", "approval_v1",
                "variables", Map.of("level", "HIGH"))), ctx(record));

        assertThat(result)
                .containsEntry("processDefinitionId", "approval_v1")
                .containsEntry("businessKey", "CMP-9")
                .containsEntry("processInstanceId", "PI-1001");
    }

    @Test
    void explicitBusinessKeyOverridesRecordId() {
        var record = Map.<String, Object>of("recordPid", "CMP-9");
        handler.execute(plan(Map.of("processDefinitionId", "p1", "businessKey", "BK-1")), ctx(record));
        verify(processEngineService).startProcess(eq("p1"), eq("BK-1"), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void throwsWhenProcessDefinitionMissing() {
        assertThatThrownBy(() -> handler.execute(plan(Map.of("variables", Map.of())), ctx(Map.of("recordPid", "X"))))
                .isInstanceOf(ActionExecutionException.class)
                .hasMessage("缺少流程标识，无法启动流程")
                .satisfies(error -> assertThat(((ActionExecutionException) error).resultPayload())
                        .containsEntry("failureReason", "process_definition_missing")
                        .containsEntry("field", "payload.processDefinitionId")
                        .containsEntry("recordPid", "X")
                        .doesNotContainKeys("processDefinitionId", "businessKey"));
        verifyNoInteractions(processEngineService);
    }

    @Test
    void wrapsEngineFailureWithStructuredTracePayload() {
        when(processEngineService.startProcess(eq("missing_flow"), eq("CMP-9"), anyMap()))
                .thenThrow(new IllegalStateException(
                        "Process definition version not found for id: missing_flow"));

        var record = Map.<String, Object>of("entityCode", "complaint", "recordPid", "CMP-9");

        assertThatThrownBy(() -> handler.execute(
                plan(Map.of("processDefinitionId", "missing_flow")),
                ctx(record)))
                .isInstanceOf(ActionExecutionException.class)
                .hasMessage("流程启动失败：流程未部署或流程标识不存在")
                .hasCauseInstanceOf(IllegalStateException.class)
                .satisfies(error -> assertThat(((ActionExecutionException) error).resultPayload())
                        .containsEntry("failureReason", "process_start_failed")
                        .containsEntry("processDefinitionId", "missing_flow")
                        .containsEntry("businessKey", "CMP-9")
                        .containsEntry("recordPid", "CMP-9")
                        .doesNotContainKey("field"));
    }
}
