package com.auraboot.framework.permission.service;

import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.permission.engine.model.BusinessGrantDecision;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.Set;

/** Strict materialized grant fields, interpreted only after its scope and conditions match. */
final class BusinessGrantFieldScope {
    private BusinessGrantFieldScope() { }

    static BusinessGrantDecision.Fields parse(ObjectMapper json, String raw, String modelCode) {
        if (raw == null) return BusinessGrantDecision.Fields.legacy();
        try {
            JsonNode node = json.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(raw);
            if (node == null || !node.isObject() || node.size() != 3
                    || !node.path("modelCode").isTextual() || !modelCode.equals(node.path("modelCode").textValue())
                    || !node.has("readFields") || !node.has("writeFields")) throw invalid();
            return new BusinessGrantDecision.Fields(false, fields(node.get("readFields")), fields(node.get("writeFields")));
        } catch (JsonProcessingException malformed) {
            // CATCH: invalid stored authorization must never become an unrestricted grant.
            throw invalid();
        }
    }

    private static Set<String> fields(JsonNode node) {
        if (!node.isArray() || node.size() > 1000) throw invalid();
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode field : node) {
            if (!field.isTextual() || !validFieldCode(field.textValue())
                    || !result.add(field.textValue())) throw invalid();
        }
        return result;
    }

    static boolean validFieldCode(String code) {
        return code != null && code.matches("[A-Za-z][A-Za-z0-9_]{0,127}");
    }

    private static MetaServiceException invalid() {
        return new MetaServiceException("Invalid business grant field scope");
    }
}
