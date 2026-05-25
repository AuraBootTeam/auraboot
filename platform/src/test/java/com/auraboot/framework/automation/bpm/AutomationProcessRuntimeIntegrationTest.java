package com.auraboot.framework.automation.bpm;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.automation.entity.Automation;
import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.executor.ActionExecutor;
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
                            "recordId", String.valueOf(context.get("recordId"))));
                    return Map.of("ok", true);
                }
            };
        }
    }

    @Autowired
    private AutomationProcessRuntime runtime;

    @Autowired
    private com.auraboot.framework.automation.trigger.AutomationTriggerService automationTriggerService;

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
        assertThat(MARKER_INVOCATIONS.get(0)).containsEntry("recordId", "rec-1");
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
        assertThat(MARKER_INVOCATIONS.get(0)).containsEntry("recordId", "rec-2");
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
}
