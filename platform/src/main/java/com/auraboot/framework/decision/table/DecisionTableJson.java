package com.auraboot.framework.decision.table;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Bridges the visual decision-table editor model into the runtime table model.
 */
public final class DecisionTableJson {

    private DecisionTableJson() {
    }

    public static JsonNode normalizeEditorModel(ObjectMapper mapper, JsonNode model) {
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
}
