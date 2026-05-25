package com.auraboot.framework.automation.bpm;

import com.auraboot.framework.automation.entity.Automation;
import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.bpm.converter.JsonToBpmnConverter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AutomationFlowCompiler}: flowConfig → designer JSON mapping,
 * and that the emitted designer JSON compiles to valid SmartEngine BPMN via the
 * reused {@link JsonToBpmnConverter}.
 */
class AutomationFlowCompilerTest {

    private final AutomationFlowCompiler compiler = new AutomationFlowCompiler(new ObjectMapper());

    private Automation linearNotificationAutomation() {
        Automation a = new Automation();
        a.setPid("AUTO123");
        a.setName("New lead notification");
        a.setFlowConfig(Map.of(
                "nodes", List.of(
                        Map.of("id", "t1", "type", "trigger-record-create",
                                "data", Map.of("label", "On create",
                                        "config", Map.of("modelCode", "crm_lead"))),
                        Map.of("id", "a1", "type", "action-send-notification",
                                "data", Map.of("label", "Notify",
                                        "config", Map.of("actionType", "send_notification",
                                                "title", "New lead")))),
                "edges", List.of(
                        Map.of("id", "e1", "source", "t1", "target", "a1"))));
        return a;
    }

    @Test
    void compile_mapsTriggerToStartEvent_actionToServiceTask_andSynthesizesEnd() {
        AutomationFlowCompiler.CompiledFlow out = compiler.compile(linearNotificationAutomation());

        assertThat(out.processKey()).isEqualTo("auto_AUTO123");

        JsonNode nodes = out.designerJson().get("nodes");
        Map<String, String> typeById = new java.util.HashMap<>();
        nodes.forEach(n -> typeById.put(n.get("id").asText(), n.get("type").asText()));
        assertThat(typeById).containsEntry("t1", "startEvent");
        assertThat(typeById).containsEntry("a1", "serviceTask");
        assertThat(typeById).containsEntry("_end", "endEvent");

        // action serviceTask is bound to the bridge delegate
        JsonNode action = nodes.get(1);
        assertThat(action.path("data").path("config").path("className").asText())
                .isEqualTo(AutomationActionServiceTaskDelegate.BEAN_NAME);

        // an end edge was synthesized from the terminal action
        boolean hasEndEdge = false;
        for (JsonNode e : out.designerJson().get("edges")) {
            if ("a1".equals(e.get("source").asText()) && "_end".equals(e.get("target").asText())) {
                hasEndEdge = true;
            }
        }
        assertThat(hasEndEdge).isTrue();
    }

    @Test
    void compile_extractsActionSpecByNodeId() {
        AutomationFlowCompiler.CompiledFlow out = compiler.compile(linearNotificationAutomation());

        assertThat(out.actionsByNodeId()).containsKey("a1");
        @SuppressWarnings("unchecked")
        Map<String, Object> spec = (Map<String, Object>) out.actionsByNodeId().get("a1");
        assertThat(spec.get("type")).isEqualTo("send_notification");
        @SuppressWarnings("unchecked")
        Map<String, Object> cfg = (Map<String, Object>) spec.get("config");
        assertThat(cfg).containsEntry("title", "New lead");
    }

    private Automation conditionalAutomation() {
        Automation a = new Automation();
        a.setPid("AUTOCOND");
        a.setName("Conditional automation");
        a.setFlowConfig(Map.of(
                "nodes", List.of(
                        Map.of("id", "t1", "type", "trigger-record-create",
                                "data", Map.of("label", "On create", "config", Map.of())),
                        Map.of("id", "gw", "type", "control-condition",
                                "data", Map.of("label", "Amount?", "config", Map.of())),
                        Map.of("id", "aHigh", "type", "action-send-notification",
                                "data", Map.of("label", "High",
                                        "config", Map.of("actionType", "send_notification"))),
                        Map.of("id", "aLow", "type", "action-send-notification",
                                "data", Map.of("label", "Low",
                                        "config", Map.of("actionType", "send_notification")))),
                "edges", List.of(
                        Map.of("id", "e1", "source", "t1", "target", "gw"),
                        Map.of("id", "e2", "source", "gw", "target", "aHigh",
                                "data", Map.of("condition",
                                        Map.of("type", "expression", "content", "amount > 1000"))),
                        Map.of("id", "e3", "source", "gw", "target", "aLow",
                                "data", Map.of("condition",
                                        Map.of("type", "expression", "content", "amount <= 1000"))))));
        return a;
    }

    @Test
    void compile_conditionNodeToExclusiveGateway_convertsToValidBpmn() {
        AutomationFlowCompiler.CompiledFlow out = compiler.compile(conditionalAutomation());

        boolean hasGateway = false;
        for (JsonNode n : out.designerJson().get("nodes")) {
            if ("gw".equals(n.get("id").asText())) {
                hasGateway = "exclusiveGateway".equals(n.get("type").asText());
            }
        }
        assertThat(hasGateway).as("control-condition maps to exclusiveGateway").isTrue();

        String bpmn = new JsonToBpmnConverter(new ObjectMapper(), null)
                .convertFromJsonNode(out.designerJson());
        assertThat(bpmn).contains("exclusiveGateway");
        assertThat(bpmn).contains("conditionExpression");
        assertThat(bpmn).contains("amount");
    }

    @Test
    void compile_actionsOnlyAutomation_synthesizesLinearFlow() {
        Automation a = new Automation();
        a.setPid("AUTOFLAT");
        a.setName("Legacy actions-only");
        a.setTriggerType("on_record_create");
        a.setActions(new java.util.ArrayList<>(List.of(
                AutomationAction.builder().type("send_notification").sequence(1)
                        .config(Map.of("title", "hi")).build())));
        // no flowConfig

        AutomationFlowCompiler.CompiledFlow out = compiler.compile(a);

        Map<String, String> typeById = new java.util.HashMap<>();
        out.designerJson().get("nodes").forEach(n -> typeById.put(n.get("id").asText(), n.get("type").asText()));
        assertThat(typeById).containsEntry("trigger_0", "startEvent");
        assertThat(typeById).containsEntry("action_0", "serviceTask");
        assertThat(typeById).containsEntry("_end", "endEvent");

        @SuppressWarnings("unchecked")
        Map<String, Object> spec = (Map<String, Object>) out.actionsByNodeId().get("action_0");
        assertThat(spec.get("type")).isEqualTo("send_notification");

        String bpmn = new JsonToBpmnConverter(new ObjectMapper(), null)
                .convertFromJsonNode(out.designerJson());
        assertThat(bpmn).contains("startEvent");
        assertThat(bpmn).contains(AutomationActionServiceTaskDelegate.BEAN_NAME);
    }

    private Automation loopAutomation(String itemVariable) {
        java.util.Map<String, Object> loopConfig = new java.util.HashMap<>();
        loopConfig.put("collection", "items");
        if (itemVariable != null) {
            loopConfig.put("itemVariable", itemVariable);
        }
        Automation a = new Automation();
        a.setPid("AUTOLOOP");
        a.setName("Loop automation");
        a.setFlowConfig(Map.of(
                "nodes", List.of(
                        Map.of("id", "t1", "type", "trigger-record-create",
                                "data", Map.of("label", "On create", "config", Map.of())),
                        Map.of("id", "loop", "type", "control-loop",
                                "data", Map.of("label", "For each", "config", loopConfig)),
                        Map.of("id", "body", "type", "action-send-notification",
                                "data", Map.of("label", "Notify",
                                        "config", Map.of("actionType", "send_notification",
                                                "title", "Item")))),
                "edges", List.of(
                        Map.of("id", "e1", "source", "t1", "target", "loop"),
                        Map.of("id", "e2", "source", "loop", "target", "body"))));
        return a;
    }

    @Test
    void compile_loopNode_isElided_andIncomingEdgeRedirectedToBody() {
        AutomationFlowCompiler.CompiledFlow out = compiler.compile(loopAutomation("row"));

        // loop node is elided — only trigger/body/end remain
        Map<String, String> typeById = new java.util.HashMap<>();
        out.designerJson().get("nodes").forEach(n ->
                typeById.put(n.get("id").asText(), n.get("type").asText()));
        assertThat(typeById).doesNotContainKey("loop");
        assertThat(typeById).containsEntry("t1", "startEvent");
        assertThat(typeById).containsEntry("body", "serviceTask");
        assertThat(typeById).containsEntry("_end", "endEvent");

        // incoming edge t1->loop redirected to t1->body; no edge touches "loop"
        boolean hasRedirected = false;
        for (JsonNode e : out.designerJson().get("edges")) {
            String s = e.get("source").asText();
            String t = e.get("target").asText();
            assertThat(s).as("no edge sources from elided loop").isNotEqualTo("loop");
            assertThat(t).as("no edge targets elided loop").isNotEqualTo("loop");
            if ("t1".equals(s) && "body".equals(t)) {
                hasRedirected = true;
            }
        }
        assertThat(hasRedirected).as("t1->loop is redirected to t1->body").isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void compile_loopNode_injectsLoopDescriptorOnBodyActionSpec() {
        AutomationFlowCompiler.CompiledFlow out = compiler.compile(loopAutomation("row"));

        // The loop becomes a descriptor on the body action's spec (carried via the
        // ACTIONS_VAR process variable). SmartEngine MI does not expand serviceTasks
        // (userTask-only), so iteration is performed inside the bridge delegate.
        Map<String, Object> spec = (Map<String, Object>) out.actionsByNodeId().get("body");
        assertThat(spec).isNotNull();
        Map<String, Object> loop = (Map<String, Object>) spec.get("loop");
        assertThat(loop).isNotNull();
        assertThat(loop).containsEntry("collection", "items");
        assertThat(loop).containsEntry("itemVariable", "row");

        // The emitted body is a plain serviceTask — no multiInstance leaks into the BPMN.
        JsonNode body = null;
        for (JsonNode n : out.designerJson().get("nodes")) {
            if ("body".equals(n.get("id").asText())) {
                body = n;
            }
        }
        assertThat(body).isNotNull();
        assertThat(body.path("data").path("config").path("multiInstance").isMissingNode()).isTrue();
        assertThat(body.path("data").path("config").path("className").asText())
                .isEqualTo(AutomationActionServiceTaskDelegate.BEAN_NAME);
    }

    @Test
    @SuppressWarnings("unchecked")
    void compile_loopNode_defaultsItemVariableToItem() {
        AutomationFlowCompiler.CompiledFlow out = compiler.compile(loopAutomation(null));

        Map<String, Object> spec = (Map<String, Object>) out.actionsByNodeId().get("body");
        Map<String, Object> loop = (Map<String, Object>) spec.get("loop");
        assertThat(loop).containsEntry("itemVariable", "item");
    }

    @Test
    void compile_loopNode_emitsPlainServiceTaskBpmn() {
        AutomationFlowCompiler.CompiledFlow out = compiler.compile(loopAutomation("row"));

        String bpmn = new JsonToBpmnConverter(new ObjectMapper(), null)
                .convertFromJsonNode(out.designerJson());
        // The loop is delegate-internal: the BPMN body is a plain serviceTask with no
        // multi-instance characteristics (the engine has no serviceTask MI executor).
        assertThat(bpmn).contains("serviceTask");
        assertThat(bpmn).doesNotContain("multiInstanceLoopCharacteristics");
    }

    @Test
    void compiledDesignerJson_convertsToValidSmartEngineBpmn() {
        AutomationFlowCompiler.CompiledFlow out = compiler.compile(linearNotificationAutomation());

        // SmartEngine is optional in the converter — null is fine for pure XML generation.
        JsonToBpmnConverter converter = new JsonToBpmnConverter(new ObjectMapper(), null);
        String bpmn = converter.convertFromJsonNode(out.designerJson());

        assertThat(bpmn).contains("startEvent");
        assertThat(bpmn).contains("serviceTask");
        assertThat(bpmn).contains(AutomationActionServiceTaskDelegate.BEAN_NAME);
        assertThat(bpmn).contains("endEvent");
    }
}
