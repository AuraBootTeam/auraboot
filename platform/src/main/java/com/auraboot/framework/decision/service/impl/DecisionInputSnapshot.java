package com.auraboot.framework.decision.service.impl;

import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.decision.dto.DecisionFactCatalogDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Bounded, catalogue-allowlisted inputs. Never persists an arbitrary request context. */
final class DecisionInputSnapshot {
    private static final int MAX_FIELDS = 128;
    private static final int MAX_TEXT = 512;
    private DecisionInputSnapshot() {}

    static Map<String, Object> capture(DecisionContext context, Map<String, Object> metadata) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (Object item : metadata.values()) {
            if (!(item instanceof Map<?, ?> fact)) continue;
            String scope = text(fact.get("scope")), path = text(fact.get("path"));
            String key = scope + "." + path;
            if (scope.isBlank() || path.isBlank() || !seen.add(key)) continue;
            if (inputs.size() >= MAX_FIELDS) break;
            Map<String, Object> row = new LinkedHashMap<>();
            for (String name : new String[]{"scope", "path", "label", "modelCode", "dataType", "factKey"}) {
                if (fact.get(name) instanceof String value) row.put(name, value);
            }
            DecisionContext.PathValue value = context.resolve(Scope.fromCode(scope), path);
            String state;
            if (Boolean.TRUE.equals(fact.get("masked"))) state = "REDACTED";
            else if (!value.present()) state = "MISSING";
            else if (value.value() == null) state = "NULL";
            else if (value.value() instanceof Boolean || value.value() instanceof Number
                    || value.value() instanceof String string && string.length() <= MAX_TEXT) {
                state = "VALUE";
                row.put("value", value.value());
            } else state = "OMITTED";
            row.put("state", state);
            inputs.put(key, row);
        }
        return inputs;
    }

    static java.util.List<Map<String,Object>> virtualSources(java.util.List<Map<String,Object>> sources, Map<String,Object> inputs) {
        return sources.stream().map(source -> {
            Map<String,Object> visible = new LinkedHashMap<>(source);
            Map<String,Object> fields = new LinkedHashMap<>();
            if (source.get("fields") instanceof Map<?,?> originals) originals.forEach((key, value) -> {
                if (inputs.get("record.data." + key) instanceof Map<?,?> input && "VALUE".equals(input.get("state"))) fields.put(String.valueOf(key), input.get("value"));
            });
            visible.put("fields", fields);
            return visible;
        }).toList();
    }

    /** Re-authorize at read time: the executing actor's permissions are not the viewer's. */
    static JsonNode project(JsonNode original, ObjectMapper mapper,
                            Function<String, DecisionFactCatalogDTO> catalogue) {
        if (original == null || !original.has("inputSnapshot")) return original;
        ObjectNode result = original.deepCopy();
        ObjectNode inputs = mapper.createObjectNode();
        Map<String, Map<String, Boolean>> allowedByModel = new HashMap<>();
        JsonNode source = original.path("inputSnapshot");
        source.fields().forEachRemaining(entry -> {
            JsonNode row = entry.getValue();
            String model = row.path("modelCode").asText("");
            Map<String, Boolean> allowed = allowedByModel.computeIfAbsent(model, code -> {
                Map<String, Boolean> facts = new HashMap<>();
                DecisionFactCatalogDTO catalog = catalogue.apply(code.isBlank() ? null : code);
                if (catalog != null && catalog.getEntities() != null) {
                    catalog.getEntities().forEach(entity -> {
                        if (entity.getFacts() == null) return;
                        entity.getFacts().forEach(fact -> {
                            if (Boolean.FALSE.equals(fact.getVisible())) return;
                            String factModel = text(fact.getModelCode());
                            if (!code.equals(factModel)) return;
                            facts.put(fact.getScope() + "." + fact.getPath(), Boolean.TRUE.equals(fact.getMasked()));
                        });
                    });
                }
                return facts;
            });
            String key = row.path("scope").asText() + "." + row.path("path").asText();
            if (!allowed.containsKey(key)) return;
            ObjectNode visible = row.deepCopy();
            if (Boolean.TRUE.equals(allowed.get(key))) {
                visible.remove("value");
                visible.put("state", "REDACTED");
            }
            inputs.set(entry.getKey(), visible);
        });
        result.set("inputSnapshot", inputs);
        var conditions = mapper.createArrayNode();
        original.path("conditionSnapshot").forEach(row -> {
            String left = row.path("leftKey").asText(""), right = row.path("rightKey").asText("");
            if (!left.isBlank() && !readable(inputs.path(left))) return;
            if (!right.isBlank() && !readable(inputs.path(right))) return;
            conditions.add(row.deepCopy());
        });
        result.set("conditionSnapshot", conditions);
        // Metadata contains reference labels too. Apply the same per-field decision to it.
        ObjectNode metadata = mapper.createObjectNode();
        original.path("factMetadata").fields().forEachRemaining(entry -> {
            JsonNode fact = entry.getValue();
            String key = fact.path("scope").asText() + "." + fact.path("path").asText();
            if (!inputs.has(key)) return;
            ObjectNode visible = fact.deepCopy();
            if ("REDACTED".equals(inputs.path(key).path("state").asText())) {
                visible.remove("valueLabels");
                visible.put("masked", true);
            }
            metadata.set(entry.getKey(), visible);
        });
        result.set("factMetadata", metadata);
        var sources = mapper.createArrayNode();
        original.path("virtualSources").forEach(sourceRow -> {
            ObjectNode visible = sourceRow.deepCopy();
            ObjectNode fields = mapper.createObjectNode();
            sourceRow.path("fields").fields().forEachRemaining(field -> {
                JsonNode input = inputs.path("record.data." + field.getKey());
                if (readable(input) && "VALUE".equals(input.path("state").asText())) fields.set(field.getKey(), input.path("value"));
            });
            visible.set("fields", fields);
            sources.add(visible);
        });
        result.set("virtualSources", sources);
        return result;
    }

    private static boolean readable(JsonNode input) {
        return java.util.List.of("VALUE", "NULL", "MISSING").contains(input.path("state").asText());
    }

    private static String text(Object value) { return value instanceof String string ? string : ""; }
}
