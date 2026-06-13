package com.auraboot.framework.automation;

import com.auraboot.framework.automation.dto.AutomationCreateRequest;
import com.auraboot.framework.automation.dto.AutomationDTO;
import com.auraboot.framework.automation.service.AutomationService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof for the visual-designer trigger-field derivation (the P0 fix).
 *
 * <p>The visual designer's save path POSTs ONLY {@code {name, description, flowConfig}};
 * it does NOT send flat {@code triggerType}/{@code modelCode}. Before
 * {@link com.auraboot.framework.automation.service.AutomationFlowTriggerDeriver},
 * {@code create()} persisted null trigger columns — which violate the
 * {@code ab_automation} NOT NULL constraints on {@code model_code} / {@code trigger_type},
 * so a designer-only automation could not even be saved (and, had the columns been
 * nullable, would never have matched the event-dispatch query
 * {@code findEnabledByModelCodeAndTriggerType}). The deriver now reads the trigger
 * node's {@code data.config} and populates {@code triggerType}/{@code modelCode}/
 * {@code triggerConfig} before insert.
 *
 * <p>Runs against a real PostgreSQL (commits, no rollback — mirrors
 * {@link AutomationServiceIntegrationTest}). Each test uses a unique run id so reruns
 * against a shared database do not collide.
 */
@DisplayName("Automation flowConfig trigger derivation (designer P0 fix)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class AutomationFlowConfigDerivationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AutomationService automationService;

    private final String runId = String.valueOf(System.currentTimeMillis());

    /**
     * Build a designer-style save payload: {@code name} + {@code flowConfig} only.
     * Trigger parameters live inside the trigger node's {@code data.config} — exactly
     * what the visual designer persists. {@code triggerType}/{@code modelCode} are
     * deliberately NOT set on the request, so the only way the persisted automation
     * gets them is via the deriver.
     */
    private AutomationCreateRequest designerRequest(String name, String triggerNodeType,
                                                    Map<String, Object> triggerNodeConfig) {
        Map<String, Object> triggerNode = Map.of(
                "id", "t1",
                "type", triggerNodeType,
                "data", Map.of("label", "Trigger", "config", triggerNodeConfig));
        Map<String, Object> actionNode = Map.of(
                "id", "a1",
                "type", "action-send-notification",
                "data", Map.of("label", "Notify", "config", Map.of("actionType", "send_notification")));
        Map<String, Object> edge = Map.of("id", "e1", "source", "t1", "target", "a1");

        AutomationCreateRequest req = new AutomationCreateRequest();
        req.setName(name);
        req.setFlowConfig(Map.of("nodes", List.of(triggerNode, actionNode), "edges", List.of(edge)));
        req.setEnabled(false);
        // triggerType / modelCode intentionally left null — mirrors the real designer save.
        return req;
    }

    @Test
    @DisplayName("designer record-create flow (no flat fields) saves and derives trigger columns")
    void designerRecordCreate_savesAndDerivesTriggerColumns() {
        String model = "derive-model-" + runId;
        AutomationCreateRequest req = designerRequest(
                "DeriveRecordCreate-" + runId,
                "trigger-record-create",
                Map.of("triggerType", "on_record_create", "modelCode", model));

        // Pre-fix, this call threw a NOT NULL violation on model_code / trigger_type.
        AutomationDTO created = automationService.create(req);
        assertThat(created.getPid()).isNotNull();

        AutomationDTO reloaded = automationService.findByPid(created.getPid());
        assertThat(reloaded.getTriggerType()).isEqualTo("on_record_create");
        assertThat(reloaded.getModelCode()).isEqualTo(model);

        // The derived automation is now discoverable by the same modelCode lookup the
        // event dispatch path (findEnabledByModelCodeAndTriggerType) relies on.
        assertThat(automationService.getByModelCode(model))
                .anyMatch(a -> created.getPid().equals(a.getPid()));
    }

    @Test
    @DisplayName("designer bpm-event flow derives modelCode (=processKey) and eventTypes")
    void designerBpmEvent_derivesModelCodeAndEventTypes() {
        String processKey = "derive-proc-" + runId;
        AutomationCreateRequest req = designerRequest(
                "DeriveBpmEvent-" + runId,
                "trigger-bpm-event",
                Map.of(
                        "triggerType", "on_bpm_event",
                        "modelCode", processKey,
                        "eventTypes", List.of("process_started", "task_completed")));

        AutomationDTO created = automationService.create(req);
        assertThat(created.getPid()).isNotNull();

        AutomationDTO reloaded = automationService.findByPid(created.getPid());
        assertThat(reloaded.getTriggerType()).isEqualTo("on_bpm_event");
        assertThat(reloaded.getModelCode()).isEqualTo(processKey);
        assertThat(reloaded.getTriggerConfig()).isNotNull();
        assertThat(reloaded.getTriggerConfig().getEventTypes())
                .containsExactlyInAnyOrder("process_started", "task_completed");
    }

    @Test
    @DisplayName("designer inactivity flow derives modelCode and inactivity trigger config")
    void designerInactivity_derivesModelCodeAndTriggerConfig() {
        String modelCode = "derive-inactive-model-" + runId;
        AutomationCreateRequest req = designerRequest(
                "DeriveInactivity-" + runId,
                "trigger-inactivity",
                Map.of(
                        "triggerType", "on_inactivity",
                        "modelCode", modelCode,
                        "inactivityHours", 24,
                        "inactivityField", "last_seen_at",
                        "stateField", "status",
                        "inactivityStates", List.of("open", "pending")));

        AutomationDTO created = automationService.create(req);
        assertThat(created.getPid()).isNotNull();

        AutomationDTO reloaded = automationService.findByPid(created.getPid());
        assertThat(reloaded.getTriggerType()).isEqualTo("on_inactivity");
        assertThat(reloaded.getModelCode()).isEqualTo(modelCode);
        assertThat(reloaded.getTriggerConfig()).isNotNull();
        assertThat(reloaded.getTriggerConfig().getInactivityHours()).isEqualTo(24);
        assertThat(reloaded.getTriggerConfig().getInactivityField()).isEqualTo("last_seen_at");
        assertThat(reloaded.getTriggerConfig().getStateField()).isEqualTo("status");
        assertThat(reloaded.getTriggerConfig().getInactivityStates())
                .containsExactlyInAnyOrder("open", "pending");
    }
}
