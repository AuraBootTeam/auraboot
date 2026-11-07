package com.auraboot.framework.permission.service;

import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.permission.engine.model.BusinessGrantDecision;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Strict materialized grant fields, interpreted only after its scope and conditions match. */
final class BusinessGrantFieldScope {
    private static final int MAX_MODELS = 32;
    private BusinessGrantFieldScope() { }

    record Document(boolean legacy, boolean versionTwo, Map<String, BusinessGrantDecision.Fields> models) {
        Document { models = Collections.unmodifiableMap(new TreeMap<>(models)); }
        Optional<BusinessGrantDecision.Fields> select(String model) {
            return legacy ? Optional.of(BusinessGrantDecision.Fields.legacy()) : Optional.ofNullable(models.get(model));
        }
    }

    static BusinessGrantDecision.Fields parse(ObjectMapper json, String raw, String modelCode) {
        return select(json, raw, modelCode).orElseThrow(BusinessGrantFieldScope::invalid);
    }

    /** An omitted model does not grant even a fixed action with an empty field list. */
    static Optional<BusinessGrantDecision.Fields> select(ObjectMapper json, String raw, String modelCode) {
        return decode(json, raw).select(modelCode);
    }

    static Document decode(ObjectMapper json, String raw) {
        if (raw == null) return new Document(true, false, Map.of());
        try {
            JsonNode node = json.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(raw);
            if (node == null || !node.isObject()) throw invalid();
            if (node.has("version")) {
                if (node.size() != 2 || !node.path("version").isIntegralNumber()
                        || !node.path("version").canConvertToInt() || node.path("version").intValue() != 2
                        || !node.path("models").isObject() || node.path("models").isEmpty()
                        || node.path("models").size() > MAX_MODELS) throw invalid();
                Map<String, BusinessGrantDecision.Fields> models = new TreeMap<>();
                var entries = node.path("models").fields();
                while (entries.hasNext()) {
                    var entry = entries.next();
                    if (!validModelCode(entry.getKey())) throw invalid();
                    // Validate every model, including ones unrelated to this invocation.
                    models.put(entry.getKey(), modelFields(entry.getValue()));
                }
                return new Document(false, true, models);
            }
            if (node.size() != 3 || !node.path("modelCode").isTextual()
                    || !validModelCode(node.path("modelCode").textValue())
                    || !node.has("readFields") || !node.has("writeFields")) throw invalid();
            return new Document(false, false, Map.of(node.path("modelCode").textValue(),
                    new BusinessGrantDecision.Fields(false, fields(node.get("readFields")), fields(node.get("writeFields")))));
        } catch (JsonProcessingException malformed) {
            // CATCH: invalid stored authorization must never become an unrestricted grant.
            throw invalid();
        }
    }

    static String encode(ObjectMapper json, Map<String, BusinessGrantDecision.Fields> models, boolean forceVersionTwo) {
        if (models.isEmpty() || models.size() > MAX_MODELS || models.keySet().stream().anyMatch(model -> !validModelCode(model))) throw invalid();
        Map<String, Object> value;
        if (!forceVersionTwo && models.size() == 1 && !models.values().iterator().next().unrestricted()) {
            var entry = models.entrySet().iterator().next();
            value = Map.of("modelCode", entry.getKey(), "readFields", entry.getValue().readable().stream().sorted().toList(),
                    "writeFields", entry.getValue().writable().stream().sorted().toList());
        } else {
            Map<String, Object> entries = new TreeMap<>();
            models.forEach((model, fields) -> entries.put(model, fields.unrestricted() ? Map.of("unrestricted", true)
                    : Map.of("readFields", fields.readable().stream().sorted().toList(),
                            "writeFields", fields.writable().stream().sorted().toList())));
            value = Map.of("version", 2, "models", entries);
        }
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException malformed) { throw invalid(); }
    }

    private static BusinessGrantDecision.Fields modelFields(JsonNode node) {
        if (!node.isObject()) throw invalid();
        if (node.size() == 1 && node.path("unrestricted").isBoolean() && node.path("unrestricted").booleanValue())
            return BusinessGrantDecision.Fields.legacy();
        if (node.size() != 2 || !node.has("readFields") || !node.has("writeFields")) throw invalid();
        return new BusinessGrantDecision.Fields(false, fields(node.get("readFields")), fields(node.get("writeFields")));
    }

    private static Set<String> fields(JsonNode node) {
        if (node == null || !node.isArray() || node.size() > 1000) throw invalid();
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode field : node) {
            if (!field.isTextual() || !validFieldCode(field.textValue()) || !result.add(field.textValue())) throw invalid();
        }
        return result;
    }

    static boolean validFieldCode(String code) {
        return code != null && code.matches("[A-Za-z][A-Za-z0-9_]{0,127}");
    }
    private static boolean validModelCode(String code) {
        return code != null && code.matches("[A-Za-z_][A-Za-z0-9_]{0,127}");
    }
    private static MetaServiceException invalid() { return new MetaServiceException("Invalid business grant field scope"); }
}
