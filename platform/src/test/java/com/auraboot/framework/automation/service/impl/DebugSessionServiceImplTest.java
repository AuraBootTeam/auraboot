package com.auraboot.framework.automation.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.automation.dto.DebugSessionCreateRequest;
import com.auraboot.framework.automation.dto.DebugSessionDTO;
import com.auraboot.framework.automation.entity.Automation;
import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.entity.DebugSession;
import com.auraboot.framework.automation.executor.ActionExecutor;
import com.auraboot.framework.automation.mapper.AutomationMapper;
import com.auraboot.framework.automation.mapper.DebugSessionMapper;
import com.auraboot.framework.automation.service.DebugEventPublisher;
import com.auraboot.framework.exception.ValidationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DebugSessionServiceImpl}.
 *
 * <p>Targets the previously near-uncovered class (1% instruction coverage,
 * 234/241 lines missed). Covers:
 * <ul>
 *   <li>createSession — happy path, automation-not-found, replacing existing
 *       active session, default trigger payload, default breakpoints</li>
 *   <li>step — completing-when-already-done, success path, failure path with
 *       continueOnError true/false, intermediate vs terminal advance</li>
 *   <li>continueExecution — runs to completion, hits breakpoint, fails fast,
 *       handles already-stopped session</li>
 *   <li>stop / restart / getContext / getSession / updateBreakpoints</li>
 *   <li>subscribeEvents — delegates to {@link DebugEventPublisher}</li>
 *   <li>guard rails — non-active step / continue / stop throw {@link ValidationException}</li>
 * </ul>
 *
 * <p>Pure Mockito; never touches DB.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DebugSessionServiceImpl — orchestrator")
class DebugSessionServiceImplTest {

    @Mock private DebugSessionMapper debugSessionMapper;
    @Mock private AutomationMapper automationMapper;
    @Mock private ActionExecutor actionExecutor;
    @Mock private DebugEventPublisher eventPublisher;

    @InjectMocks private DebugSessionServiceImpl service;

    private static final String AUTOMATION_PID = "autom-1";
    private static final String SESSION_PID = "session-1";

    @BeforeEach
    void setupTenant() {
        MetaContext.setSystemTenantContext(1L);
    }

    @AfterEach
    void clearTenant() {
        MetaContext.clear();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Automation automationWithActions(int count) {
        Automation a = new Automation();
        a.setPid(AUTOMATION_PID);
        // Match the system tenant set in @BeforeEach — createSession() now enforces
        // tenant ownership (MetaContext.getCurrentTenantId() == automation.tenantId) to
        // block cross-tenant IDOR. Without a matching tenantId the guard rejects the
        // stubbed automation with "Automation not found".
        a.setTenantId(1L);
        List<AutomationAction> actions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            AutomationAction action = new AutomationAction();
            action.setSequence(i);
            action.setType("UPDATE_RECORD");
            action.setLabel("step-" + i);
            actions.add(action);
        }
        a.setActions(actions);
        return a;
    }

    private DebugSession baseSession(String status, int currentIdx) {
        DebugSession s = new DebugSession();
        s.setPid(SESSION_PID);
        s.setAutomationId(AUTOMATION_PID);
        s.setStatus(status);
        s.setCurrentActionIndex(currentIdx);
        s.setBreakpoints(new ArrayList<>());
        s.setExecutionContext(new HashMap<>());
        s.setActionResults(new ArrayList<>());
        s.setTriggerPayload(new HashMap<>());
        return s;
    }

    // ------------------------------------------------------------------
    // createSession
    // ------------------------------------------------------------------

    @Test
    @DisplayName("createSession persists paused session with merged context")
    void createSession_happyPath() {
        when(automationMapper.findByPid(AUTOMATION_PID)).thenReturn(automationWithActions(2));
        when(debugSessionMapper.findActiveByAutomationId(AUTOMATION_PID)).thenReturn(null);

        DebugSessionCreateRequest req = new DebugSessionCreateRequest();
        req.setRecordPid("rec-1");
        Map<String, Object> payload = new HashMap<>();
        payload.put("foo", "bar");
        req.setTriggerPayload(payload);
        req.setBreakpoints(List.of(1));

        DebugSessionDTO dto = service.createSession(AUTOMATION_PID, req);

        assertThat(dto.getStatus()).isEqualTo("paused");
        assertThat(dto.getTotalActions()).isEqualTo(2);
        assertThat(dto.getRecordPid()).isEqualTo("rec-1");
        assertThat(dto.getExecutionContext())
                .containsEntry("foo", "bar")
                .containsEntry("recordPid", "rec-1")
                .containsEntry("automationPid", AUTOMATION_PID)
                .containsEntry("debugMode", true)
                .doesNotContainKey("record" + "Id");
        assertThat(dto.getBreakpoints()).containsExactly(1);
        verify(debugSessionMapper).insertSession(any(DebugSession.class));
    }

    @Test
    @DisplayName("createSession throws when automation not found")
    void createSession_automationMissing() {
        when(automationMapper.findByPid(AUTOMATION_PID)).thenReturn(null);
        DebugSessionCreateRequest req = new DebugSessionCreateRequest();
        assertThatThrownBy(() -> service.createSession(AUTOMATION_PID, req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Automation not found");
        verify(debugSessionMapper, never()).insertSession(any());
    }

    @Test
    @DisplayName("createSession stops & closes prior active session")
    void createSession_replacesActiveSession() {
        when(automationMapper.findByPid(AUTOMATION_PID)).thenReturn(automationWithActions(1));
        DebugSession previous = baseSession("running", 0);
        previous.setPid("prev-session");
        when(debugSessionMapper.findActiveByAutomationId(AUTOMATION_PID)).thenReturn(previous);

        DebugSessionCreateRequest req = new DebugSessionCreateRequest();
        DebugSessionDTO dto = service.createSession(AUTOMATION_PID, req);

        assertThat(previous.getStatus()).isEqualTo("stopped");
        verify(debugSessionMapper).updateSession(previous);
        verify(eventPublisher).closeSession("prev-session");
        assertThat(dto.getBreakpoints()).isEmpty();
    }

    @Test
    @DisplayName("createSession applies safe defaults for null payload/breakpoints")
    void createSession_defaultsApplied() {
        when(automationMapper.findByPid(AUTOMATION_PID)).thenReturn(automationWithActions(0));
        when(debugSessionMapper.findActiveByAutomationId(AUTOMATION_PID)).thenReturn(null);

        DebugSessionCreateRequest req = new DebugSessionCreateRequest();
        DebugSessionDTO dto = service.createSession(AUTOMATION_PID, req);

        assertThat(dto.getTotalActions()).isZero();
        assertThat(dto.getBreakpoints()).isEmpty();
        assertThat(dto.getTriggerPayload()).isEmpty();
        assertThat(dto.getExecutionContext()).containsKeys("automationPid", "debugMode");
    }

    // ------------------------------------------------------------------
    // step
    // ------------------------------------------------------------------

    @Test
    @DisplayName("step executes current action then pauses for next")
    void step_intermediateAdvance() {
        DebugSession s = baseSession("paused", 0);
        when(debugSessionMapper.findByPid(SESSION_PID)).thenReturn(s);
        when(automationMapper.findByPid(AUTOMATION_PID)).thenReturn(automationWithActions(2));
        when(actionExecutor.execute(any(), anyMap())).thenReturn("ok");

        DebugSessionDTO dto = service.step(SESSION_PID);

        assertThat(s.getCurrentActionIndex()).isEqualTo(1);
        assertThat(s.getStatus()).isEqualTo("paused");
        assertThat(s.getActionResults()).hasSize(1);
        assertThat(dto.getTotalActions()).isEqualTo(2);
        verify(eventPublisher, times(3)).publish(eq(SESSION_PID), any());
    }

    @Test
    @DisplayName("step rebuilds context when persisted executionContext is null")
    void step_nullPersistedExecutionContext_rebuildsSafeContext() {
        DebugSession s = baseSession("paused", 0);
        s.setExecutionContext(null);
        s.setTriggerPayload(Map.of("field", "value"));
        when(debugSessionMapper.findByPid(SESSION_PID)).thenReturn(s);
        when(automationMapper.findByPid(AUTOMATION_PID)).thenReturn(automationWithActions(1));
        when(actionExecutor.execute(any(), anyMap())).thenReturn("ok");

        DebugSessionDTO dto = service.step(SESSION_PID);

        ArgumentCaptor<Map<String, Object>> contextCaptor = ArgumentCaptor.forClass(Map.class);
        verify(actionExecutor).execute(any(), contextCaptor.capture());
        assertThat(contextCaptor.getValue())
                .containsEntry("field", "value")
                .containsEntry("automationPid", AUTOMATION_PID)
                .containsEntry("debugMode", true);
        assertThat(dto.getStatus()).isEqualTo("completed");
        assertThat(dto.getActionResults()).hasSize(1);
    }

    @Test
    @DisplayName("step completes the session when last action runs")
    void step_terminalAdvance() {
        DebugSession s = baseSession("paused", 0);
        when(debugSessionMapper.findByPid(SESSION_PID)).thenReturn(s);
        when(automationMapper.findByPid(AUTOMATION_PID)).thenReturn(automationWithActions(1));
        when(actionExecutor.execute(any(), anyMap())).thenReturn("done");

        service.step(SESSION_PID);

        assertThat(s.getStatus()).isEqualTo("completed");
        assertThat(s.getCurrentActionIndex()).isEqualTo(1);
    }

    @Test
    @DisplayName("step short-circuits to completed when index already past end")
    void step_alreadyDone() {
        DebugSession s = baseSession("paused", 5);
        when(debugSessionMapper.findByPid(SESSION_PID)).thenReturn(s);
        when(automationMapper.findByPid(AUTOMATION_PID)).thenReturn(automationWithActions(1));

        DebugSessionDTO dto = service.step(SESSION_PID);

        assertThat(s.getStatus()).isEqualTo("completed");
        verify(actionExecutor, never()).execute(any(), anyMap());
        assertThat(dto.getStatus()).isEqualTo("completed");
    }

    @Test
    @DisplayName("step marks session failed when action throws and continueOnError=false")
    void step_failureStopsRun() {
        DebugSession s = baseSession("paused", 0);
        when(debugSessionMapper.findByPid(SESSION_PID)).thenReturn(s);
        Automation a = automationWithActions(2);
        a.getActions().get(0).setContinueOnError(false);
        when(automationMapper.findByPid(AUTOMATION_PID)).thenReturn(a);
        when(actionExecutor.execute(any(), anyMap())).thenThrow(new RuntimeException("boom"));

        service.step(SESSION_PID);

        assertThat(s.getStatus()).isEqualTo("failed");
        assertThat(s.getErrorMessage()).isEqualTo("boom");
    }

    @Test
    @DisplayName("step keeps going (paused) when action fails but continueOnError=true")
    void step_failureContinues() {
        DebugSession s = baseSession("paused", 0);
        when(debugSessionMapper.findByPid(SESSION_PID)).thenReturn(s);
        Automation a = automationWithActions(2);
        a.getActions().get(0).setContinueOnError(true);
        when(automationMapper.findByPid(AUTOMATION_PID)).thenReturn(a);
        when(actionExecutor.execute(any(), anyMap())).thenThrow(new RuntimeException("ignored"));

        service.step(SESSION_PID);

        assertThat(s.getStatus()).isEqualTo("paused");
        assertThat(s.getCurrentActionIndex()).isEqualTo(1);
    }

    @Test
    @DisplayName("step throws when session is not active")
    void step_notActive() {
        DebugSession s = baseSession("completed", 0);
        when(debugSessionMapper.findByPid(SESSION_PID)).thenReturn(s);
        assertThatThrownBy(() -> service.step(SESSION_PID))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("step throws when automation lookup returns null mid-flight")
    void step_automationMissing() {
        DebugSession s = baseSession("paused", 0);
        when(debugSessionMapper.findByPid(SESSION_PID)).thenReturn(s);
        when(automationMapper.findByPid(AUTOMATION_PID)).thenReturn(null);
        assertThatThrownBy(() -> service.step(SESSION_PID))
                .isInstanceOf(ValidationException.class);
    }

    // ------------------------------------------------------------------
    // continueExecution
    // ------------------------------------------------------------------

    @Test
    @DisplayName("continueExecution runs all remaining actions to completion")
    void continueExecution_runsToCompletion() {
        DebugSession s = baseSession("paused", 0);
        when(debugSessionMapper.findByPid(SESSION_PID)).thenReturn(s);
        when(automationMapper.findByPid(AUTOMATION_PID)).thenReturn(automationWithActions(3));
        when(actionExecutor.execute(any(), anyMap())).thenReturn("ok");

        DebugSessionDTO dto = service.continueExecution(SESSION_PID);

        assertThat(s.getStatus()).isEqualTo("completed");
        assertThat(s.getCurrentActionIndex()).isEqualTo(3);
        assertThat(s.getActionResults()).hasSize(3);
        assertThat(dto.getTotalActions()).isEqualTo(3);
        verify(actionExecutor, times(3)).execute(any(), anyMap());
    }

    @Test
    @DisplayName("continueExecution pauses at next breakpoint after running action")
    void continueExecution_hitsBreakpoint() {
        DebugSession s = baseSession("paused", 0);
        s.setBreakpoints(List.of(1));
        when(debugSessionMapper.findByPid(SESSION_PID)).thenReturn(s);
        when(automationMapper.findByPid(AUTOMATION_PID)).thenReturn(automationWithActions(3));
        when(actionExecutor.execute(any(), anyMap())).thenReturn("ok");

        service.continueExecution(SESSION_PID);

        assertThat(s.getStatus()).isEqualTo("paused");
        assertThat(s.getCurrentActionIndex()).isEqualTo(1);
        verify(actionExecutor, times(1)).execute(any(), anyMap());
    }

    @Test
    @DisplayName("continueExecution stops on failed action without continueOnError")
    void continueExecution_failsFast() {
        DebugSession s = baseSession("paused", 0);
        when(debugSessionMapper.findByPid(SESSION_PID)).thenReturn(s);
        Automation a = automationWithActions(3);
        a.getActions().get(0).setContinueOnError(false);
        when(automationMapper.findByPid(AUTOMATION_PID)).thenReturn(a);
        when(actionExecutor.execute(any(), anyMap())).thenThrow(new RuntimeException("nope"));

        service.continueExecution(SESSION_PID);

        assertThat(s.getStatus()).isEqualTo("failed");
        assertThat(s.getErrorMessage()).isEqualTo("nope");
    }

    @Test
    @DisplayName("continueExecution throws when not active")
    void continueExecution_notActive() {
        DebugSession s = baseSession("stopped", 0);
        when(debugSessionMapper.findByPid(SESSION_PID)).thenReturn(s);
        assertThatThrownBy(() -> service.continueExecution(SESSION_PID))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("continueExecution throws when automation null")
    void continueExecution_automationMissing() {
        DebugSession s = baseSession("paused", 0);
        when(debugSessionMapper.findByPid(SESSION_PID)).thenReturn(s);
        when(automationMapper.findByPid(AUTOMATION_PID)).thenReturn(null);
        assertThatThrownBy(() -> service.continueExecution(SESSION_PID))
                .isInstanceOf(ValidationException.class);
    }

    // ------------------------------------------------------------------
    // stop / restart / context / breakpoints / subscribe / getSession
    // ------------------------------------------------------------------

    @Test
    @DisplayName("stop transitions session to stopped, closes events stream")
    void stop_happyPath() {
        DebugSession s = baseSession("paused", 1);
        when(debugSessionMapper.findByPid(SESSION_PID)).thenReturn(s);
        when(automationMapper.findByPid(AUTOMATION_PID)).thenReturn(automationWithActions(3));

        DebugSessionDTO dto = service.stop(SESSION_PID);

        assertThat(s.getStatus()).isEqualTo("stopped");
        verify(eventPublisher).closeSession(SESSION_PID);
        assertThat(dto.getTotalActions()).isEqualTo(3);
    }

    @Test
    @DisplayName("stop throws when session is not active")
    void stop_notActive() {
        DebugSession s = baseSession("completed", 0);
        when(debugSessionMapper.findByPid(SESSION_PID)).thenReturn(s);
        assertThatThrownBy(() -> service.stop(SESSION_PID))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("stop missing session raises validation error")
    void stop_missingSession() {
        when(debugSessionMapper.findByPid("missing")).thenReturn(null);
        assertThatThrownBy(() -> service.stop("missing"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Debug session not found");
    }

    @Test
    @DisplayName("restart resets state and publishes paused event")
    void restart_resetsState() {
        DebugSession s = baseSession("failed", 2);
        s.setActionResults(new ArrayList<>(List.of(new com.auraboot.framework.automation.entity.AutomationLog.ActionResult())));
        s.setErrorMessage("prior failure");
        Map<String, Object> payload = new HashMap<>();
        payload.put("seed", 1);
        s.setTriggerPayload(payload);
        s.setRecordPid("rec-9");

        when(debugSessionMapper.findByPid(SESSION_PID)).thenReturn(s);
        when(automationMapper.findByPid(AUTOMATION_PID)).thenReturn(automationWithActions(2));

        DebugSessionDTO dto = service.restart(SESSION_PID);

        assertThat(s.getStatus()).isEqualTo("paused");
        assertThat(s.getCurrentActionIndex()).isZero();
        assertThat(s.getActionResults()).isEmpty();
        assertThat(s.getErrorMessage()).isNull();
        assertThat(s.getExecutionContext())
                .containsEntry("seed", 1)
                .containsEntry("recordPid", "rec-9")
                .containsEntry("automationPid", AUTOMATION_PID)
                .containsEntry("debugMode", true);
        assertThat(dto.getTotalActions()).isEqualTo(2);
    }

    @Test
    @DisplayName("getContext returns execution context map; null falls back to empty")
    void getContext_returnsMap() {
        DebugSession s = baseSession("paused", 0);
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("k", "v");
        s.setExecutionContext(ctx);
        when(debugSessionMapper.findByPid(SESSION_PID)).thenReturn(s);
        assertThat(service.getContext(SESSION_PID)).containsEntry("k", "v");

        DebugSession empty = baseSession("paused", 0);
        empty.setExecutionContext(null);
        when(debugSessionMapper.findByPid("empty")).thenReturn(empty);
        assertThat(service.getContext("empty")).isEmpty();
    }

    @Test
    @DisplayName("getSession reports total action count from automation")
    void getSession_returnsDtoWithTotals() {
        DebugSession s = baseSession("paused", 0);
        when(debugSessionMapper.findByPid(SESSION_PID)).thenReturn(s);
        when(automationMapper.findByPid(AUTOMATION_PID)).thenReturn(automationWithActions(4));

        DebugSessionDTO dto = service.getSession(SESSION_PID);

        assertThat(dto.getTotalActions()).isEqualTo(4);
        assertThat(dto.getStatus()).isEqualTo("paused");
    }

    @Test
    @DisplayName("getSession tolerates automation lookup returning null")
    void getSession_automationNullGivesZeroTotal() {
        DebugSession s = baseSession("paused", 0);
        when(debugSessionMapper.findByPid(SESSION_PID)).thenReturn(s);
        when(automationMapper.findByPid(AUTOMATION_PID)).thenReturn(null);

        DebugSessionDTO dto = service.getSession(SESSION_PID);

        assertThat(dto.getTotalActions()).isZero();
    }

    @Test
    @DisplayName("updateBreakpoints persists list and tolerates null input")
    void updateBreakpoints_handlesNull() {
        DebugSession s = baseSession("paused", 0);
        when(debugSessionMapper.findByPid(SESSION_PID)).thenReturn(s);
        when(automationMapper.findByPid(AUTOMATION_PID)).thenReturn(automationWithActions(2));

        DebugSessionDTO dto = service.updateBreakpoints(SESSION_PID, null);
        assertThat(s.getBreakpoints()).isEmpty();

        ArgumentCaptor<DebugSession> captor = ArgumentCaptor.forClass(DebugSession.class);
        verify(debugSessionMapper).updateSession(captor.capture());
        assertThat(captor.getValue().getBreakpoints()).isEmpty();
        assertThat(dto.getTotalActions()).isEqualTo(2);

        // Now with explicit list
        service.updateBreakpoints(SESSION_PID, List.of(0, 1));
        assertThat(s.getBreakpoints()).containsExactly(0, 1);
    }

    @Test
    @DisplayName("subscribeEvents validates session and delegates to publisher")
    void subscribeEvents_delegates() {
        DebugSession s = baseSession("paused", 0);
        when(debugSessionMapper.findByPid(SESSION_PID)).thenReturn(s);
        SseEmitter emitter = new SseEmitter();
        when(eventPublisher.subscribe(SESSION_PID)).thenReturn(emitter);

        SseEmitter actual = service.subscribeEvents(SESSION_PID);
        assertThat(actual).isSameAs(emitter);
    }

    @Test
    @DisplayName("subscribeEvents on missing session throws validation error")
    void subscribeEvents_missing() {
        when(debugSessionMapper.findByPid("ghost")).thenReturn(null);
        assertThatThrownBy(() -> service.subscribeEvents("ghost"))
                .isInstanceOf(ValidationException.class);
        verify(eventPublisher, never()).subscribe(anyString());
    }

    // ------------------------------------------------------------------
    // designer-flow derivation — debug a flowConfig-built automation whose
    // flat actions[] is empty (the designer stores steps in flowConfig nodes).
    // ------------------------------------------------------------------

    /** A designer-built automation: flowConfig trigger→a0→a1, flat actions[] empty. */
    private Automation designerAutomation(String... actionNodeTypes) {
        Automation a = new Automation();
        a.setPid(AUTOMATION_PID);
        a.setTenantId(1L);
        a.setActions(new ArrayList<>()); // designer flows leave the flat column empty
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        nodes.add(Map.of("id", "t", "type", "trigger-record-create",
                "data", Map.of("type", "trigger-record-create", "label", "OnCreate",
                        "config", Map.of("triggerType", "on_record_create", "modelCode", "e2et_order"))));
        String prev = "t";
        for (int i = 0; i < actionNodeTypes.length; i++) {
            String id = "a" + i;
            String nodeType = actionNodeTypes[i];
            String actionType = nodeType.substring("action-".length()).replace('-', '_');
            nodes.add(Map.of("id", id, "type", nodeType,
                    "data", Map.of("type", nodeType, "label", "step-" + i,
                            "config", Map.of("actionType", actionType))));
            edges.add(Map.of("id", "e" + i, "source", prev, "target", id));
            prev = id;
        }
        Map<String, Object> flowConfig = new HashMap<>();
        flowConfig.put("nodes", nodes);
        flowConfig.put("edges", edges);
        a.setFlowConfig(flowConfig);
        return a;
    }

    @Test
    @DisplayName("createSession derives the action count from flowConfig when flat actions[] is empty (designer flow)")
    void createSession_designerFlow_derivesActionsFromFlowConfig() {
        Automation designer = designerAutomation("action-update-record", "action-create-record");
        when(automationMapper.findByPid(AUTOMATION_PID)).thenReturn(designer);
        when(debugSessionMapper.findActiveByAutomationId(AUTOMATION_PID)).thenReturn(null);

        DebugSessionDTO dto = service.createSession(AUTOMATION_PID, new DebugSessionCreateRequest());

        assertThat(dto.getTotalActions())
                .as("debugger must see the 2 action nodes derived from flowConfig, not 0")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("step executes the flowConfig-derived actions in graph order (designer flow)")
    void step_designerFlow_executesDerivedActionsInGraphOrder() {
        DebugSession s = baseSession("paused", 0);
        when(debugSessionMapper.findByPid(SESSION_PID)).thenReturn(s);
        when(automationMapper.findByPid(AUTOMATION_PID))
                .thenReturn(designerAutomation("action-update-record", "action-create-record"));
        when(actionExecutor.execute(any(), anyMap())).thenReturn("ok");

        DebugSessionDTO step1 = service.step(SESSION_PID);
        assertThat(step1.getTotalActions()).isEqualTo(2);
        assertThat(s.getCurrentActionIndex()).isEqualTo(1);
        assertThat(s.getStatus()).isEqualTo("paused");
        assertThat(s.getActionResults()).hasSize(1);
        assertThat(s.getActionResults().get(0).getActionType()).isEqualTo("update_record");

        service.step(SESSION_PID); // second step → last action → completed
        assertThat(s.getStatus()).isEqualTo("completed");
        assertThat(s.getActionResults()).hasSize(2);
        assertThat(s.getActionResults().get(1).getActionType()).isEqualTo("create_record");
    }
}
