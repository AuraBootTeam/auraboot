package com.auraboot.framework.bpm.converter;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.chain.BpmServiceTaskConstants;
import com.auraboot.framework.bpm.extension.BpmExtensionKeys;
import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.model.assembly.ProcessDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Comparator;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts frontend BPMN designer JSON to BPMN 2.0 XML for SmartEngine deployment.
 *
 * <p>Input: JSON from the React @xyflow/react based designer with nodes/edges structure.
 * <p>Output: BPMN 2.0 XML compatible with Alibaba SmartEngine.
 *
 * <p>Supported node types: startEvent, endEvent, userTask, serviceTask, receiveTask,
 * exclusiveGateway, parallelGateway, inclusiveGateway, callActivity.
 *
 * @see BpmnToJsonConverter for the reverse direction
 */
@Slf4j
@Component
public class JsonToBpmnConverter {

    private static final String BPMN_NAMESPACE = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    private static final String SMART_NAMESPACE = "http://smartengine.org/schema/process";
    private static final String XSI_NAMESPACE = "http://www.w3.org/2001/XMLSchema-instance";
    private static final String TARGET_NAMESPACE = "http://auraboot.com/bpm";

    private final ObjectMapper objectMapper;
    private final SmartEngine smartEngine;

    public JsonToBpmnConverter(ObjectMapper objectMapper,
                               @Autowired(required = false) SmartEngine smartEngine) {
        this.objectMapper = objectMapper;
        this.smartEngine = smartEngine;
    }

    /**
     * Convert designer JSON string to BPMN 2.0 XML string.
     *
     * @param json the designer JSON string containing nodes and edges
     * @return valid BPMN 2.0 XML string
     * @throws BpmnConversionException if conversion fails
     */
    public String convert(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            return convertFromJsonNode(root);
        } catch (BpmnConversionException e) {
            throw e;
        } catch (Exception e) {
            throw new BpmnConversionException("Failed to parse JSON input", e);
        }
    }

    /**
     * Convert designer JSON (as Jackson JsonNode) to BPMN 2.0 XML string.
     *
     * @param root the root JsonNode containing process definition
     * @return valid BPMN 2.0 XML string
     * @throws BpmnConversionException if conversion fails
     */
    public String convertFromJsonNode(JsonNode root) {
        try {
            String processKey = getTextOrDefault(root, "key", "process_1");
            String processName = getTextOrDefault(root, "name", processKey);
            JsonNode nodesArray = root.path("nodes");
            JsonNode edgesArray = root.path("edges");
            JsonNode processAura = root.path("aura");

            StringWriter stringWriter = new StringWriter();
            XMLOutputFactory xmlFactory = XMLOutputFactory.newInstance();
            XMLStreamWriter writer = xmlFactory.createXMLStreamWriter(stringWriter);

            writeXmlDocument(writer, processKey, processName, nodesArray, edgesArray, processAura);

            writer.flush();
            writer.close();

            String xml = stringWriter.toString();
            log.debug("Converted JSON to BPMN XML: processKey={}, nodeCount={}, edgeCount={}",
                    processKey, nodesArray.size(), edgesArray.size());
            return xml;

        } catch (BpmnConversionException e) {
            throw e;
        } catch (Exception e) {
            throw new BpmnConversionException("Failed to convert JSON to BPMN XML", e);
        }
    }

    /**
     * Convert designer data from a Map to BPMN 2.0 XML string.
     *
     * @param processData the process data as a Map
     * @return valid BPMN 2.0 XML string
     * @throws BpmnConversionException if conversion fails
     */
    public String convertFromMap(Map<String, Object> processData) {
        try {
            JsonNode root = objectMapper.valueToTree(processData);
            return convertFromJsonNode(root);
        } catch (BpmnConversionException e) {
            throw e;
        } catch (Exception e) {
            throw new BpmnConversionException("Failed to convert Map to BPMN XML", e);
        }
    }

    // ==================== XML Generation ====================

    private void writeXmlDocument(XMLStreamWriter writer, String processKey, String processName,
                                  JsonNode nodes, JsonNode edges, JsonNode processAura)
            throws XMLStreamException {

        writer.writeStartDocument("UTF-8", "1.0");
        writer.writeCharacters("\n");

        // <definitions>
        writer.writeStartElement("definitions");
        writer.writeDefaultNamespace(BPMN_NAMESPACE);
        writer.writeNamespace("smart", SMART_NAMESPACE);
        writer.writeNamespace("xsi", XSI_NAMESPACE);
        writer.writeAttribute("targetNamespace", TARGET_NAMESPACE);
        writer.writeCharacters("\n\n");

        // <process>
        writer.writeCharacters("  ");
        writer.writeStartElement("process");
        writer.writeAttribute("id", processKey);
        writer.writeAttribute("name", processName);
        writer.writeAttribute("isExecutable", "true");
        writer.writeCharacters("\n");

        // Process-level <extensionElements><smart:properties>aura.*</smart:properties></extensionElements>
        // compiled from designerJson.aura.{withdrawPolicy,ccPolicy}. Empty or absent aura block
        // emits nothing so existing BPMN stays byte-identical for processes without policies.
        writeProcessAuraExtensionElements(writer, processAura);

        // Validate exclusive gateway outgoing flows (each must have a condition or be marked default;
        // at most one default per gateway). Fail fast before writing invalid BPMN.
        validateExclusiveGatewayFlows(nodes, edges);

        // Collect default flow IDs for gateways to set the "default" attribute later.
        // We need to pre-process to find which gateway has which default flow.
        Map<String, String> gatewayDefaultFlows = collectGatewayDefaultFlows(nodes, edges);

        // Write all nodes
        if (nodes.isArray()) {
            for (JsonNode node : nodes) {
                writeNode(writer, node, gatewayDefaultFlows);
            }
        }

        // Write all edges (sequence flows)
        if (edges.isArray()) {
            for (JsonNode edge : edges) {
                writeSequenceFlow(writer, edge);
            }
        }

        // Close </process>
        writer.writeCharacters("  ");
        writer.writeEndElement();
        writer.writeCharacters("\n\n");

        // Close </definitions>
        writer.writeEndElement();
        writer.writeCharacters("\n");
        writer.writeEndDocument();
    }

    /**
     * Collect the mapping from gateway node IDs to their default sequence flow IDs.
     * A default flow can be identified in two ways:
     * 1. The gateway's config has a "defaultFlow" or "defaultFlowId" field
     * 2. An edge has "isDefault: true" in its data
     */
    private Map<String, String> collectGatewayDefaultFlows(JsonNode nodes, JsonNode edges) {
        Map<String, String> result = new java.util.HashMap<>();

        // First, check gateway configs for defaultFlow/defaultFlowId
        if (nodes.isArray()) {
            for (JsonNode node : nodes) {
                String nodeType = getNodeType(node);
                if ("exclusiveGateway".equals(nodeType) || "parallelGateway".equals(nodeType) || "inclusiveGateway".equals(nodeType)) {
                    String nodeId = node.path("id").asText();
                    JsonNode config = node.path("data").path("config");
                    String defaultFlowId = getTextOrNull(config, "defaultFlow");
                    if (defaultFlowId == null) {
                        defaultFlowId = getTextOrNull(config, "defaultFlowId");
                    }
                    if (defaultFlowId != null) {
                        result.put(nodeId, defaultFlowId);
                    }
                }
            }
        }

        // Second, check edges for isDefault flag and map back to source gateway
        if (edges.isArray()) {
            for (JsonNode edge : edges) {
                JsonNode edgeData = edge.path("data");
                if (edgeData.path("isDefault").asBoolean(false)) {
                    String edgeId = edge.path("id").asText();
                    String sourceId = edge.path("source").asText();
                    // Only override if not already set by gateway config
                    result.putIfAbsent(sourceId, edgeId);
                }
            }
        }

        return result;
    }

    /**
     * Validate that every exclusive gateway's outgoing sequence flows satisfy:
     * (a) at most one flow marked as default (via edge.data.isDefault or gateway config defaultFlow/Id);
     * (b) <strong>every</strong> outgoing flow has a non-empty conditionExpression content,
     *     including any flow flagged as default. SmartEngine requires all outgoing flows to
     *     carry an evaluable condition — the BPMN spec's bare {@code default=} fallback is not
     *     supported by the engine, so we surface that as a hard validation error rather than
     *     letting the engine reject the deployment with an opaque message at runtime.
     * Throws BpmnConversionException with gatewayId + edgeId so the caller can surface a precise error.
     */
    private void validateExclusiveGatewayFlows(JsonNode nodes, JsonNode edges) {
        if (!nodes.isArray() || !edges.isArray()) return;

        for (JsonNode node : nodes) {
            if (!"exclusiveGateway".equals(getNodeType(node))) continue;
            String gatewayId = node.path("id").asText();

            // Collect outgoing edges
            List<JsonNode> outgoing = new ArrayList<>();
            for (JsonNode edge : edges) {
                if (gatewayId.equals(edge.path("source").asText())) {
                    outgoing.add(edge);
                }
            }
            if (outgoing.isEmpty()) continue;

            // (a) at most one default
            List<String> defaultEdgeIds = new ArrayList<>();
            for (JsonNode edge : outgoing) {
                if (edge.path("data").path("isDefault").asBoolean(false)) {
                    defaultEdgeIds.add(edge.path("id").asText());
                }
            }
            if (defaultEdgeIds.size() > 1) {
                throw new BpmnConversionException(
                        "Exclusive gateway '" + gatewayId + "' has multiple default flows: " + defaultEdgeIds);
            }

            // (b) every outgoing flow must carry a non-empty condition
            for (JsonNode edge : outgoing) {
                String edgeId = edge.path("id").asText();
                JsonNode condition = edge.path("data").path("condition");
                boolean hasCondition = !condition.isMissingNode() && !condition.isNull()
                        && !condition.path("content").asText("").trim().isEmpty();
                if (!hasCondition) {
                    throw new BpmnConversionException(
                            "Sequence flow '" + edgeId + "' from exclusive gateway '" + gatewayId
                                    + "' is missing a condition expression (every outgoing flow must "
                                    + "carry an evaluable expression — SmartEngine does not honor BPMN "
                                    + "default-flow fallback)");
                }
            }
        }
    }

    // ==================== Node Writers ====================

    private void writeNode(XMLStreamWriter writer, JsonNode node,
                           Map<String, String> gatewayDefaultFlows) throws XMLStreamException {
        String nodeType = getNodeType(node);
        String nodeId = node.path("id").asText();
        JsonNode data = node.path("data");
        String label = getTextOrNull(data, "label");
        JsonNode config = data.path("config");

        switch (nodeType) {
            case "startEvent" -> writeStartEvent(writer, nodeId, label);
            case "endEvent" -> writeEndEvent(writer, nodeId, label);
            case "userTask" -> writeUserTask(writer, nodeId, label, config, data);
            case "serviceTask" -> writeServiceTask(writer, nodeId, label, config, null);
            case BpmServiceTaskConstants.NODE_TYPE_RULE_TASK ->
                    // rule-task reads smart:* attrs directly off node.data
                    // (no nested data.config indirection).
                    writeServiceTask(writer, nodeId, label, data,
                            BpmServiceTaskConstants.NODE_TYPE_RULE_TASK);
            case BpmServiceTaskConstants.NODE_TYPE_NOTIFICATION_TASK ->
                    writeServiceTask(writer, nodeId, label, data,
                            BpmServiceTaskConstants.NODE_TYPE_NOTIFICATION_TASK);
            case BpmServiceTaskConstants.NODE_TYPE_RECORD_UPDATE_TASK ->
                    writeServiceTask(writer, nodeId, label, data,
                            BpmServiceTaskConstants.NODE_TYPE_RECORD_UPDATE_TASK);
            case "receiveTask" -> writeReceiveTask(writer, nodeId, label, config);
            case "exclusiveGateway" -> writeExclusiveGateway(writer, nodeId, label, gatewayDefaultFlows.get(nodeId));
            case "parallelGateway" -> writeParallelGateway(writer, nodeId, label, gatewayDefaultFlows.get(nodeId));
            case "inclusiveGateway" -> writeInclusiveGateway(writer, nodeId, label, gatewayDefaultFlows.get(nodeId));
            case "callActivity" -> writeCallActivity(writer, nodeId, label, config);
            default -> {
                log.warn("Unknown node type '{}' for node '{}', skipping", nodeType, nodeId);
            }
        }
    }

    private void writeStartEvent(XMLStreamWriter writer, String id, String name) throws XMLStreamException {
        writer.writeCharacters("\n    ");
        writer.writeEmptyElement("startEvent");
        writer.writeAttribute("id", id);
        if (name != null) {
            writer.writeAttribute("name", name);
        }
        writer.writeCharacters("\n");
    }

    private void writeEndEvent(XMLStreamWriter writer, String id, String name) throws XMLStreamException {
        writer.writeCharacters("\n    ");
        writer.writeEmptyElement("endEvent");
        writer.writeAttribute("id", id);
        if (name != null) {
            writer.writeAttribute("name", name);
        }
        writer.writeCharacters("\n");
    }

    private void writeUserTask(XMLStreamWriter writer, String id, String name, JsonNode config,
                                JsonNode data) throws XMLStreamException {
        JsonNode multiInstance = config != null ? config.path("multiInstance") : null;
        boolean hasMultiInstance = multiInstance != null && !multiInstance.isMissingNode()
                && multiInstance.path("enabled").asBoolean(false);
        // Collect node-level aura extension properties (requiredPermissions, ccPolicyOverride).
        Map<String, String> auraProps = collectUserTaskAuraProperties(config);
        boolean hasAuraExtensions = !auraProps.isEmpty();
        // Collect node-level hook descriptors (config.hooks[]) for GAP-254 compilation.
        // Hooks are emitted as <smart:hook> children inside <extensionElements> alongside
        // <smart:properties>, so the BPMN XML carries the full designer hook config and
        // the deploy-time persistence path can recover it from XML or designerJson alike.
        List<HookDescriptor> hooks = collectNodeHookDescriptors(config);
        boolean hasHookExtensions = !hooks.isEmpty();
        // If any child needs to appear we must use writeStartElement (not empty element).
        boolean hasChildren = hasMultiInstance || hasAuraExtensions || hasHookExtensions;

        writer.writeCharacters("\n    ");
        if (hasChildren) {
            writer.writeStartElement("userTask");
        } else {
            writer.writeEmptyElement("userTask");
        }
        writer.writeAttribute("id", id);
        if (name != null) {
            writer.writeAttribute("name", name);
        }

        // Handle assignee configuration.
        // Two supported formats:
        // 1. Nested: data.config.assignee.{type,roleIds,userIds,...} — used by Page Designer
        // 2. Flat:   data.{assigneeType,assigneeValue} — used by plugin processes.json
        //    In flat format, assigneeValue is a role/user code (resolved at runtime by
        //    AssigneeResolverService which supports both numeric IDs and string codes).
        writeUserTaskAssigneeAttributes(writer, config, data);

        // GAP-249: propagate multi-instance config as userTask-level attributes so
        // they become part of AbstractActivity.properties after parsing. SmartEngine's
        // MultiInstanceLoopCharacteristics model does NOT retain the collection
        // expression or element variable, so the TaskAssigneeDispatcher has no way to
        // drive N-instance expansion unless we stash these here. The child
        // <multiInstanceLoopCharacteristics> element below still carries isSequential
        // and completionCondition — that's what UserTaskBehavior.handleMultiInstance
        // consumes. These two surfaces are complementary, not redundant.
        if (hasMultiInstance) {
            String miCollection = getTextOrNull(multiInstance, "collection");
            if (miCollection != null) {
                writer.writeAttribute(SMART_NAMESPACE, "miCollection", miCollection);
            }
            String miElementVariable = getTextOrNull(multiInstance, "elementVariable");
            if (miElementVariable != null) {
                writer.writeAttribute(SMART_NAMESPACE, "miElementVariable", miElementVariable);
            }
            boolean miSequential = multiInstance.path("sequential").asBoolean(false);
            writer.writeAttribute(SMART_NAMESPACE, "miSequential", String.valueOf(miSequential));
        }

        if (hasAuraExtensions || hasHookExtensions) {
            writeActivityExtensionElements(writer, auraProps, hooks);
        }

        if (hasMultiInstance) {
            writeMultiInstanceLoopCharacteristics(writer, multiInstance);
        }

        if (hasChildren) {
            writer.writeCharacters("\n    ");
            writer.writeEndElement(); // userTask
        }

        writer.writeCharacters("\n");
    }

    /**
     * Build the ordered list of node-level hook descriptors from {@code config.hooks[]}
     * for GAP-254 compilation. Each entry is normalized into {@link HookDescriptor}
     * with defaults applied so callers (XML emit + DB persist) see a uniform shape.
     *
     * <p>Designer JSON shape (from BPMN designer's HookConfigSection):
     * <pre>{@code
     *   config.hooks[] = [
     *     { "hookType": "pre_execute" | "post_execute" | "pre_check" | "post_action",
     *       "executionOrder": 0,
     *       "hookConfig": { "actionType"|"type": "command|script|...", ... },
     *       "failStrategy": "block" | "ignore" | "warn",
     *       "async": false,
     *       "enabled": true }
     *   ]
     * }</pre>
     *
     * <p>{@code hookConfig} is serialized to JSON and emitted as the inner text of the
     * resulting {@code <smart:hookConfig>} element. Vocabulary normalization
     * (pre_execute → pre_check, http_callback → rest_call) happens at the
     * {@code BpmNodeHookService} write boundary; the converter preserves the raw
     * designer values so the XML stays an exact mirror of the designer state.
     */
    List<HookDescriptor> collectNodeHookDescriptors(JsonNode config) {
        if (config == null || config.isMissingNode()) return Collections.emptyList();
        JsonNode hooks = config.path("hooks");
        if (!hooks.isArray() || hooks.isEmpty()) return Collections.emptyList();
        List<HookDescriptor> result = new ArrayList<>(hooks.size());
        int autoOrder = 0;
        for (JsonNode hook : hooks) {
            if (!hook.isObject()) continue;
            String hookType = getTextOrNull(hook, "hookType");
            if (hookType == null) {
                throw new BpmnConversionException(
                        "Designer node hook missing required 'hookType' field: " + hook);
            }
            int order = hook.path("executionOrder").asInt(autoOrder);
            String failStrategy = getTextOrDefault(hook, "failStrategy", "block");
            boolean async = hook.path("async").asBoolean(false);
            boolean enabled = !hook.has("enabled") || hook.path("enabled").asBoolean(true);
            JsonNode hookConfig = hook.path("hookConfig");
            String actionType = null;
            String configJson = "{}";
            if (hookConfig.isObject()) {
                // actionType lives under hookConfig.actionType (UI vocab) or .type (backend
                // vocab); surface whichever one is present so the XML attribute reflects
                // the source-of-truth without forcing a normalize pass at this layer.
                actionType = getTextOrNull(hookConfig, "actionType");
                if (actionType == null) {
                    actionType = getTextOrNull(hookConfig, "type");
                }
                try {
                    configJson = objectMapper.writeValueAsString(hookConfig);
                } catch (JsonProcessingException e) {
                    throw new BpmnConversionException(
                            "Failed to serialize node hook hookConfig for emission", e);
                }
            }
            result.add(new HookDescriptor(
                    hookType, actionType, order, failStrategy, async, enabled,
                    configJson, hookConfig.isObject() ? hookConfig : null));
            autoOrder++;
        }
        return result;
    }

    /**
     * Static accessor used by {@code ProcessDeploymentService} to recover the same
     * hook descriptor list at deploy time so it can be persisted into
     * {@code ab_bpm_node_hook} alongside the deployed BPMN. Returns a flat list of
     * (nodeId, descriptor) pairs scoped to userTask + serviceTask nodes (the only
     * node types that surface a hook UI today).
     */
    public List<NodeHookEntry> extractHookEntries(JsonNode root) {
        List<NodeHookEntry> entries = new ArrayList<>();
        JsonNode nodes = root.path("nodes");
        if (!nodes.isArray()) return entries;
        for (JsonNode node : nodes) {
            String nodeType = getNodeType(node);
            // Only userTask carries hooks via UI today. ServiceTask family does not
            // expose a hook editor, so we skip them deliberately rather than emit
            // empty entries that downstream consumers would need to filter.
            if (!"userTask".equals(nodeType)) continue;
            String nodeId = node.path("id").asText();
            if (nodeId == null || nodeId.isBlank()) continue;
            JsonNode config = node.path("data").path("config");
            for (HookDescriptor desc : collectNodeHookDescriptors(config)) {
                entries.add(new NodeHookEntry(nodeId, desc));
            }
        }
        return entries;
    }

    /**
     * Convenience overload for callers holding a Map (avoids re-serializing).
     */
    public List<NodeHookEntry> extractHookEntries(Map<String, Object> processData) {
        JsonNode tree = objectMapper.valueToTree(processData);
        return extractHookEntries(tree);
    }

    /**
     * Normalized shape of a single designer-level hook entry, ready to be emitted
     * as XML or persisted to {@code ab_bpm_node_hook}.
     *
     * @param hookType         designer-vocab hookType (e.g. {@code pre_execute})
     * @param actionType       designer-vocab actionType (e.g. {@code command}); nullable
     * @param executionOrder   ordering within the same nodeId+hookType bucket
     * @param failStrategy     {@code block | warn | ignore}
     * @param async            whether the executor runs the hook on a virtual thread
     * @param enabled          row-level disable flag
     * @param hookConfigJson   serialized hookConfig (always a JSON object, never null)
     * @param hookConfigNode   raw JsonNode form for callers that need structured access
     */
    public record HookDescriptor(
            String hookType,
            String actionType,
            int executionOrder,
            String failStrategy,
            boolean async,
            boolean enabled,
            String hookConfigJson,
            JsonNode hookConfigNode) {}

    /**
     * Pair of (nodeId, hook descriptor) used by deploy-time persistence.
     */
    public record NodeHookEntry(String nodeId, HookDescriptor descriptor) {}

    /**
     * Build the ordered map of node-level {@code aura.*} extension properties
     * to emit inside the userTask's {@code <smart:properties>} block.
     *
     * <p>Current supported keys:
     * <ul>
     *   <li>{@link BpmExtensionKeys#REQUIRED_PERMISSIONS} — JSON array of permission codes</li>
     *   <li>{@link BpmExtensionKeys#CC_POLICY_OVERRIDE} — per-node override of process ccPolicy</li>
     * </ul>
     */
    private Map<String, String> collectUserTaskAuraProperties(JsonNode config) {
        Map<String, String> result = new LinkedHashMap<>();
        if (config == null || config.isMissingNode()) return result;
        JsonNode aura = config.path("aura");
        if (aura.isMissingNode() || !aura.isObject()) return result;

        JsonNode requiredPermissions = aura.path("requiredPermissions");
        if (requiredPermissions.isArray() && !requiredPermissions.isEmpty()) {
            // Serialize as a JSON array string; BpmExtensionAccessor.getRequiredPermissions
            // parses this back into List<String>.
            try {
                String serialized = objectMapper.writeValueAsString(requiredPermissions);
                result.put(BpmExtensionKeys.REQUIRED_PERMISSIONS, serialized);
            } catch (JsonProcessingException e) {
                throw new BpmnConversionException(
                        "Failed to serialize aura.requiredPermissions for userTask", e);
            }
        }

        String ccOverride = getTextOrNull(aura, "ccPolicyOverride");
        if (ccOverride != null) {
            result.put(BpmExtensionKeys.CC_POLICY_OVERRIDE, ccOverride);
        }
        return result;
    }

    /**
     * Emit the process-level {@code <extensionElements><smart:properties>...</smart:properties>
     * </extensionElements>} block from {@code designerJson.aura.{withdrawPolicy,ccPolicy}}.
     * No-op when the aura block is absent or empty.
     */
    private void writeProcessAuraExtensionElements(XMLStreamWriter writer, JsonNode processAura)
            throws XMLStreamException {
        if (processAura == null || processAura.isMissingNode() || !processAura.isObject()) return;
        Map<String, String> props = new LinkedHashMap<>();
        String withdrawPolicy = getTextOrNull(processAura, "withdrawPolicy");
        if (withdrawPolicy != null) {
            props.put(BpmExtensionKeys.WITHDRAW_POLICY, withdrawPolicy);
        }
        String ccPolicy = getTextOrNull(processAura, "ccPolicy");
        if (ccPolicy != null) {
            props.put(BpmExtensionKeys.CC_POLICY, ccPolicy);
        }
        if (props.isEmpty()) return;

        writer.writeCharacters("    ");
        writer.writeStartElement("extensionElements");
        writeSmartProperties(writer, props, "      ");
        writer.writeCharacters("\n    ");
        writer.writeEndElement(); // extensionElements
        writer.writeCharacters("\n");
    }

    /**
     * Emit a single activity-level {@code <extensionElements>} block carrying the
     * {@code <smart:properties>} entries for the surrounding userTask. Both
     * aura.* keyed properties and the JSON-serialized hook list piggyback on the
     * same {@code <smart:property>} mechanism so SmartEngine's BPMN parser
     * (which only recognizes its own {@code Properties} extension) accepts the
     * deployment without rejecting the file as "Parse process definition file
     * failure!" — it stores anything under {@code Properties.decorationMap} and
     * leaves interpretation to consumers.
     *
     * <p>Hook payload shape (one entry per hook descriptor):
     * <pre>{@code
     *   <smart:property name="aura.hooks"
     *                   value='[{"hookType":"pre_execute","actionType":"command",...}, ...]'/>
     * }</pre>
     *
     * <p>The serialized hook array survives import/export round-trips intact.
     * The persistence path (deploy → ab_bpm_node_hook) consumes hooks directly
     * from designerJson rather than re-parsing the XML, so this XML form is
     * primarily for traceability + downstream consumers (export, audit).
     */
    private void writeActivityExtensionElements(XMLStreamWriter writer,
                                                Map<String, String> auraProps,
                                                List<HookDescriptor> hooks)
            throws XMLStreamException {
        // Compose merged property map: aura.* values + serialized hooks payload.
        Map<String, String> merged = new LinkedHashMap<>(auraProps);
        if (!hooks.isEmpty()) {
            merged.put(BpmExtensionKeys.NODE_HOOKS, serializeHooksPayload(hooks));
        }
        if (merged.isEmpty()) return;
        writer.writeCharacters("\n      ");
        writer.writeStartElement("extensionElements");
        writeSmartProperties(writer, merged, "        ");
        writer.writeCharacters("\n      ");
        writer.writeEndElement(); // extensionElements
    }

    /**
     * Serialize designer hook descriptors into the JSON array form stored under
     * {@link BpmExtensionKeys#NODE_HOOKS}.
     */
    private String serializeHooksPayload(List<HookDescriptor> hooks) {
        try {
            List<Map<String, Object>> arr = new ArrayList<>(hooks.size());
            for (HookDescriptor h : hooks) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("hookType", h.hookType());
                if (h.actionType() != null) entry.put("actionType", h.actionType());
                entry.put("executionOrder", h.executionOrder());
                entry.put("failStrategy", h.failStrategy());
                entry.put("async", h.async());
                entry.put("enabled", h.enabled());
                entry.put("hookConfig", h.hookConfigNode() != null
                        ? objectMapper.convertValue(h.hookConfigNode(), Map.class)
                        : Map.of());
                arr.add(entry);
            }
            return objectMapper.writeValueAsString(arr);
        } catch (JsonProcessingException e) {
            throw new BpmnConversionException("Failed to serialize node hooks payload", e);
        }
    }

    /**
     * Emit a {@code <smart:properties>} element containing one
     * {@code <smart:property name="..." value="..."/>} child per entry.
     */
    private void writeSmartProperties(XMLStreamWriter writer, Map<String, String> props,
                                      String childIndent) throws XMLStreamException {
        writer.writeCharacters("\n" + childIndent.substring(2));
        writer.writeStartElement(SMART_NAMESPACE, "properties");
        for (Map.Entry<String, String> entry : props.entrySet()) {
            writer.writeCharacters("\n" + childIndent);
            writer.writeEmptyElement(SMART_NAMESPACE, "property");
            writer.writeAttribute("name", entry.getKey());
            writer.writeAttribute("value", entry.getValue());
        }
        writer.writeCharacters("\n" + childIndent.substring(2));
        writer.writeEndElement(); // smart:properties
    }

    /**
     * Write SmartEngine-compatible assignee attributes for a userTask.
     *
     * <p>Mapping from frontend AssigneeConfig to SmartEngine attributes:
     * <ul>
     *   <li>assignee.type -> smart:assigneeType</li>
     *   <li>assignee.userIds[0] -> smart:assigneeId (for single user)</li>
     *   <li>candidateUsers -> smart:candidateUsers (comma-separated)</li>
     *   <li>candidateGroups -> smart:candidateGroups (comma-separated)</li>
     *   <li>assignee expression -> smart:assignee</li>
     * </ul>
     *
     * <p>Also supports the flat format used by plugin {@code processes.json}:
     * <ul>
     *   <li>data.assigneeType + data.assigneeValue — e.g. assigneeType="role", assigneeValue="wd_manager"</li>
     * </ul>
     * In the flat format, {@code assigneeValue} is a role/user code (string).
     * {@link com.auraboot.framework.bpm.service.AssigneeResolverService} resolves both
     * numeric IDs and string codes at runtime.
     *
     * @param config  the {@code data.config} node (may be missing/null for plugin processes.json)
     * @param data    the {@code data} node (contains flat assigneeType/assigneeValue if present)
     */
    private void writeUserTaskAssigneeAttributes(XMLStreamWriter writer, JsonNode config,
                                                  JsonNode data) throws XMLStreamException {
        // Format 1: nested assignee object inside data.config (Page Designer format)
        if (config != null && !config.isMissingNode()) {
            JsonNode assignee = config.path("assignee");
            if (!assignee.isMissingNode() && !assignee.isNull()) {
                String assigneeType = getTextOrNull(assignee, "type");

                if ("expression".equals(assigneeType)) {
                    // Expression-based assignee: use smart:assignee
                    String expression = getTextOrNull(assignee, "expression");
                    if (expression != null) {
                        writer.writeAttribute(SMART_NAMESPACE, "assignee", expression);
                    }
                } else if (assigneeType != null) {
                    // Type-based assignee (user, role, dept, starter)
                    writer.writeAttribute(SMART_NAMESPACE, "assigneeType", assigneeType);

                    // For user type, use the first userId as assigneeId
                    if ("user".equals(assigneeType)) {
                        JsonNode userIds = assignee.path("userIds");
                        if (userIds.isArray() && !userIds.isEmpty()) {
                            writer.writeAttribute(SMART_NAMESPACE, "assigneeId", userIds.get(0).asText());
                        }
                    } else if ("role".equals(assigneeType)) {
                        JsonNode roleIds = assignee.path("roleIds");
                        if (roleIds.isArray() && !roleIds.isEmpty()) {
                            writer.writeAttribute(SMART_NAMESPACE, "assigneeId", roleIds.get(0).asText());
                        }
                    } else if ("dept".equals(assigneeType)) {
                        JsonNode deptIds = assignee.path("deptIds");
                        if (deptIds.isArray() && !deptIds.isEmpty()) {
                            writer.writeAttribute(SMART_NAMESPACE, "assigneeId", deptIds.get(0).asText());
                        }
                    }
                }

                // Candidate users
                JsonNode candidateUsers = config.path("candidateUsers");
                if (candidateUsers.isArray() && !candidateUsers.isEmpty()) {
                    String candidateUsersStr = joinArrayNode(candidateUsers);
                    writer.writeAttribute(SMART_NAMESPACE, "candidateUsers", candidateUsersStr);
                }

                // Candidate groups
                JsonNode candidateGroups = config.path("candidateGroups");
                if (candidateGroups.isArray() && !candidateGroups.isEmpty()) {
                    String candidateGroupsStr = joinArrayNode(candidateGroups);
                    writer.writeAttribute(SMART_NAMESPACE, "candidateGroups", candidateGroupsStr);
                }
                return; // nested format handled, done
            }
        }

        // Format 2: flat assigneeType + assigneeValue in data node (plugin processes.json format)
        // assigneeValue is a role/user code — AssigneeResolverService handles code→ID lookup at runtime.
        if (data != null && !data.isMissingNode()) {
            String flatType = getTextOrNull(data, "assigneeType");
            String flatValue = getTextOrNull(data, "assigneeValue");
            if (flatType != null && !flatType.isBlank()) {
                writer.writeAttribute(SMART_NAMESPACE, "assigneeType", flatType);
                if (flatValue != null && !flatValue.isBlank()) {
                    // Write assigneeValue under a dedicated attribute so AssigneeResolverService
                    // can distinguish "code" (string) from "id" (numeric) at runtime.
                    writer.writeAttribute(SMART_NAMESPACE, "assigneeId", flatValue);
                }
            }
        }
    }

    private void writeServiceTask(XMLStreamWriter writer, String id, String name, JsonNode config,
                                   String subType) throws XMLStreamException {
        JsonNode multiInstance = config != null ? config.path("multiInstance") : null;
        boolean hasMultiInstance = multiInstance != null && !multiInstance.isMissingNode()
                && multiInstance.path("enabled").asBoolean(false);

        writer.writeCharacters("\n    ");
        if (hasMultiInstance) {
            writer.writeStartElement("serviceTask");
        } else {
            writer.writeEmptyElement("serviceTask");
        }
        writer.writeAttribute("id", id);
        if (name != null) {
            writer.writeAttribute("name", name);
        }

        if (BpmServiceTaskConstants.NODE_TYPE_RULE_TASK.equals(subType)) {
            // Dedicated SmartEngine delegate for Drools evaluation.
            writer.writeAttribute(SMART_NAMESPACE, "class", BpmServiceTaskConstants.BEAN_DROOLS_DELEGATE);
            if (config != null && !config.isMissingNode()) {
                String ruleCode = getTextOrNull(config, BpmServiceTaskConstants.ATTR_RULE_CODE);
                if (ruleCode == null) {
                    throw new BpmnConversionException("rule-task '" + id + "' missing '"
                            + BpmServiceTaskConstants.ATTR_RULE_CODE + "' in config");
                }
                writer.writeAttribute(SMART_NAMESPACE, BpmServiceTaskConstants.ATTR_RULE_CODE, ruleCode);
                String factsVars = getTextOrNull(config, BpmServiceTaskConstants.ATTR_FACTS_VARS);
                if (factsVars != null) {
                    writer.writeAttribute(SMART_NAMESPACE,
                            BpmServiceTaskConstants.ATTR_FACTS_VARS, factsVars);
                }
            } else {
                throw new BpmnConversionException("rule-task '" + id + "' missing config");
            }
        } else if (BpmServiceTaskConstants.NODE_TYPE_NOTIFICATION_TASK.equals(subType)) {
            // Dedicated SmartEngine delegate for notification publishing.
            writer.writeAttribute(SMART_NAMESPACE, "class",
                    BpmServiceTaskConstants.BEAN_NOTIFICATION_DELEGATE);
            if (config != null && !config.isMissingNode()) {
                String eventCode = getTextOrNull(config, BpmServiceTaskConstants.ATTR_EVENT_CODE);
                if (eventCode == null) {
                    throw new BpmnConversionException("notification-task '" + id + "' missing '"
                            + BpmServiceTaskConstants.ATTR_EVENT_CODE + "' in config");
                }
                writer.writeAttribute(SMART_NAMESPACE,
                        BpmServiceTaskConstants.ATTR_EVENT_CODE, eventCode);
                String recipientFrom = getTextOrNull(config,
                        BpmServiceTaskConstants.ATTR_RECIPIENT_FROM);
                if (recipientFrom != null) {
                    writer.writeAttribute(SMART_NAMESPACE,
                            BpmServiceTaskConstants.ATTR_RECIPIENT_FROM, recipientFrom);
                }
                String templateParamsVars = getTextOrNull(config,
                        BpmServiceTaskConstants.ATTR_TEMPLATE_PARAMS_VARS);
                if (templateParamsVars != null) {
                    writer.writeAttribute(SMART_NAMESPACE,
                            BpmServiceTaskConstants.ATTR_TEMPLATE_PARAMS_VARS, templateParamsVars);
                }
            } else {
                throw new BpmnConversionException("notification-task '" + id + "' missing config");
            }
        } else if (BpmServiceTaskConstants.NODE_TYPE_RECORD_UPDATE_TASK.equals(subType)) {
            // Dedicated SmartEngine delegate for updating a single field on a dynamic model record.
            writer.writeAttribute(SMART_NAMESPACE, "class",
                    BpmServiceTaskConstants.BEAN_RECORD_UPDATE_DELEGATE);
            if (config != null && !config.isMissingNode()) {
                String modelCode = getTextOrNull(config, BpmServiceTaskConstants.ATTR_MODEL_CODE);
                if (modelCode == null) {
                    throw new BpmnConversionException("record-update-task '" + id + "' missing '"
                            + BpmServiceTaskConstants.ATTR_MODEL_CODE + "' in config");
                }
                writer.writeAttribute(SMART_NAMESPACE,
                        BpmServiceTaskConstants.ATTR_MODEL_CODE, modelCode);
                String recordIdVar = getTextOrNull(config, BpmServiceTaskConstants.ATTR_RECORD_ID_VAR);
                if (recordIdVar != null) {
                    writer.writeAttribute(SMART_NAMESPACE,
                            BpmServiceTaskConstants.ATTR_RECORD_ID_VAR, recordIdVar);
                }
                String fieldName = getTextOrNull(config, BpmServiceTaskConstants.ATTR_FIELD_NAME);
                if (fieldName == null) {
                    throw new BpmnConversionException("record-update-task '" + id + "' missing '"
                            + BpmServiceTaskConstants.ATTR_FIELD_NAME + "' in config");
                }
                writer.writeAttribute(SMART_NAMESPACE,
                        BpmServiceTaskConstants.ATTR_FIELD_NAME, fieldName);
                String fieldValue = getTextOrNull(config, BpmServiceTaskConstants.ATTR_FIELD_VALUE);
                if (fieldValue == null) {
                    throw new BpmnConversionException("record-update-task '" + id + "' missing '"
                            + BpmServiceTaskConstants.ATTR_FIELD_VALUE + "' in config");
                }
                writer.writeAttribute(SMART_NAMESPACE,
                        BpmServiceTaskConstants.ATTR_FIELD_VALUE, fieldValue);
            } else {
                throw new BpmnConversionException("record-update-task '" + id + "' missing config");
            }
        } else if (config != null && !config.isMissingNode()) {
            String serviceType = getTextOrNull(config, "serviceType");
            String className = getTextOrNull(config, "className");

            if ("command".equals(serviceType)) {
                // COMMAND service type: bridge to AuraBoot Command engine via CommandServiceTaskDelegate.
                // The delegate reads command configuration from process variables at runtime.
                writer.writeAttribute(SMART_NAMESPACE, "class", "commandServiceTaskDelegate");
            } else if ("http".equals(serviceType)) {
                // HTTP service type: bridge to HttpServiceTaskDelegate which
                // performs the outbound call at runtime. Required: serviceUrl.
                String serviceUrl = getTextOrNull(config, "serviceUrl");
                if (serviceUrl == null || serviceUrl.isBlank()) {
                    throw new BpmnConversionException("serviceTask '" + id
                            + "' with serviceType=http missing 'serviceUrl' in config");
                }
                writer.writeAttribute(SMART_NAMESPACE, "class",
                        BpmServiceTaskConstants.BEAN_HTTP_DELEGATE);
                writer.writeAttribute(SMART_NAMESPACE,
                        BpmServiceTaskConstants.ATTR_SERVICE_URL, serviceUrl);
                String httpMethod = getTextOrNull(config, "method");
                if (httpMethod != null && !httpMethod.isBlank()) {
                    writer.writeAttribute(SMART_NAMESPACE,
                            BpmServiceTaskConstants.ATTR_METHOD, httpMethod);
                }
                String responseVar = getTextOrNull(config, "responseVar");
                if (responseVar != null && !responseVar.isBlank()) {
                    writer.writeAttribute(SMART_NAMESPACE,
                            BpmServiceTaskConstants.ATTR_RESPONSE_VAR, responseVar);
                }
                String timeoutMs = getTextOrNull(config, "timeoutMs");
                if (timeoutMs != null && !timeoutMs.isBlank()) {
                    writer.writeAttribute(SMART_NAMESPACE,
                            BpmServiceTaskConstants.ATTR_TIMEOUT_MS, timeoutMs);
                }
            } else if (className != null) {
                // Explicit className: emit smart:class directly
                writer.writeAttribute(SMART_NAMESPACE, "class", className);
            } else if ("java".equals(serviceType)) {
                // serviceType is java but no className yet - log a warning
                log.warn("ServiceTask '{}' has serviceType=java but no className configured", id);
            }

            // Store async flag as extension attribute if set
            if (config.path("async").asBoolean(false)) {
                writer.writeAttribute(SMART_NAMESPACE, "async", "true");
            }
        }

        if (hasMultiInstance) {
            writeMultiInstanceLoopCharacteristics(writer, multiInstance);

            writer.writeCharacters("\n    ");
            writer.writeEndElement(); // serviceTask
        }

        writer.writeCharacters("\n");
    }

    private void writeReceiveTask(XMLStreamWriter writer, String id, String name, JsonNode config)
            throws XMLStreamException {
        writer.writeCharacters("\n    ");
        writer.writeEmptyElement("receiveTask");
        writer.writeAttribute("id", id);
        if (name != null) {
            writer.writeAttribute("name", name);
        }
        writer.writeCharacters("\n");
    }

    private void writeExclusiveGateway(XMLStreamWriter writer, String id, String name, String defaultFlowId)
            throws XMLStreamException {
        writer.writeCharacters("\n    ");
        writer.writeEmptyElement("exclusiveGateway");
        writer.writeAttribute("id", id);
        if (name != null) {
            writer.writeAttribute("name", name);
        }
        if (defaultFlowId != null) {
            writer.writeAttribute("default", defaultFlowId);
        }
        writer.writeCharacters("\n");
    }

    private void writeParallelGateway(XMLStreamWriter writer, String id, String name, String defaultFlowId)
            throws XMLStreamException {
        writer.writeCharacters("\n    ");
        writer.writeEmptyElement("parallelGateway");
        writer.writeAttribute("id", id);
        if (name != null) {
            writer.writeAttribute("name", name);
        }
        if (defaultFlowId != null) {
            writer.writeAttribute("default", defaultFlowId);
        }
        writer.writeCharacters("\n");
    }

    private void writeInclusiveGateway(XMLStreamWriter writer, String id, String name, String defaultFlowId)
            throws XMLStreamException {
        writer.writeCharacters("\n    ");
        writer.writeEmptyElement("inclusiveGateway");
        writer.writeAttribute("id", id);
        if (name != null) {
            writer.writeAttribute("name", name);
        }
        if (defaultFlowId != null) {
            writer.writeAttribute("default", defaultFlowId);
        }
        writer.writeCharacters("\n");
    }

    private void writeCallActivity(XMLStreamWriter writer, String id, String name, JsonNode config)
            throws XMLStreamException {
        writer.writeCharacters("\n    ");
        writer.writeStartElement("callActivity");
        writer.writeAttribute("id", id);
        if (name != null) {
            writer.writeAttribute("name", name);
        }

        Map<String, String> auraProps = new LinkedHashMap<>();
        if (config != null && !config.isMissingNode()) {
            String calledProcessKey = getTextOrNull(config, "calledProcessKey");
            if (calledProcessKey != null) {
                writer.writeAttribute("calledElement", calledProcessKey);
            }
            String calledProcessVersion = getTextOrNull(config, "calledProcessVersion");
            if ("latest".equals(calledProcessVersion) && calledProcessKey != null) {
                // The UI defaults version-mode to "latest"; SmartEngine's
                // CallActivityParser stores the string verbatim and its
                // runtime ProcessDefinitionContainer has no latest-alias
                // resolver (lookup by literal {key}:{version}:{tenant} only).
                // Resolve to the highest deployed version for this tenant at
                // convert-time so the deployed BPMN carries a concrete
                // version attribute. If nothing is deployed yet we skip the
                // attribute — the child reference will fail at runtime with
                // a clear "No ProcessDefinition found" error, which is
                // preferable to silently pinning a stale version.
                String resolved = resolveLatestVersion(calledProcessKey);
                if (resolved != null) {
                    calledProcessVersion = resolved;
                }
            }
            if (calledProcessVersion != null && !"latest".equals(calledProcessVersion)) {
                writer.writeAttribute(SMART_NAMESPACE, "calledElementVersion", calledProcessVersion);
            }

            // Collect UI-authored variable mappings into a single aura.callMappings
            // JSON payload. SmartEngine's CallActivityBehavior isolates parent
            // and child request maps (only tenantId is forwarded — see
            // CallActivityBehavior#startChildProcessInstance), so BPMN itself
            // has no channel for <smart:in>/<smart:out>. At runtime
            // AuraCallActivityListener reads this payload on the child's
            // PROCESS_START and the parent's callActivity ACTIVITY_END to
            // bridge the isolation.
            //
            // We piggyback on the generic <smart:properties> extension (same
            // mechanism as aura.hooks / aura.formKey) because SmartEngine's
            // BPMN parser rejects unknown child elements under <callActivity>
            // (GAP-250: "Parse process definition file failure!"). <smart:in>
            // and <smart:out> are NOT registered parsers; a named smart:property
            // nested inside <extensionElements><smart:properties> IS.
            JsonNode inputMappingsNode = config.path("inputMappings");
            JsonNode outputMappingsNode = config.path("outputMappings");
            boolean hasInputs = inputMappingsNode.isObject() && inputMappingsNode.size() > 0;
            boolean hasOutputs = outputMappingsNode.isObject() && outputMappingsNode.size() > 0;
            if (hasInputs || hasOutputs) {
                Map<String, Object> payload = new LinkedHashMap<>();
                if (hasInputs) {
                    payload.put("inputs", objectMapper.convertValue(inputMappingsNode, Map.class));
                }
                if (hasOutputs) {
                    payload.put("outputs", objectMapper.convertValue(outputMappingsNode, Map.class));
                }
                try {
                    auraProps.put(BpmExtensionKeys.CALL_MAPPINGS,
                            objectMapper.writeValueAsString(payload));
                } catch (JsonProcessingException e) {
                    throw new BpmnConversionException(
                            "Failed to serialize callActivity mappings payload", e);
                }
            }
        }

        if (!auraProps.isEmpty()) {
            writer.writeCharacters("\n      ");
            writer.writeStartElement("extensionElements");
            writeSmartProperties(writer, auraProps, "        ");
            writer.writeCharacters("\n      ");
            writer.writeEndElement(); // extensionElements
            writer.writeCharacters("\n    ");
        } else {
            writer.writeCharacters("\n");
        }
        writer.writeEndElement(); // callActivity
    }

    /**
     * Resolve the highest deployed version for a process key under the
     * current tenant. Returns {@code null} when no matching definition is
     * deployed yet, or when the SmartEngine bean is unavailable (e.g. in
     * lightweight unit tests of the converter itself — those cases should
     * supply a concrete version literal in the designer config).
     */
    private String resolveLatestVersion(String processKey) {
        if (smartEngine == null) return null;
        String tenantId;
        try {
            tenantId = MetaContext.getCurrentTenantIdAsString();
        } catch (Exception e) {
            return null;
        }
        try {
            return smartEngine.getRepositoryQueryService()
                    .getAllCachedProcessDefinition()
                    .stream()
                    .filter(pd -> processKey.equals(pd.getId()))
                    .filter(pd -> tenantId == null || tenantId.equals(pd.getTenantId()))
                    .map(ProcessDefinition::getVersion)
                    .max(Comparator.naturalOrder())
                    .orElse(null);
        } catch (Exception e) {
            log.warn("resolveLatestVersion({}) failed: {}", processKey, e.getMessage());
            return null;
        }
    }

    /**
     * Write multiInstanceLoopCharacteristics child element for userTask or serviceTask.
     */
    private void writeMultiInstanceLoopCharacteristics(XMLStreamWriter writer, JsonNode multiInstance)
            throws XMLStreamException {
        boolean isSequential = multiInstance.path("sequential").asBoolean(false);
        writer.writeCharacters("\n      ");
        writer.writeStartElement("multiInstanceLoopCharacteristics");
        writer.writeAttribute("isSequential", String.valueOf(isSequential));

        // Collection and element variable
        String collection = getTextOrNull(multiInstance, "collection");
        if (collection != null) {
            writer.writeAttribute(SMART_NAMESPACE, "collection", collection);
        }
        String elementVariable = getTextOrNull(multiInstance, "elementVariable");
        if (elementVariable != null) {
            writer.writeAttribute(SMART_NAMESPACE, "elementVariable", elementVariable);
        }

        // Loop cardinality
        int loopCardinality = multiInstance.path("loopCardinality").asInt(0);
        if (loopCardinality > 0) {
            writer.writeCharacters("\n        ");
            writer.writeStartElement("loopCardinality");
            writer.writeCharacters(String.valueOf(loopCardinality));
            writer.writeEndElement();
        }

        // Completion condition
        String completionCondition = getTextOrNull(multiInstance, "completionCondition");
        if (completionCondition != null) {
            writer.writeCharacters("\n        ");
            writer.writeStartElement("completionCondition");
            writer.writeCData(completionCondition);
            writer.writeEndElement();
        }

        writer.writeCharacters("\n      ");
        writer.writeEndElement(); // multiInstanceLoopCharacteristics
    }

    // ==================== Sequence Flow Writer ====================

    private void writeSequenceFlow(XMLStreamWriter writer, JsonNode edge) throws XMLStreamException {
        String edgeId = edge.path("id").asText();
        String sourceRef = edge.path("source").asText();
        String targetRef = edge.path("target").asText();
        JsonNode edgeData = edge.path("data");
        String label = getTextOrNull(edgeData, "label");

        // Check if this edge has a condition expression
        JsonNode condition = edgeData.path("condition");
        boolean hasCondition = !condition.isMissingNode() && !condition.isNull()
                && condition.has("content") && !condition.path("content").asText("").isEmpty();

        if (hasCondition) {
            // Sequence flow with condition expression - need child element
            writer.writeCharacters("\n    ");
            writer.writeStartElement("sequenceFlow");
            writer.writeAttribute("id", edgeId);
            writer.writeAttribute("sourceRef", sourceRef);
            writer.writeAttribute("targetRef", targetRef);
            if (label != null) {
                writer.writeAttribute("name", label);
            }

            // Write conditionExpression child element
            writer.writeCharacters("\n      ");
            writer.writeStartElement("conditionExpression");

            String conditionType = getTextOrDefault(condition, "type", "expression");
            if ("expression".equals(conditionType)) {
                writer.writeAttribute(XSI_NAMESPACE, "type", "tFormalExpression");
            } else if ("script".equals(conditionType)) {
                String language = getTextOrNull(condition, "language");
                if (language != null) {
                    writer.writeAttribute("language", language);
                }
            }

            String content = condition.path("content").asText();
            writer.writeCharacters(content);
            writer.writeEndElement(); // </conditionExpression>

            writer.writeCharacters("\n    ");
            writer.writeEndElement(); // </sequenceFlow>
            writer.writeCharacters("\n");
        } else {
            // Simple sequence flow without condition
            writer.writeCharacters("\n    ");
            writer.writeEmptyElement("sequenceFlow");
            writer.writeAttribute("id", edgeId);
            writer.writeAttribute("sourceRef", sourceRef);
            writer.writeAttribute("targetRef", targetRef);
            if (label != null) {
                writer.writeAttribute("name", label);
            }
            writer.writeCharacters("\n");
        }
    }

    // ==================== Utility Methods ====================

    private String getNodeType(JsonNode node) {
        // The node type can be in node.type or node.data.type
        String type = getTextOrNull(node, "type");
        if (type == null) {
            type = getTextOrNull(node.path("data"), "type");
        }
        return type;
    }

    private String getTextOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || value.asText().isEmpty()) {
            return null;
        }
        return value.asText();
    }

    private String getTextOrDefault(JsonNode node, String field, String defaultValue) {
        String value = getTextOrNull(node, field);
        return value != null ? value : defaultValue;
    }

    private String joinArrayNode(JsonNode arrayNode) {
        List<String> values = new ArrayList<>();
        for (JsonNode item : arrayNode) {
            String text = item.asText();
            if (text != null && !text.isEmpty()) {
                values.add(text);
            }
        }
        return String.join(",", values);
    }
}
