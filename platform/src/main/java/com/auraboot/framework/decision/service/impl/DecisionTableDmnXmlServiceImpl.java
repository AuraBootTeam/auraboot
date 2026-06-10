package com.auraboot.framework.decision.service.impl;

import com.auraboot.framework.decision.adapter.DroolsDmnAdapter;
import com.auraboot.framework.decision.ast.DataType;
import com.auraboot.framework.decision.ast.Operand;
import com.auraboot.framework.decision.ast.Operator;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.decision.dto.DecisionTableDmnXmlDTO;
import com.auraboot.framework.decision.dto.DecisionTableDmnXmlRequest;
import com.auraboot.framework.decision.model.DecisionKind;
import com.auraboot.framework.decision.model.DecisionValidateResult;
import com.auraboot.framework.decision.model.RuntimeAdapter;
import com.auraboot.framework.decision.model.VersionStatus;
import com.auraboot.framework.decision.runtime.ResolvedDecision;
import com.auraboot.framework.decision.service.DecisionTableDmnXmlService;
import com.auraboot.framework.decision.table.DecisionTable;
import com.auraboot.framework.decision.table.HitPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Converts the platform visual decision-table model to a KIE-compilable OMG DMN decisionTable XML
 * subset and imports that subset back into the editor model. Unsupported external DMN constructs are
 * rejected explicitly so round-trip status is truthful.
 */
@Service
public class DecisionTableDmnXmlServiceImpl implements DecisionTableDmnXmlService {

    private static final String DMN_NS = "https://www.omg.org/spec/DMN/20191111/MODEL/";
    private static final String DEFAULT_NAMESPACE = "https://auraboot/dmn/decision-table";

    private final ObjectMapper mapper;
    private final DroolsDmnAdapter dmnAdapter = new DroolsDmnAdapter();

    public DecisionTableDmnXmlServiceImpl(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public DecisionTableDmnXmlDTO exportDmn(DecisionTableDmnXmlRequest request) {
        DecisionTableDmnXmlDTO dto = new DecisionTableDmnXmlDTO();
        DecisionTable table;
        try {
            table = readTable(request == null ? null : request.getModel());
        } catch (Exception e) {
            dto.addError("DMN_TABLE_PARSE", "Invalid decision table model: " + e.getMessage());
            return dto;
        }

        String decisionName = safeName(textOr(request.getDecisionName(), "decision_table"));
        String decisionId = safeId(textOr(request.getDecisionId(), decisionName));
        String namespace = textOr(request.getNamespace(), DEFAULT_NAMESPACE + "/" + decisionId);
        String dmnXml = toDmnXml(table, decisionId, decisionName, namespace, dto);
        dto.setDmnXml(dmnXml);
        dto.setModel(toEditorModel(table));
        validateDmnXml(dto, dmnXml);
        return dto;
    }

    @Override
    public DecisionTableDmnXmlDTO importDmn(DecisionTableDmnXmlRequest request) {
        DecisionTableDmnXmlDTO dto = new DecisionTableDmnXmlDTO();
        String dmnXml = request == null ? null : request.getDmnXml();
        if (dmnXml == null || dmnXml.isBlank()) {
            dto.addError("DMN_XML_EMPTY", "DMN XML is empty");
            return dto;
        }
        dto.setDmnXml(dmnXml);
        try {
            dto.setModel(importEditorModel(dmnXml));
        } catch (Exception e) {
            dto.addError("DMN_XML_IMPORT", e.getMessage());
            return dto;
        }
        validateDmnXml(dto, dmnXml);
        return dto;
    }

    @Override
    public DecisionTableDmnXmlDTO roundTrip(DecisionTableDmnXmlRequest request) {
        DecisionTableDmnXmlDTO exported = exportDmn(request);
        if (Boolean.FALSE.equals(exported.getValid())) {
            return exported;
        }
        DecisionTableDmnXmlRequest importRequest = new DecisionTableDmnXmlRequest();
        importRequest.setDmnXml(exported.getDmnXml());
        DecisionTableDmnXmlDTO imported = importDmn(importRequest);
        imported.setDmnXml(exported.getDmnXml());
        return imported;
    }

    private DecisionTable readTable(JsonNode model) throws Exception {
        return mapper.treeToValue(normalize(model), DecisionTable.class);
    }

    private JsonNode normalize(JsonNode model) {
        if (model == null || !model.isObject()) {
            return model;
        }
        ObjectNode copy = model.deepCopy();
        JsonNode inputs = copy.path("inputs");
        if (inputs.isArray()) {
            for (JsonNode node : inputs) {
                if (node instanceof ObjectNode input && !input.has("expr")
                        && input.has("scope") && input.has("path")) {
                    ObjectNode expr = mapper.createObjectNode();
                    expr.put("type", "path");
                    expr.put("scope", input.path("scope").asText("record"));
                    expr.put("path", input.path("path").asText());
                    expr.put("dataType", input.path("dataType").asText("string"));
                    input.set("expr", expr);
                    input.remove(List.of("scope", "path", "dataType"));
                }
            }
        }
        return copy;
    }

    private String toDmnXml(DecisionTable table, String decisionId, String decisionName, String namespace,
                            DecisionTableDmnXmlDTO dto) {
        StringBuilder xml = new StringBuilder(4096);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<definitions xmlns=\"").append(DMN_NS).append("\"")
                .append(" namespace=\"").append(escapeXml(namespace)).append("\"")
                .append(" name=\"").append(escapeXml(decisionName)).append("\"")
                .append(" id=\"").append(escapeXml(safeId(decisionId))).append("\">\n");
        for (DecisionTable.Input input : table.inputs()) {
            String varName = dmnInputName(input, dto);
            xml.append("  <inputData id=\"").append(escapeXml("inputData_" + safeId(input.id()))).append("\"")
                    .append(" name=\"").append(escapeXml(varName)).append("\">")
                    .append("<variable name=\"").append(escapeXml(varName)).append("\"")
                    .append(" typeRef=\"").append(dmnType(inputDataType(input))).append("\"/>")
                    .append("</inputData>\n");
        }
        xml.append("  <decision id=\"").append(escapeXml("decision_" + safeId(decisionId))).append("\"")
                .append(" name=\"").append(escapeXml(decisionName)).append("\">\n");
        DataType decisionType = table.outputs().size() == 1 ? table.outputs().get(0).dataType() : DataType.OBJECT;
        xml.append("    <variable name=\"").append(escapeXml(decisionName)).append("\"")
                .append(" typeRef=\"").append(dmnType(decisionType)).append("\"/>\n");
        for (DecisionTable.Input input : table.inputs()) {
            xml.append("    <informationRequirement><requiredInput href=\"#")
                    .append(escapeXml("inputData_" + safeId(input.id())))
                    .append("\"/></informationRequirement>\n");
        }
        xml.append("    <decisionTable id=\"").append(escapeXml("dt_" + safeId(decisionId))).append("\"")
                .append(" hitPolicy=\"").append(table.hitPolicy().name()).append("\"");
        if (table.hitPolicy() == HitPolicy.COLLECT && table.aggregation() != DecisionTable.CollectAggregation.NONE) {
            xml.append(" aggregation=\"").append(table.aggregation().name()).append("\"");
        }
        xml.append(">\n");
        for (DecisionTable.Input input : table.inputs()) {
            String varName = dmnInputName(input, dto);
            xml.append("      <input id=\"").append(escapeXml("input_" + safeId(input.id()))).append("\"")
                    .append(" label=\"").append(escapeXml(textOr(input.label(), input.id()))).append("\">\n")
                    .append("        <inputExpression id=\"").append(escapeXml("inputExpr_" + safeId(input.id()))).append("\"")
                    .append(" typeRef=\"").append(dmnType(inputDataType(input))).append("\">")
                    .append("<text>").append(escapeXml(varName)).append("</text></inputExpression>\n");
            appendAllowedValues(xml, "inputValues", input.allowedValues(), inputDataType(input), 8);
            xml.append("      </input>\n");
        }
        for (DecisionTable.Output output : table.outputs()) {
            xml.append("      <output id=\"").append(escapeXml("output_" + safeId(output.id()))).append("\"")
                    .append(" name=\"").append(escapeXml(output.id())).append("\"")
                    .append(" label=\"").append(escapeXml(textOr(output.label(), output.id()))).append("\"")
                    .append(" typeRef=\"").append(dmnType(output.dataType())).append("\">\n");
            appendAllowedValues(xml, "outputValues", output.allowedValues(), output.dataType(), 8);
            xml.append("      </output>\n");
        }
        int row = 1;
        for (DecisionTable.Rule rule : table.rules()) {
            String ruleId = safeId(textOr(rule.ruleId(), "rule_" + row));
            xml.append("      <rule id=\"").append(escapeXml(ruleId)).append("\">\n");
            for (DecisionTable.Input input : table.inputs()) {
                DecisionTable.Cell cell = rule.when().get(input.id());
                xml.append("        <inputEntry id=\"").append(escapeXml("inputEntry_" + ruleId + "_" + safeId(input.id()))).append("\">")
                        .append("<text>").append(escapeXml(dmnUnaryTest(cell, inputDataType(input)))).append("</text>")
                        .append("</inputEntry>\n");
            }
            for (DecisionTable.Output output : table.outputs()) {
                Object out = rule.then().get(output.id());
                xml.append("        <outputEntry id=\"").append(escapeXml("outputEntry_" + ruleId + "_" + safeId(output.id()))).append("\">")
                        .append("<text>").append(escapeXml(dmnOutputExpression(out, output.dataType()))).append("</text>")
                        .append("</outputEntry>\n");
            }
            xml.append("      </rule>\n");
            row += 1;
        }
        xml.append("    </decisionTable>\n");
        xml.append("  </decision>\n");
        xml.append("</definitions>\n");
        return xml.toString();
    }

    private ObjectNode importEditorModel(String dmnXml) throws Exception {
        Document doc = parseXml(dmnXml);
        Element table = first(doc.getDocumentElement(), "decisionTable");
        if (table == null) {
            throw new IllegalArgumentException("Only DMN decisionTable import is supported");
        }

        ObjectNode model = mapper.createObjectNode();
        String hitPolicy = attrOr(table, "hitPolicy", "FIRST");
        model.put("hitPolicy", hitPolicy);
        model.put("aggregation", attrOr(table, "aggregation", "NONE"));

        ArrayNode inputs = mapper.createArrayNode();
        List<Element> inputElements = children(table, "input");
        for (Element inputEl : inputElements) {
            Element inputExpression = first(inputEl, "inputExpression");
            String expression = textOf(first(inputExpression, "text"));
            String id = platformInputId(inputEl, expression);
            DataType dataType = fromDmnType(attrOr(inputExpression, "typeRef", "string"));
            ObjectNode input = mapper.createObjectNode();
            input.put("id", id);
            input.put("label", attrOr(inputEl, "label", id));
            input.put("scope", "record");
            input.put("path", "data." + expression);
            input.put("dataType", dataType.code());
            ArrayNode allowed = allowedValues(inputEl, "inputValues", dataType);
            if (allowed.size() > 0) {
                input.set("allowedValues", allowed);
            }
            inputs.add(input);
        }
        model.set("inputs", inputs);

        ArrayNode outputs = mapper.createArrayNode();
        List<Element> outputElements = children(table, "output");
        for (Element outputEl : outputElements) {
            String id = attrOr(outputEl, "name", stripPrefix(attrOr(outputEl, "id", "output"), "output_"));
            DataType dataType = fromDmnType(attrOr(outputEl, "typeRef", "string"));
            ObjectNode output = mapper.createObjectNode();
            output.put("id", id);
            output.put("label", attrOr(outputEl, "label", id));
            output.put("dataType", dataType.code());
            ArrayNode allowed = allowedValues(outputEl, "outputValues", dataType);
            if (allowed.size() > 0) {
                output.set("allowedValues", allowed);
            }
            outputs.add(output);
        }
        model.set("outputs", outputs);

        ArrayNode rules = mapper.createArrayNode();
        int priority = 10;
        for (Element ruleEl : children(table, "rule")) {
            ObjectNode rule = mapper.createObjectNode();
            rule.put("ruleId", attrOr(ruleEl, "id", "rule_" + priority));
            rule.put("priority", priority);
            ObjectNode when = mapper.createObjectNode();
            List<Element> inputEntries = children(ruleEl, "inputEntry");
            for (int i = 0; i < Math.min(inputEntries.size(), inputElements.size()); i += 1) {
                String inputId = inputs.get(i).path("id").asText();
                ObjectNode cell = mapper.createObjectNode();
                cell.put("operator", "EQ");
                cell.put("value", "");
                cell.put("feel", textOf(first(inputEntries.get(i), "text")));
                when.set(inputId, cell);
            }
            rule.set("when", when);
            ObjectNode then = mapper.createObjectNode();
            List<Element> outputEntries = children(ruleEl, "outputEntry");
            for (int i = 0; i < Math.min(outputEntries.size(), outputElements.size()); i += 1) {
                String outputId = outputs.get(i).path("id").asText();
                DataType outputType = fromDmnType(attrOr(outputElements.get(i), "typeRef", "string"));
                then.set(outputId, mapper.valueToTree(parseLiteral(textOf(first(outputEntries.get(i), "text")), outputType)));
            }
            rule.set("then", then);
            rules.add(rule);
            priority += 10;
        }
        model.set("rules", rules);
        model.set("defaultOutput", mapper.createObjectNode());
        return model;
    }

    private ObjectNode toEditorModel(DecisionTable table) {
        ObjectNode model = mapper.createObjectNode();
        model.put("hitPolicy", table.hitPolicy().name());
        model.put("aggregation", table.aggregation().name());
        ArrayNode inputs = mapper.createArrayNode();
        for (DecisionTable.Input source : table.inputs()) {
            ObjectNode input = mapper.createObjectNode();
            input.put("id", source.id());
            input.put("label", textOr(source.label(), source.id()));
            if (source.expr() instanceof Operand.PathOperand path) {
                input.put("scope", path.scope().name().toLowerCase(Locale.ROOT));
                input.put("path", path.path());
                input.put("dataType", path.dataType() == null ? "string" : path.dataType().code());
            }
            if (!source.allowedValues().isEmpty()) {
                input.set("allowedValues", mapper.valueToTree(source.allowedValues()));
            }
            inputs.add(input);
        }
        model.set("inputs", inputs);
        model.set("outputs", mapper.valueToTree(table.outputs()));
        model.set("rules", mapper.valueToTree(table.rules()));
        model.set("defaultOutput", mapper.valueToTree(table.defaultOutput()));
        return model;
    }

    private void validateDmnXml(DecisionTableDmnXmlDTO dto, String dmnXml) {
        DecisionValidateResult result = dmnAdapter.validate(new ResolvedDecision(
                "decision_table_xml", 1, null, VersionStatus.DRAFT,
                DecisionKind.DMN, RuntimeAdapter.DROOLS_DMN, TextNode.valueOf(dmnXml)));
        if (!result.valid()) {
            for (DecisionValidateResult.Issue issue : result.errors()) {
                dto.addError(issue.code(), issue.message());
            }
        }
        for (DecisionValidateResult.Issue issue : result.warnings()) {
            dto.addWarning(issue.code(), issue.message());
        }
    }

    private Document parseXml(String dmnXml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        secure(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        secure(factory, "http://xml.org/sax/features/external-general-entities", false);
        secure(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        secure(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(dmnXml)));
    }

    private void secure(DocumentBuilderFactory factory, String feature, boolean value) throws Exception {
        factory.setFeature(feature, value);
    }

    private List<Element> children(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        if (parent == null) {
            return result;
        }
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i += 1) {
            Node node = nodes.item(i);
            if (node instanceof Element child && localName.equals(child.getLocalName())) {
                result.add(child);
            }
        }
        return result;
    }

    private Element first(Element parent, String localName) {
        if (parent == null) {
            return null;
        }
        if (localName.equals(parent.getLocalName())) {
            return parent;
        }
        NodeList nodes = parent.getElementsByTagNameNS("*", localName);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    private String textOf(Element element) {
        return element == null ? "" : element.getTextContent().trim();
    }

    private String attrOr(Element element, String attr, String fallback) {
        return element != null && element.hasAttribute(attr) && !element.getAttribute(attr).isBlank()
                ? element.getAttribute(attr)
                : fallback;
    }

    private void appendAllowedValues(StringBuilder xml, String tag, List<Object> values, DataType dataType, int spaces) {
        if (values == null || values.isEmpty()) {
            return;
        }
        xml.append(" ".repeat(spaces))
                .append("<").append(tag).append("><text>")
                .append(escapeXml(values.stream().map(value -> dmnLiteral(value, dataType)).reduce((a, b) -> a + ", " + b).orElse("")))
                .append("</text></").append(tag).append(">\n");
    }

    private ArrayNode allowedValues(Element parent, String tag, DataType dataType) {
        ArrayNode values = mapper.createArrayNode();
        Element valueElement = first(parent, tag);
        if (valueElement == null) {
            return values;
        }
        for (String part : splitFeelList(textOf(first(valueElement, "text")))) {
            values.addPOJO(parseLiteral(part, dataType));
        }
        return values;
    }

    private List<String> splitFeelList(String text) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        char quote = 0;
        for (int i = 0; i < text.length(); i += 1) {
            char c = text.charAt(i);
            if ((c == '"' || c == '\'') && (i == 0 || text.charAt(i - 1) != '\\')) {
                if (!inString) {
                    inString = true;
                    quote = c;
                } else if (quote == c) {
                    inString = false;
                }
            }
            if (c == ',' && !inString) {
                result.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            result.add(tail);
        }
        return result;
    }

    private DataType inputDataType(DecisionTable.Input input) {
        return input.expr() == null || input.expr().dataType() == null ? DataType.STRING : input.expr().dataType();
    }

    private String dmnInputName(DecisionTable.Input input, DecisionTableDmnXmlDTO dto) {
        if (input.expr() instanceof Operand.PathOperand path) {
            if (path.scope() != Scope.RECORD) {
                dto.addWarning("DMN_SCOPE_FLATTENING",
                        "Current Drools DMN adapter flattens record data only; exported input " + input.id()
                                + " uses variable name from path " + path.path());
            }
            String p = path.path();
            if (p != null && p.startsWith("data.") && p.length() > 5) {
                return safeName(p.substring(5));
            }
            if (p != null && !p.isBlank()) {
                return safeName(p.substring(p.lastIndexOf('.') + 1));
            }
        }
        return safeName(input.id());
    }

    private String platformInputId(Element inputEl, String expression) {
        String id = stripPrefix(attrOr(inputEl, "id", expression), "input_");
        return safeName(id.isBlank() ? expression : id);
    }

    private String dmnUnaryTest(DecisionTable.Cell cell, DataType dataType) {
        if (cell == null) {
            return "-";
        }
        if (cell.feel() != null && !cell.feel().isBlank()) {
            return normalizeFeelForDmn(cell.feel(), dataType);
        }
        Object value = cell.value();
        Operator op = cell.operator() == null ? Operator.EQ : cell.operator();
        return switch (op) {
            case GT -> "> " + dmnLiteral(value, dataType);
            case GTE -> ">= " + dmnLiteral(value, dataType);
            case LT -> "< " + dmnLiteral(value, dataType);
            case LTE -> "<= " + dmnLiteral(value, dataType);
            case NE -> "not(" + dmnLiteral(value, dataType) + ")";
            case BETWEEN -> {
                if (value instanceof List<?> values && values.size() >= 2) {
                    yield "[" + dmnLiteral(values.get(0), dataType) + ".." + dmnLiteral(values.get(1), dataType) + "]";
                }
                yield "-";
            }
            case IN -> {
                if (value instanceof List<?> values) {
                    yield values.stream().map(item -> dmnLiteral(item, dataType)).reduce((a, b) -> a + ", " + b).orElse("-");
                }
                yield value == null ? "-" : dmnLiteral(value, dataType);
            }
            case IS_NULL -> "null";
            case IS_NOT_NULL -> "not(null)";
            default -> dmnLiteral(value, dataType);
        };
    }

    private String normalizeFeelForDmn(String raw, DataType dataType) {
        String text = raw.trim();
        if (text.isEmpty() || "-".equals(text) || !isStringLike(dataType)) {
            return text;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.equals("null") || lower.equals("not(null)") || lower.equals("not null")
                || text.startsWith("[") || text.startsWith("\"") || text.startsWith("'")) {
            return text;
        }
        String comparisonRegex = "^(>=|<=|>|<|!=|=)\\s*(.+)$";
        if (text.matches(comparisonRegex)) {
            return text.replaceFirst(comparisonRegex, "$1 " + dmnLiteral(text.replaceFirst(comparisonRegex, "$2"), dataType));
        }
        if (text.contains(",")) {
            return splitFeelList(text).stream()
                    .map(part -> dmnLiteral(part, dataType))
                    .reduce((a, b) -> a + ", " + b)
                    .orElse(text);
        }
        return dmnLiteral(text, dataType);
    }

    private String dmnOutputExpression(Object value, DataType dataType) {
        return dmnLiteral(value, dataType);
    }

    private String dmnLiteral(Object value, DataType dataType) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        String text = String.valueOf(value).trim();
        if (text.equalsIgnoreCase("null")) {
            return "null";
        }
        if (dataType == DataType.BOOLEAN && (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false"))) {
            return text.toLowerCase(Locale.ROOT);
        }
        if (dataType != null && dataType.isNumeric() && !text.isBlank()) {
            return text;
        }
        if ((text.startsWith("\"") && text.endsWith("\"")) || (text.startsWith("'") && text.endsWith("'"))) {
            return "\"" + escapeFeelString(text.substring(1, text.length() - 1)) + "\"";
        }
        return "\"" + escapeFeelString(text) + "\"";
    }

    private Object parseLiteral(String raw, DataType dataType) {
        String text = raw == null ? "" : raw.trim();
        if ((text.startsWith("\"") && text.endsWith("\"")) || (text.startsWith("'") && text.endsWith("'"))) {
            return text.substring(1, text.length() - 1);
        }
        if (text.equalsIgnoreCase("null")) {
            return null;
        }
        if (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false")) {
            return Boolean.valueOf(text);
        }
        if (dataType == DataType.INTEGER) {
            return Integer.valueOf(text);
        }
        if (dataType == DataType.DECIMAL) {
            return new BigDecimal(text);
        }
        return text;
    }

    private String dmnType(DataType dataType) {
        if (dataType == null) {
            return "string";
        }
        return switch (dataType) {
            case INTEGER, DECIMAL -> "number";
            case BOOLEAN -> "boolean";
            case DATE -> "date";
            case DATETIME -> "dateTime";
            case OBJECT -> "context";
            default -> "string";
        };
    }

    private DataType fromDmnType(String type) {
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        return switch (t) {
            case "number", "double", "decimal" -> DataType.DECIMAL;
            case "integer", "long", "int" -> DataType.INTEGER;
            case "boolean" -> DataType.BOOLEAN;
            case "date" -> DataType.DATE;
            case "datetime" -> DataType.DATETIME;
            case "context" -> DataType.OBJECT;
            default -> DataType.STRING;
        };
    }

    private boolean isStringLike(DataType dataType) {
        return dataType == DataType.STRING || dataType == DataType.TEXT || dataType == DataType.ENUM
                || dataType == DataType.DICT || dataType == DataType.USER || dataType == DataType.ROLE
                || dataType == DataType.GROUP || dataType == DataType.DEPARTMENT;
    }

    private String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String safeName(String value) {
        String source = value == null || value.isBlank() ? "decision_table" : value.trim();
        return source.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private String safeId(String value) {
        String id = safeName(value).replace('.', '_').replace('-', '_');
        if (!id.matches("[A-Za-z_].*")) {
            id = "_" + id;
        }
        return id;
    }

    private String stripPrefix(String value, String prefix) {
        return value != null && value.startsWith(prefix) ? value.substring(prefix.length()) : value;
    }

    private String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private String escapeFeelString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
