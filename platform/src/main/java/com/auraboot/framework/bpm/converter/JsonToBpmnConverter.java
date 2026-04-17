package com.auraboot.framework.bpm.converter;

import com.auraboot.framework.bpm.chain.BpmServiceTaskConstants;
import com.auraboot.framework.bpm.extension.BpmExtensionKeys;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Iterator;
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

    public JsonToBpmnConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
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
            case "userTask" -> writeUserTask(writer, nodeId, label, config);
            case "serviceTask" -> writeServiceTask(writer, nodeId, label, config, null);
            case BpmServiceTaskConstants.NODE_TYPE_RULE_TASK ->
                    // rule-task reads smart:* attrs directly off node.data
                    // (no nested data.config indirection).
                    writeServiceTask(writer, nodeId, label, data,
                            BpmServiceTaskConstants.NODE_TYPE_RULE_TASK);
            case BpmServiceTaskConstants.NODE_TYPE_NOTIFICATION_TASK ->
                    writeServiceTask(writer, nodeId, label, data,
                            BpmServiceTaskConstants.NODE_TYPE_NOTIFICATION_TASK);
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

    private void writeUserTask(XMLStreamWriter writer, String id, String name, JsonNode config)
            throws XMLStreamException {
        JsonNode multiInstance = config != null ? config.path("multiInstance") : null;
        boolean hasMultiInstance = multiInstance != null && !multiInstance.isMissingNode()
                && multiInstance.path("enabled").asBoolean(false);
        // Collect node-level aura extension properties (requiredPermissions, ccPolicyOverride).
        Map<String, String> auraProps = collectUserTaskAuraProperties(config);
        boolean hasAuraExtensions = !auraProps.isEmpty();
        // If either child needs to appear we must use writeStartElement (not empty element).
        boolean hasChildren = hasMultiInstance || hasAuraExtensions;

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

        // Handle assignee configuration
        writeUserTaskAssigneeAttributes(writer, config);

        if (hasAuraExtensions) {
            writeExtensionPropertiesElement(writer, auraProps);
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
     * Emit an activity-level {@code <extensionElements><smart:properties>...} block for
     * the provided {@code aura.*} keyed properties. Indentation matches the surrounding
     * userTask element so the resulting XML stays pretty-formatted for debugging.
     */
    private void writeExtensionPropertiesElement(XMLStreamWriter writer, Map<String, String> props)
            throws XMLStreamException {
        if (props.isEmpty()) return;
        writer.writeCharacters("\n      ");
        writer.writeStartElement("extensionElements");
        writeSmartProperties(writer, props, "        ");
        writer.writeCharacters("\n      ");
        writer.writeEndElement(); // extensionElements
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
     */
    private void writeUserTaskAssigneeAttributes(XMLStreamWriter writer, JsonNode config) throws XMLStreamException {
        if (config == null || config.isMissingNode()) {
            return;
        }

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
        } else if (config != null && !config.isMissingNode()) {
            String serviceType = getTextOrNull(config, "serviceType");
            String className = getTextOrNull(config, "className");

            if ("command".equals(serviceType)) {
                // COMMAND service type: bridge to AuraBoot Command engine via CommandServiceTaskDelegate.
                // The delegate reads command configuration from process variables at runtime.
                writer.writeAttribute(SMART_NAMESPACE, "class", "commandServiceTaskDelegate");
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

        if (config != null && !config.isMissingNode()) {
            String calledProcessKey = getTextOrNull(config, "calledProcessKey");
            if (calledProcessKey != null) {
                writer.writeAttribute("calledElement", calledProcessKey);
            }
            String calledProcessVersion = getTextOrNull(config, "calledProcessVersion");
            if (calledProcessVersion != null) {
                writer.writeAttribute(SMART_NAMESPACE, "calledElementVersion", calledProcessVersion);
            }

            // Write input/output mappings as extension elements
            JsonNode inputMappings = config.path("inputMappings");
            JsonNode outputMappings = config.path("outputMappings");
            boolean hasExtensions = (inputMappings.isObject() && inputMappings.size() > 0)
                    || (outputMappings.isObject() && outputMappings.size() > 0);

            if (hasExtensions) {
                writer.writeCharacters("\n      ");
                writer.writeStartElement("extensionElements");

                if (inputMappings.isObject()) {
                    Iterator<Map.Entry<String, JsonNode>> fields = inputMappings.fields();
                    while (fields.hasNext()) {
                        Map.Entry<String, JsonNode> entry = fields.next();
                        writer.writeCharacters("\n        ");
                        writer.writeEmptyElement(SMART_NAMESPACE, "in");
                        writer.writeAttribute("source", entry.getKey());
                        writer.writeAttribute("target", entry.getValue().asText());
                    }
                }

                if (outputMappings.isObject()) {
                    Iterator<Map.Entry<String, JsonNode>> fields = outputMappings.fields();
                    while (fields.hasNext()) {
                        Map.Entry<String, JsonNode> entry = fields.next();
                        writer.writeCharacters("\n        ");
                        writer.writeEmptyElement(SMART_NAMESPACE, "out");
                        writer.writeAttribute("source", entry.getKey());
                        writer.writeAttribute("target", entry.getValue().asText());
                    }
                }

                writer.writeCharacters("\n      ");
                writer.writeEndElement(); // extensionElements
            }
        }

        writer.writeCharacters("\n    ");
        writer.writeEndElement(); // callActivity
        writer.writeCharacters("\n");
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
