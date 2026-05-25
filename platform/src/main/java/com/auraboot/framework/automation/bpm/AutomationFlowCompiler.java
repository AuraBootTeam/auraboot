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

        // Rewrite control-loop nodes: elide each loop and attach a loop descriptor to its
        // body action. SmartEngine multi-instance is userTask-only (no serviceTask collection
        // executor), so the body serviceTask iterates inside AutomationActionServiceTaskDelegate
        // rather than as a BPMN <multiInstanceLoopCharacteristics>.
        LoopRewrite rewrite = rewriteControlLoops(nodes, edges);
        nodes = rewrite.nodes();
        edges = rewrite.edges();
        Map<String, Map<String, Object>> loopByBodyId = rewrite.loopByBodyId();

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
                Map<String, Object> loop = loopByBodyId.get(id);
                if (loop != null) {
                    spec.put("loop", loop);
                }
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

    /** Outcome of rewriting control-loop nodes: loop nodes elided, edges redirected,
     *  and the loop descriptor ({@code collection} + {@code itemVariable}) to attach to
     *  each loop body action spec, keyed by body node id. */
    private record LoopRewrite(
            List<Map<String, Object>> nodes,
            List<Map<String, Object>> edges,
            Map<String, Map<String, Object>> loopByBodyId) {}

    /**
     * Rewrite each {@code control-loop} node into a loop descriptor on its single body
     * action: elide the loop node, redirect its incoming edges to the body, drop its
     * outgoing edge, and record {@code {collection, itemVariable}} keyed by body node id.
     *
     * <p>The descriptor is honored at runtime by {@link AutomationActionServiceTaskDelegate}
     * (delegate-internal for-each), because this SmartEngine fork only expands multi-instance
     * for {@code userTask} (会签), not collection-driven {@code serviceTask}s.
     *
     * <p>Scope (T2): a loop wraps exactly one downstream action (single-action loop).
     */
    @SuppressWarnings("unchecked")
    private LoopRewrite rewriteControlLoops(
            List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        Map<String, Map<String, Object>> loopNodes = new HashMap<>();
        for (Map<String, Object> n : nodes) {
            if ("control-loop".equals(String.valueOf(n.get("type")))) {
                loopNodes.put(String.valueOf(n.get("id")), n);
            }
        }
        if (loopNodes.isEmpty()) {
            return new LoopRewrite(nodes, edges, Map.of());
        }

        Map<String, String> loopToBody = new HashMap<>();
        Map<String, Map<String, Object>> loopByBodyId = new HashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : loopNodes.entrySet()) {
            String loopId = entry.getKey();
            List<String> bodies = edges.stream()
                    .filter(e -> loopId.equals(String.valueOf(e.get("source"))))
                    .map(e -> String.valueOf(e.get("target")))
                    .collect(Collectors.toList());
            if (bodies.size() != 1) {
                throw new IllegalArgumentException("control-loop node " + loopId
                        + " must have exactly one outgoing edge (body), found " + bodies.size());
            }
            String bodyId = bodies.get(0);
            loopToBody.put(loopId, bodyId);

            Map<String, Object> data = entry.getValue().get("data") instanceof Map
                    ? (Map<String, Object>) entry.getValue().get("data") : Map.of();
            Map<String, Object> cfg = data.get("config") instanceof Map
                    ? (Map<String, Object>) data.get("config") : Map.of();
            Object collection = cfg.get("collection");
            if (collection == null || String.valueOf(collection).isBlank()) {
                throw new IllegalArgumentException(
                        "control-loop node " + loopId + " missing 'collection' in config");
            }
            Object itemVar = cfg.get("itemVariable");
            String itemVariable = itemVar != null && !String.valueOf(itemVar).isBlank()
                    ? String.valueOf(itemVar) : "item";

            Map<String, Object> loop = new HashMap<>();
            loop.put("collection", String.valueOf(collection));
            loop.put("itemVariable", itemVariable);
            loopByBodyId.put(bodyId, loop);
        }

        List<Map<String, Object>> newNodes = nodes.stream()
                .filter(n -> !loopNodes.containsKey(String.valueOf(n.get("id"))))
                .collect(Collectors.toList());

        List<Map<String, Object>> newEdges = new ArrayList<>();
        for (Map<String, Object> e : edges) {
            String src = String.valueOf(e.get("source"));
            String tgt = String.valueOf(e.get("target"));
            if (loopNodes.containsKey(src)) {
                // loop → body outgoing edge: dropped (loop elided into the body's loop descriptor)
                continue;
            }
            if (loopNodes.containsKey(tgt)) {
                // src → loop: redirect to src → body
                Map<String, Object> redirected = new HashMap<>(e);
                redirected.put("target", loopToBody.get(tgt));
                newEdges.add(redirected);
            } else {
                newEdges.add(e);
            }
        }
        return new LoopRewrite(newNodes, newEdges, loopByBodyId);
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
