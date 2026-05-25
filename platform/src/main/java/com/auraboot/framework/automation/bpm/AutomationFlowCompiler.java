package com.auraboot.framework.automation.bpm;

import com.auraboot.framework.automation.entity.Automation;
import com.auraboot.framework.automation.entity.AutomationAction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Compiles an automation's visual {@code flowConfig} (@xyflow nodes/edges) into the
 * designer JSON shape consumed by {@code JsonToBpmnConverter}, so it can be deployed
 * and run on SmartEngine.
 *
 * <p>Mapping (DDR-2026-05-23 Option B / T2):
 * <ul>
 *   <li>{@code trigger-*} → {@code startEvent}</li>
 *   <li>{@code action-*} → generic {@code serviceTask} bound to
 *       {@link AutomationActionServiceTaskDelegate} (action config travels via the
 *       {@code _automation_actions} process variable, not BPMN attributes)</li>
 *   <li>{@code control-condition} → {@code exclusiveGateway}</li>
 * </ul>
 * A terminal {@code endEvent} is synthesized and wired to action nodes with no
 * outgoing edge. {@code JsonToBpmnConverter} is reused unchanged.
 */
@Component
public class AutomationFlowCompiler {

    public static final String PROCESS_KEY_PREFIX = "auto_";
    private static final String END_NODE_ID = "_end";

    private final ObjectMapper objectMapper;

    public AutomationFlowCompiler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Result of compiling an automation flow. */
    public record CompiledFlow(
            String processKey,
            JsonNode designerJson,
            Map<String, Object> actionsByNodeId) {}

    @SuppressWarnings("unchecked")
    public CompiledFlow compile(Automation automation) {
        List<Map<String, Object>> nodes;
        List<Map<String, Object>> edges;

        Map<String, Object> flowConfig = automation.getFlowConfig();
        if (flowConfig != null && flowConfig.get("nodes") instanceof List<?> fcNodes && !fcNodes.isEmpty()) {
            nodes = (List<Map<String, Object>>) flowConfig.get("nodes");
            edges = flowConfig.get("edges") instanceof List
                    ? (List<Map<String, Object>>) flowConfig.get("edges")
                    : new ArrayList<>();
        } else if (automation.getActions() != null && !automation.getActions().isEmpty()) {
            // No visual flow: synthesize a linear flow from triggerType + actions[] so
            // legacy actions-only automations also run on SmartEngine (prereq for cutover).
            Map<String, List<Map<String, Object>>> synth = synthesizeFromActions(automation);
            nodes = synth.get("nodes");
            edges = synth.get("edges");
        } else {
            throw new IllegalArgumentException(
                    "automation " + automation.getPid() + " has no flow or actions to compile");
        }

        String processKey = PROCESS_KEY_PREFIX + automation.getPid();
        ObjectNode root = objectMapper.createObjectNode();
        root.put("key", processKey);
        root.put("name", automation.getName() != null ? automation.getName() : processKey);
        ArrayNode outNodes = root.putArray("nodes");
        ArrayNode outEdges = root.putArray("edges");

        Map<String, Object> actionsByNodeId = new HashMap<>();
        Set<String> nodesWithOutgoing = edges.stream()
                .map(e -> String.valueOf(e.get("source")))
                .collect(Collectors.toSet());
        List<String> terminalActionIds = new ArrayList<>();

        for (Map<String, Object> node : nodes) {
            String id = String.valueOf(node.get("id"));
            String type = String.valueOf(node.get("type"));
            Map<String, Object> data = node.get("data") instanceof Map
                    ? (Map<String, Object>) node.get("data") : Map.of();
            Map<String, Object> config = data.get("config") instanceof Map
                    ? (Map<String, Object>) data.get("config") : Map.of();
            String label = data.get("label") != null ? String.valueOf(data.get("label")) : id;

            ObjectNode on = outNodes.addObject();
            on.put("id", id);
            ObjectNode od = on.putObject("data");
            od.put("label", label);

            if (type.startsWith("trigger")) {
                on.put("type", "startEvent");
            } else if (type.startsWith("action")) {
                on.put("type", "serviceTask");
                od.putObject("config").put("className", AutomationActionServiceTaskDelegate.BEAN_NAME);
                Map<String, Object> spec = new HashMap<>();
                spec.put("type", resolveActionType(type, config));
                spec.put("config", new HashMap<>(config));
                actionsByNodeId.put(id, spec);
                if (!nodesWithOutgoing.contains(id)) {
                    terminalActionIds.add(id);
                }
            } else if ("control-condition".equals(type)) {
                on.put("type", "exclusiveGateway");
            } else {
                throw new IllegalArgumentException(
                        "unsupported automation node type for compilation: " + type);
            }
        }

        for (Map<String, Object> edge : edges) {
            ObjectNode oe = outEdges.addObject();
            String source = String.valueOf(edge.get("source"));
            String target = String.valueOf(edge.get("target"));
            oe.put("id", String.valueOf(edge.getOrDefault("id", "e_" + source + "_" + target)));
            oe.put("source", source);
            oe.put("target", target);
            Map<String, Object> edata = edge.get("data") instanceof Map
                    ? (Map<String, Object>) edge.get("data") : null;
            if (edata != null) {
                ObjectNode oed = oe.putObject("data");
                Object cond = edata.get("condition");
                if (cond instanceof Map<?, ?> cm) {
                    Object ctype = cm.get("type");
                    oed.putObject("condition")
                            .put("type", ctype != null ? String.valueOf(ctype) : "expression")
                            .put("content", String.valueOf(cm.get("content")));
                } else if (cond instanceof String cs && !cs.isBlank()) {
                    oed.putObject("condition").put("type", "expression").put("content", cs);
                }
                if (Boolean.TRUE.equals(edata.get("isDefault"))) {
                    oed.put("isDefault", true);
                }
            }
        }

        if (!terminalActionIds.isEmpty()) {
            ObjectNode endNode = outNodes.addObject();
            endNode.put("id", END_NODE_ID).put("type", "endEvent");
            endNode.putObject("data").put("label", "End");
            for (String tid : terminalActionIds) {
                ObjectNode oe = outEdges.addObject();
                oe.put("id", "e_" + tid + "_" + END_NODE_ID);
                oe.put("source", tid);
                oe.put("target", END_NODE_ID);
            }
        }

        return new CompiledFlow(processKey, root, actionsByNodeId);
    }

    /** Synthesize a linear trigger→actions→end graph for actions-only automations. */
    private Map<String, List<Map<String, Object>>> synthesizeFromActions(Automation automation) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();

        String triggerId = "trigger_0";
        String triggerLabel = automation.getTriggerType() != null ? automation.getTriggerType() : "trigger";
        nodes.add(Map.of("id", triggerId, "type", "trigger",
                "data", Map.of("label", triggerLabel)));

        List<AutomationAction> actions = new ArrayList<>(automation.getActions());
        actions.sort(Comparator.comparingInt(a -> a.getSequence() != null ? a.getSequence() : 0));

        String prev = triggerId;
        for (int i = 0; i < actions.size(); i++) {
            AutomationAction action = actions.get(i);
            String id = "action_" + i;
            Map<String, Object> config = new HashMap<>();
            if (action.getConfig() != null) {
                config.putAll(action.getConfig());
            }
            config.put("actionType", action.getType());
            nodes.add(Map.of("id", id, "type", "action",
                    "data", Map.of(
                            "label", action.getType() != null ? action.getType() : id,
                            "config", config)));
            edges.add(Map.of("id", "e_" + prev + "_" + id, "source", prev, "target", id));
            prev = id;
        }

        Map<String, List<Map<String, Object>>> result = new HashMap<>();
        result.put("nodes", nodes);
        result.put("edges", edges);
        return result;
    }

    private String resolveActionType(String nodeType, Map<String, Object> config) {
        Object at = config.get("actionType");
        if (at != null) {
            return String.valueOf(at);
        }
        return nodeType.startsWith("action-")
                ? nodeType.substring("action-".length()).replace('-', '_')
                : nodeType;
    }
}
