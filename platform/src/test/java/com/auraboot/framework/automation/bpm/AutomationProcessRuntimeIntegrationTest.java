package com.auraboot.framework.automation.bpm;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.automation.entity.Automation;
import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.entity.AutomationLog;
import com.auraboot.framework.automation.executor.ActionExecutor;
import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end T2 slice 1c: compile an automation flow, deploy it to SmartEngine, and
 * run it in MEMORY mode — proving the full chain
 * {@code flowConfig → JsonToBpmnConverter → SmartEngine deploy → startProcess →
 * AutomationActionServiceTaskDelegate → CompositeActionExecutor → ActionExecutor}.
 *
 * <p>A marker {@link ActionExecutor} ({@code test_marker}) registered via
 * {@link MarkerConfig} records its invocation, so the assertion needs no model/seed
 * setup. Runs against a real (isolated) PostgreSQL + SmartEngine.
 */
@Slf4j
@DisplayName("Automation SmartEngine end-to-end (T2 slice 1c)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class AutomationProcessRuntimeIntegrationTest extends BaseIntegrationTest {

    /** Records marker-action invocations from inside the running process. */
    static final List<Map<String, Object>> MARKER_INVOCATIONS = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class MarkerConfig {
        @Bean
        ActionExecutor markerActionExecutor() {
            return new ActionExecutor() {
                @Override
                public boolean supports(String actionType) {
                    return "test_marker".equals(actionType);
                }

                @Override
                public Object execute(AutomationAction action, Map<String, Object> context) {
                    MARKER_INVOCATIONS.add(Map.of(
                            "type", action.getType(),
                            "config", action.getConfig() != null ? action.getConfig() : Map.of(),
                            "recordPid", String.valueOf(context.get("recordPid")),
                            "item", String.valueOf(context.get("item"))));
                    return Map.of("ok", true);
                }
            };
        }
    }

    @Autowired
    private AutomationProcessRuntime runtime;

    @Autowired
    private com.auraboot.framework.automation.trigger.AutomationTriggerService automationTriggerService;

    @Autowired
    private com.auraboot.framework.automation.mapper.AutomationLogMapper automationLogMapper;

    @BeforeEach
    void clearInvocations() {
        MARKER_INVOCATIONS.clear();
    }

    private Automation markerAutomation() {
        Automation a = new Automation();
        a.setPid("ITAUTO" + System.currentTimeMillis());
        a.setName("E2E marker automation");
        a.setTenantId(MetaContext.getCurrentTenantId());
        a.setFlowConfig(Map.of(
                "nodes", List.of(
                        Map.of("id", "t1", "type", "trigger-record-create",
                                "data", Map.of("label", "On create", "config", Map.of())),
                        Map.of("id", "a1", "type", "action-test-marker",
                                "data", Map.of("label", "Marker",
                                        "config", Map.of("actionType", "test_marker", "note", "hi")))),
                "edges", List.of(
                        Map.of("id", "e1", "source", "t1", "target", "a1"))));
        a.setEnabled(true);
        return a;
    }

    @Test
    void deploy_thenRun_firesActionViaSmartEngine() {
        Automation automation = markerAutomation();

        String processKey = runtime.deploy(automation);
        assertThat(processKey).isEqualTo("auto_" + automation.getPid());

        runtime.run(automation, "rec-1", Map.of("event", "create"));

        assertThat(MARKER_INVOCATIONS)
                .as("marker action should have fired once via the SmartEngine serviceTask delegate")
                .hasSize(1);
        assertThat(MARKER_INVOCATIONS.get(0)).containsEntry("recordPid", "rec-1");
    }

    @Test
    void executeAutomation_splicesFlowConfigOntoSmartEngine() {
        Automation automation = markerAutomation();
        runtime.deploy(automation);

        // Go through the real trigger entry point: executeAutomation must route a
        // flowConfig automation to the SmartEngine runtime (not the flat actions loop).
        automationTriggerService.executeAutomation(automation, "rec-2", Map.of("event", "create"));

        assertThat(MARKER_INVOCATIONS)
                .as("flow automation should run via executeAutomation → SmartEngine splice")
                .hasSize(1);
        assertThat(MARKER_INVOCATIONS.get(0)).containsEntry("recordPid", "rec-2");
    }

    private Automation conditionalMarkerAutomation() {
        Automation a = new Automation();
        a.setPid("ITCOND" + System.currentTimeMillis());
        a.setName("E2E conditional automation");
        a.setTenantId(MetaContext.getCurrentTenantId());
        a.setFlowConfig(Map.of(
                "nodes", List.of(
                        Map.of("id", "t1", "type", "trigger-record-create",
                                "data", Map.of("label", "On create", "config", Map.of())),
                        Map.of("id", "gw", "type", "control-condition",
                                "data", Map.of("label", "Amount?", "config", Map.of())),
                        Map.of("id", "aHigh", "type", "action-test-marker",
                                "data", Map.of("label", "High",
                                        "config", Map.of("actionType", "test_marker", "branch", "high"))),
                        Map.of("id", "aLow", "type", "action-test-marker",
                                "data", Map.of("label", "Low",
                                        "config", Map.of("actionType", "test_marker", "branch", "low")))),
                "edges", List.of(
                        Map.of("id", "e1", "source", "t1", "target", "gw"),
                        Map.of("id", "e2", "source", "gw", "target", "aHigh",
                                "data", Map.of("condition",
                                        Map.of("type", "expression", "content", "amount > 1000"))),
                        Map.of("id", "e3", "source", "gw", "target", "aLow",
                                "data", Map.of("condition",
                                        Map.of("type", "expression", "content", "amount <= 1000"))))));
        a.setEnabled(true);
        return a;
    }

    private Automation loopMarkerAutomation() {
        Automation a = new Automation();
        a.setPid("ITLOOP" + System.currentTimeMillis());
        a.setName("E2E loop automation");
        a.setTenantId(MetaContext.getCurrentTenantId());
        a.setFlowConfig(Map.of(
                "nodes", List.of(
                        Map.of("id", "t1", "type", "trigger-record-create",
                                "data", Map.of("label", "On create", "config", Map.of())),
                        Map.of("id", "loop", "type", "control-loop",
                                "data", Map.of("label", "For each item",
                                        "config", Map.of("collection", "items", "itemVariable", "item"))),
                        Map.of("id", "body", "type", "action-test-marker",
                                "data", Map.of("label", "Marker",
                                        "config", Map.of("actionType", "test_marker")))),
                "edges", List.of(
                        Map.of("id", "e1", "source", "t1", "target", "loop"),
                        Map.of("id", "e2", "source", "loop", "target", "body"))));
        a.setEnabled(true);
        return a;
    }

    @Test
    void controlLoop_expandsBodyActionPerCollectionItem_onSmartEngine() {
        Automation automation = loopMarkerAutomation();
        runtime.deploy(automation);

        // control-loop over a 3-element collection must expand the body action into
        // 3 multi-instance executions on SmartEngine (the bridge-delegate serviceTask
        // is driven by the compiled <multiInstanceLoopCharacteristics>).
        runtime.run(automation, "rec-loop",
                Map.of("event", "create", "items", List.of("a", "b", "c")));

        assertThat(MARKER_INVOCATIONS)
                .as("loop body should fire once per collection item")
                .hasSize(3);
        assertThat(MARKER_INVOCATIONS)
                .extracting(m -> m.get("item"))
                .as("each iteration binds its element under the loop itemVariable")
                .containsExactlyInAnyOrder("a", "b", "c");
    }

    @Test
    void conditionGateway_routesOnlyMatchingBranch_onSmartEngine() {
        Automation automation = conditionalMarkerAutomation();
        runtime.deploy(automation);

        // amount=2000 -> only the "amount > 1000" branch action must fire (P0-2 fixed:
        // the SmartEngine exclusive gateway gates downstream; the false branch does not run).
        runtime.run(automation, "rec-3", Map.of("event", "create", "amount", 2000));

        assertThat(MARKER_INVOCATIONS)
                .as("only the matching gateway branch should fire")
                .hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> firedConfig =
                (Map<String, Object>) MARKER_INVOCATIONS.get(0).get("config");
        assertThat(firedConfig).containsEntry("branch", "high");
    }

    // ---- P0-5 follow-ups: execution-chain edge cases ----

    @Test
    void controlLoop_emptyCollection_doesNotInvokeBody_onSmartEngine() {
        Automation automation = loopMarkerAutomation();
        runtime.deploy(automation);

        // Empty items collection — the delegate-internal for-each must elide the body
        // entirely (no marker invocation), and the process must still complete normally.
        runtime.run(automation, "rec-loop-empty",
                Map.of("event", "create", "items", List.of()));

        assertThat(MARKER_INVOCATIONS)
                .as("loop body must not fire when collection is empty")
                .isEmpty();
    }

    @Test
    void controlLoop_missingCollectionVariable_doesNotInvokeBody_onSmartEngine() {
        Automation automation = loopMarkerAutomation();
        runtime.deploy(automation);

        // "items" variable absent from payload entirely — must degrade gracefully.
        runtime.run(automation, "rec-loop-missing", Map.of("event", "create"));

        assertThat(MARKER_INVOCATIONS)
                .as("loop body must not fire when the collection variable is absent")
                .isEmpty();
    }

    /** Fails on every invocation — used to drive the error/state-log paths. */
    @TestConfiguration
    static class FailingMarkerConfig {
        @Bean
        ActionExecutor failingActionExecutor() {
            return new ActionExecutor() {
                @Override
                public boolean supports(String actionType) {
                    return "test_failure".equals(actionType);
                }

                @Override
                public Object execute(AutomationAction action, Map<String, Object> context) {
                    throw new RuntimeException("synthetic action failure for P0-5 test");
                }
            };
        }
    }

    private Automation failingActionAutomation(String triggerType) {
        Automation a = new Automation();
        a.setPid("ITFAIL" + System.currentTimeMillis());
        a.setName("E2E failing automation");
        a.setTriggerType(triggerType);
        a.setTenantId(MetaContext.getCurrentTenantId());
        a.setFlowConfig(Map.of(
                "nodes", List.of(
                        Map.of("id", "t1", "type", "trigger-record-create",
                                "data", Map.of("label", "On create", "config", Map.of())),
                        Map.of("id", "a1", "type", "action-test-failure",
                                "data", Map.of("label", "Failure",
                                        "config", Map.of("actionType", "test_failure")))),
                "edges", List.of(
                        Map.of("id", "e1", "source", "t1", "target", "a1"))));
        a.setEnabled(true);
        return a;
    }

    @Test
    void executeAutomation_actionThrows_recordsFailedLogWithErrorMessage() {
        // Verify the error/state-log path: when an action raises inside SmartEngine,
        // executeAutomation must catch it, stamp the AutomationLog status=FAILED and
        // persist the error message — no silent loss, no rollback that wipes the log row.
        Automation automation = failingActionAutomation("on_record_create");
        runtime.deploy(automation);

        AutomationLog logEntry = automationTriggerService.executeAutomation(
                automation, "rec-fail", Map.of("event", "create"));

        assertThat(logEntry.getStatus()).isEqualTo(StatusConstants.FAILED);
        assertThat(logEntry.getErrorMessage())
                .as("error message must surface the action failure")
                .contains("synthetic action failure");
        assertThat(logEntry.getCompletedAt())
                .as("log must be marked completed even on failure")
                .isNotNull();

        // Persisted log row must reflect the same status (catches @Transactional rollback
        // bugs that would silently drop the FAILED row).
        AutomationLog persisted = automationLogMapper.selectById(logEntry.getId());
        assertThat(persisted)
                .as("log row must survive the failed run")
                .isNotNull();
        assertThat(persisted.getStatus()).isEqualTo(StatusConstants.FAILED);
    }

    @Test
    void executeAutomation_manualTrigger_stampsTriggerTypeOnLog() {
        // P0-5 coverage for the "manual" trigger entry: AutomationTriggerService.executeAutomation
        // is the unified synchronous entry used by manual runs (UI run-now buttons) and by
        // async fan-out from event/schedule triggers. Verify the trigger type is preserved
        // on the log row exactly as configured.
        Automation automation = markerAutomation();
        automation.setTriggerType("manual");
        runtime.deploy(automation);

        AutomationLog logEntry = automationTriggerService.executeAutomation(
                automation, "rec-manual", Map.of("event", "manual_run"));

        assertThat(logEntry.getStatus()).isEqualTo("success");
        assertThat(logEntry.getTriggerType()).isEqualTo("manual");
        assertThat(MARKER_INVOCATIONS)
                .as("manual trigger must still drive the SmartEngine flow")
                .hasSize(1);
    }
}
