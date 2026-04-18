package com.auraboot.framework.bpm.converter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.*;

/**
 * Converts BPMN 2.0 XML to frontend designer JSON format.
 *
 * <p>Input: BPMN 2.0 XML string (as deployed in SmartEngine).
 * <p>Output: JSON matching the React @xyflow/react designer's expected node/edge structure.
 *
 * <p>Used when loading existing deployed process definitions back into the designer.
 *
 * @see JsonToBpmnConverter for the forward direction
 */
@Slf4j
@Component
public class BpmnToJsonConverter {

    private static final String BPMN_NAMESPACE = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    private static final String SMART_NAMESPACE = "http://smartengine.org/schema/process";
    // Also support AuraBoot's own smart namespace variant
    private static final String SMART_NAMESPACE_ALT = "http://smart.alibaba.com";

    // Layout constants for auto-positioning when no position data exists
    private static final int LAYOUT_START_X = 100;
    private static final int LAYOUT_START_Y = 200;
    private static final int LAYOUT_X_SPACING = 200;

    private final ObjectMapper objectMapper;

    public BpmnToJsonConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Convert BPMN 2.0 XML string to designer JSON string.
     *
     * @param bpmnXml the BPMN 2.0 XML string
     * @return JSON string matching frontend designer format
     * @throws BpmnConversionException if conversion fails
     */
    public String convert(String bpmnXml) {
        try {
            ObjectNode result = convertToJsonNode(bpmnXml);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        } catch (BpmnConversionException e) {
            throw e;
        } catch (Exception e) {
            throw new BpmnConversionException("Failed to convert BPMN XML to JSON", e);
        }
    }

    /**
     * Convert BPMN 2.0 XML string to a Jackson ObjectNode.
     *
     * @param bpmnXml the BPMN 2.0 XML string
     * @return ObjectNode matching frontend designer format
     * @throws BpmnConversionException if conversion fails
     */
    public ObjectNode convertToJsonNode(String bpmnXml) {
        try {
            Document document = parseXml(bpmnXml);
            return extractProcessDefinition(document);
        } catch (BpmnConversionException e) {
            throw e;
        } catch (Exception e) {
            throw new BpmnConversionException("Failed to parse BPMN XML", e);
        }
    }

    /**
     * Convert BPMN 2.0 XML string to a Map.
     *
     * @param bpmnXml the BPMN 2.0 XML string
     * @return Map matching frontend designer format
     * @throws BpmnConversionException if conversion fails
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> convertToMap(String bpmnXml) {
        try {
            ObjectNode jsonNode = convertToJsonNode(bpmnXml);
            return objectMapper.convertValue(jsonNode, Map.class);
        } catch (BpmnConversionException e) {
            throw e;
        } catch (Exception e) {
            throw new BpmnConversionException("Failed to convert BPMN XML to Map", e);
        }
    }

    // ==================== XML Parsing ====================

    private Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        // Security: disable external entities
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    private ObjectNode extractProcessDefinition(Document document) {
        ObjectNode result = objectMapper.createObjectNode();

        // Find the <process> element (may be prefixed with bpmn2: or not)
        Element processElement = findProcessElement(document);
        if (processElement == null) {
            throw new BpmnConversionException("No <process> element found in BPMN XML");
        }

        String processId = processElement.getAttribute("id");
        String processName = processElement.getAttribute("name");

        result.put("key", processId);
        result.put("name", processName != null && !processName.isEmpty() ? processName : processId);

        // Parse all child elements of <process>
        ArrayNode nodesArray = objectMapper.createArrayNode();
        ArrayNode edgesArray = objectMapper.createArrayNode();

        // Collect default flow mappings: gatewayId -> defaultFlowId
        Map<String, String> gatewayDefaults = new HashMap<>();

        // First pass: identify gateways with default flows
        NodeList children = processElement.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            Element element = (Element) child;
            String localName = element.getLocalName();

            if ("exclusiveGateway".equals(localName) || "parallelGateway".equals(localName) || "inclusiveGateway".equals(localName)) {
                String defaultFlow = element.getAttribute("default");
                if (defaultFlow != null && !defaultFlow.isEmpty()) {
                    gatewayDefaults.put(element.getAttribute("id"), defaultFlow);
                }
            }
        }

        // Second pass: convert all elements
        int nodeIndex = 0;
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            Element element = (Element) child;
            String localName = element.getLocalName();

            if ("sequenceFlow".equals(localName)) {
                ObjectNode edge = convertSequenceFlow(element, gatewayDefaults);
                if (edge != null) {
                    edgesArray.add(edge);
                }
            } else if (isBpmnNodeElement(localName)) {
                ObjectNode node = convertNodeElement(element, localName, nodeIndex);
                if (node != null) {
                    nodesArray.add(node);
                    nodeIndex++;
                }
            }
        }

        result.set("nodes", nodesArray);
        result.set("edges", edgesArray);

        log.debug("Converted BPMN XML to JSON: processKey={}, nodeCount={}, edgeCount={}",
                processId, nodesArray.size(), edgesArray.size());

        return result;
    }

    // ==================== Element Converters ====================

    private ObjectNode convertNodeElement(Element element, String localName, int index) {
        ObjectNode node = objectMapper.createObjectNode();
        String id = element.getAttribute("id");
        String name = element.getAttribute("name");

        node.put("id", id);
        node.put("type", localName);

        // Auto-layout position (evenly spaced horizontally)
        ObjectNode position = objectMapper.createObjectNode();
        position.put("x", LAYOUT_START_X + (index * LAYOUT_X_SPACING));
        position.put("y", LAYOUT_START_Y);
        node.set("position", position);

        // Build data object
        ObjectNode data = objectMapper.createObjectNode();
        data.put("type", localName);
        data.put("label", name != null && !name.isEmpty() ? name : id);

        // Build config based on node type
        ObjectNode config = buildNodeConfig(element, localName);
        if (config != null && config.size() > 0) {
            data.set("config", config);
        }

        node.set("data", data);
        return node;
    }

    private ObjectNode buildNodeConfig(Element element, String localName) {
        return switch (localName) {
            case "userTask" -> buildUserTaskConfig(element);
            case "serviceTask" -> buildServiceTaskConfig(element);
            case "receiveTask" -> buildReceiveTaskConfig(element);
            case "exclusiveGateway" -> buildExclusiveGatewayConfig(element);
            case "parallelGateway" -> buildParallelGatewayConfig(element);
            case "inclusiveGateway" -> buildInclusiveGatewayConfig(element);
            case "callActivity" -> buildCallActivityConfig(element);
            case "startEvent" -> buildStartEventConfig(element);
            case "endEvent" -> buildEndEventConfig(element);
            default -> null;
        };
    }

    private ObjectNode buildUserTaskConfig(Element element) {
        ObjectNode config = objectMapper.createObjectNode();

        String description = element.getAttribute("documentation");
        if (description != null && !description.isEmpty()) {
            config.put("description", description);
        }

        // Parse assignee from smart:* attributes
        String assigneeType = getSmartAttribute(element, "assigneeType");
        String assigneeId = getSmartAttribute(element, "assigneeId");
        String assigneeExpression = getSmartAttribute(element, "assignee");

        ObjectNode assigneeConfig = objectMapper.createObjectNode();
        boolean hasAssignee = false;

        if (assigneeExpression != null && !assigneeExpression.isEmpty()) {
            assigneeConfig.put("type", "expression");
            assigneeConfig.put("expression", assigneeExpression);
            hasAssignee = true;
        } else if (assigneeType != null && !assigneeType.isEmpty()) {
            assigneeConfig.put("type", assigneeType);

            if (assigneeId != null && !assigneeId.isEmpty()) {
                // Map assigneeId back to the appropriate array
                ArrayNode idsArray = objectMapper.createArrayNode();
                idsArray.add(assigneeId);

                switch (assigneeType) {
                    case "user" -> assigneeConfig.set("userIds", idsArray);
                    case "role" -> assigneeConfig.set("roleIds", idsArray);
                    case "dept" -> assigneeConfig.set("deptIds", idsArray);
                }
            }
            hasAssignee = true;
        }

        if (hasAssignee) {
            config.set("assignee", assigneeConfig);
        }

        // Parse candidateUsers
        String candidateUsers = getSmartAttribute(element, "candidateUsers");
        if (candidateUsers != null && !candidateUsers.isEmpty()) {
            ArrayNode usersArray = objectMapper.createArrayNode();
            for (String user : candidateUsers.split(",")) {
                usersArray.add(user.trim());
            }
            config.set("candidateUsers", usersArray);
        }

        // Parse candidateGroups
        String candidateGroups = getSmartAttribute(element, "candidateGroups");
        if (candidateGroups != null && !candidateGroups.isEmpty()) {
            ArrayNode groupsArray = objectMapper.createArrayNode();
            for (String group : candidateGroups.split(",")) {
                groupsArray.add(group.trim());
            }
            config.set("candidateGroups", groupsArray);
        }

        // Parse multiInstanceLoopCharacteristics
        Element multiInstanceEl = findChildElement(element, "multiInstanceLoopCharacteristics");
        if (multiInstanceEl != null) {
            ObjectNode multiInstance = buildMultiInstanceConfig(multiInstanceEl);
            config.set("multiInstance", multiInstance);
        }

        return config;
    }

    private ObjectNode buildServiceTaskConfig(Element element) {
        ObjectNode config = objectMapper.createObjectNode();

        String smartClass = getSmartAttribute(element, "class");
        if (smartClass != null && !smartClass.isEmpty()) {
            config.put("serviceType", "java");
            config.put("className", smartClass);
        }

        String async = getSmartAttribute(element, "async");
        if ("true".equals(async)) {
            config.put("async", true);
        }

        // Parse multiInstanceLoopCharacteristics
        Element multiInstanceEl = findChildElement(element, "multiInstanceLoopCharacteristics");
        if (multiInstanceEl != null) {
            ObjectNode multiInstance = buildMultiInstanceConfig(multiInstanceEl);
            config.set("multiInstance", multiInstance);
        }

        return config;
    }

    private ObjectNode buildReceiveTaskConfig(Element element) {
        ObjectNode config = objectMapper.createObjectNode();
        // ReceiveTask has minimal config in the current model
        return config;
    }

    private ObjectNode buildExclusiveGatewayConfig(Element element) {
        ObjectNode config = objectMapper.createObjectNode();

        String defaultFlow = element.getAttribute("default");
        if (defaultFlow != null && !defaultFlow.isEmpty()) {
            config.put("defaultFlow", defaultFlow);
        }

        return config;
    }

    private ObjectNode buildStartEventConfig(Element element) {
        // Start events typically have no special config from BPMN
        return objectMapper.createObjectNode();
    }

    private ObjectNode buildEndEventConfig(Element element) {
        // End events typically have no special config from BPMN
        return objectMapper.createObjectNode();
    }

    private ObjectNode buildParallelGatewayConfig(Element element) {
        ObjectNode config = objectMapper.createObjectNode();
        String defaultFlow = element.getAttribute("default");
        if (defaultFlow != null && !defaultFlow.isEmpty()) {
            config.put("defaultFlow", defaultFlow);
        }
        return config;
    }

    private ObjectNode buildInclusiveGatewayConfig(Element element) {
        ObjectNode config = objectMapper.createObjectNode();
        String defaultFlow = element.getAttribute("default");
        if (defaultFlow != null && !defaultFlow.isEmpty()) {
            config.put("defaultFlow", defaultFlow);
        }
        return config;
    }

    private ObjectNode buildCallActivityConfig(Element element) {
        ObjectNode config = objectMapper.createObjectNode();
        String calledElement = element.getAttribute("calledElement");
        if (calledElement != null && !calledElement.isEmpty()) {
            config.put("calledProcessKey", calledElement);
        }
        String calledVersion = getSmartAttribute(element, "calledElementVersion");
        if (calledVersion != null) {
            config.put("calledProcessVersion", calledVersion);
        }

        // Parse <extensionElements><smart:properties><smart:property
        // name="aura.callMappings" value='{"inputs":{...},"outputs":{...}}'/>
        // — the GAP-250 follow-up mechanism used by AuraCallActivityListener
        // to bridge SmartEngine's parent/child request-map isolation. This
        // replaces the legacy <smart:in>/<smart:out> scheme that SmartEngine's
        // BPMN parser rejected. We still tolerate the legacy form here so that
        // exports from older AuraBoot versions round-trip back into designer
        // JSON without loss.
        Element extensionElements = findChildElement(element, "extensionElements");
        if (extensionElements != null) {
            ObjectNode inputMappings = objectMapper.createObjectNode();
            ObjectNode outputMappings = objectMapper.createObjectNode();

            // Preferred path: aura.callMappings smart:property.
            String callMappingsJson = readAuraProperty(extensionElements, "aura.callMappings");
            if (callMappingsJson != null && !callMappingsJson.isBlank()) {
                try {
                    Map<String, Map<String, String>> decoded = objectMapper.readValue(
                            callMappingsJson,
                            objectMapper.getTypeFactory().constructMapType(
                                    LinkedHashMap.class,
                                    objectMapper.getTypeFactory().constructType(String.class),
                                    objectMapper.getTypeFactory().constructMapType(
                                            LinkedHashMap.class, String.class, String.class)));
                    Map<String, String> inputs = decoded.get("inputs");
                    if (inputs != null) {
                        for (Map.Entry<String, String> e : inputs.entrySet()) {
                            inputMappings.put(e.getKey(), e.getValue());
                        }
                    }
                    Map<String, String> outputs = decoded.get("outputs");
                    if (outputs != null) {
                        for (Map.Entry<String, String> e : outputs.entrySet()) {
                            outputMappings.put(e.getKey(), e.getValue());
                        }
                    }
                } catch (Exception e) {
                    // Surface malformed payload loudly rather than silently
                    // dropping mappings — callers rely on round-trip fidelity.
                    throw new BpmnConversionException(
                            "Malformed aura.callMappings payload on callActivity "
                                    + element.getAttribute("id") + ": " + callMappingsJson, e);
                }
            }

            // Legacy path: <smart:in>/<smart:out> direct children. Retained for
            // backward compatibility with artifacts exported before GAP-250.
            NodeList children = extensionElements.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child.getNodeType() != Node.ELEMENT_NODE) continue;
                Element ext = (Element) child;
                String localName = ext.getLocalName();
                if ("in".equals(localName)) {
                    String source = ext.getAttribute("source");
                    String target = ext.getAttribute("target");
                    if (source != null && target != null && !source.isEmpty()) {
                        inputMappings.put(source, target);
                    }
                } else if ("out".equals(localName)) {
                    String source = ext.getAttribute("source");
                    String target = ext.getAttribute("target");
                    if (source != null && target != null && !source.isEmpty()) {
                        outputMappings.put(source, target);
                    }
                }
            }

            if (inputMappings.size() > 0) config.set("inputMappings", inputMappings);
            if (outputMappings.size() > 0) config.set("outputMappings", outputMappings);
        }

        return config;
    }

    /**
     * Read {@code <smart:property name="<key>" value="..."/>} from a
     * {@code <extensionElements><smart:properties>} container. Returns
     * {@code null} when the key is absent.
     */
    private String readAuraProperty(Element extensionElements, String key) {
        NodeList children = extensionElements.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            Element ext = (Element) child;
            if (!"properties".equals(ext.getLocalName())) continue;
            NodeList propChildren = ext.getChildNodes();
            for (int j = 0; j < propChildren.getLength(); j++) {
                Node pNode = propChildren.item(j);
                if (pNode.getNodeType() != Node.ELEMENT_NODE) continue;
                Element prop = (Element) pNode;
                if (!"property".equals(prop.getLocalName())) continue;
                if (key.equals(prop.getAttribute("name"))) {
                    return prop.getAttribute("value");
                }
            }
        }
        return null;
    }

    /**
     * Build multiInstance config from a multiInstanceLoopCharacteristics element.
     */
    private ObjectNode buildMultiInstanceConfig(Element multiInstanceEl) {
        ObjectNode multiInstance = objectMapper.createObjectNode();
        multiInstance.put("enabled", true);
        multiInstance.put("sequential", "true".equals(multiInstanceEl.getAttribute("isSequential")));

        String collection = getSmartAttribute(multiInstanceEl, "collection");
        if (collection != null) multiInstance.put("collection", collection);
        String elementVariable = getSmartAttribute(multiInstanceEl, "elementVariable");
        if (elementVariable != null) multiInstance.put("elementVariable", elementVariable);

        Element loopCardinalityEl = findChildElement(multiInstanceEl, "loopCardinality");
        if (loopCardinalityEl != null) {
            try {
                multiInstance.put("loopCardinality", Integer.parseInt(loopCardinalityEl.getTextContent().trim()));
            } catch (NumberFormatException ignored) {}
        }

        Element completionConditionEl = findChildElement(multiInstanceEl, "completionCondition");
        if (completionConditionEl != null) {
            String cc = completionConditionEl.getTextContent();
            if (cc != null && !cc.trim().isEmpty()) {
                multiInstance.put("completionCondition", cc.trim());
            }
        }

        return multiInstance;
    }

    private ObjectNode convertSequenceFlow(Element element, Map<String, String> gatewayDefaults) {
        ObjectNode edge = objectMapper.createObjectNode();

        String id = element.getAttribute("id");
        String sourceRef = element.getAttribute("sourceRef");
        String targetRef = element.getAttribute("targetRef");
        String name = element.getAttribute("name");

        edge.put("id", id);
        edge.put("source", sourceRef);
        edge.put("target", targetRef);

        ObjectNode edgeData = objectMapper.createObjectNode();
        if (name != null && !name.isEmpty()) {
            edgeData.put("label", name);
        }

        // Check if this is a default flow for any gateway
        boolean isDefault = gatewayDefaults.entrySet().stream()
                .anyMatch(entry -> id.equals(entry.getValue()) && sourceRef.equals(entry.getKey()));
        if (isDefault) {
            edgeData.put("isDefault", true);
        }

        // Parse condition expression. Preserve "language" attribute (e.g. mvel/juel) so the
        // designer round-trip keeps script-type conditions intact.
        Element conditionElement = findChildElement(element, "conditionExpression");
        if (conditionElement != null) {
            String conditionContent = conditionElement.getTextContent();
            if (conditionContent != null && !conditionContent.trim().isEmpty()) {
                ObjectNode condition = objectMapper.createObjectNode();
                String language = conditionElement.getAttribute("language");
                if (language != null && !language.isEmpty()) {
                    condition.put("type", "script");
                    condition.put("language", language);
                } else {
                    condition.put("type", "expression");
                }
                condition.put("content", conditionContent.trim());
                edgeData.set("condition", condition);
            }
        }

        edge.set("data", edgeData);
        return edge;
    }

    // ==================== Utility Methods ====================

    private Element findProcessElement(Document document) {
        // Try without namespace prefix first
        NodeList processes = document.getElementsByTagNameNS(BPMN_NAMESPACE, "process");
        if (processes.getLength() > 0) {
            return (Element) processes.item(0);
        }

        // Try without namespace (some BPMN files use default namespace)
        processes = document.getElementsByTagName("process");
        if (processes.getLength() > 0) {
            return (Element) processes.item(0);
        }

        return null;
    }

    private boolean isBpmnNodeElement(String localName) {
        return localName != null && Set.of(
                "startEvent", "endEvent", "userTask", "serviceTask",
                "receiveTask", "exclusiveGateway", "parallelGateway",
                "inclusiveGateway", "callActivity"
        ).contains(localName);
    }

    /**
     * Get a SmartEngine extension attribute value.
     * Tries both known smart namespace URIs.
     */
    private String getSmartAttribute(Element element, String attrName) {
        String value = element.getAttributeNS(SMART_NAMESPACE, attrName);
        if (value != null && !value.isEmpty()) {
            return value;
        }

        value = element.getAttributeNS(SMART_NAMESPACE_ALT, attrName);
        if (value != null && !value.isEmpty()) {
            return value;
        }

        // Fallback: try with smart: prefix in no-namespace attributes
        value = element.getAttribute("smart:" + attrName);
        if (value != null && !value.isEmpty()) {
            return value;
        }

        return null;
    }

    private Element findChildElement(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                Element childElement = (Element) child;
                if (localName.equals(childElement.getLocalName())) {
                    return childElement;
                }
            }
        }
        return null;
    }
}
