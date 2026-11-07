package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.plugin.extension.BusinessResultField;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Bounded JSON values attested against one host-loaded root document, never handler source claims. */
final class BusinessResultJsonSource {
    private static final int MAX_DOCUMENT = 1_048_576;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                    .maxStringLength(MAX_DOCUMENT).maxNumberLength(128).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private BusinessResultJsonSource() { }

    static String document(Object source) {
        try {
            String text;
            if (source instanceof String string) text = string;
            else if (source instanceof Map<?, ?> map) {
                var copy = new Budget().copy(map, 0);
                text = JSON.writeValueAsString(copy);
            } else throw BusinessResultProjectionPlan.denied();
            if (text.length() > MAX_DOCUMENT) throw BusinessResultProjectionPlan.denied();
            JsonNode root = JSON.readTree(text);
            if (root == null || !root.isObject()) throw BusinessResultProjectionPlan.denied();
            return text;
        } catch (JsonProcessingException malformed) {
            // CATCH: corrupt stored source data must never become an unbound public result.
            throw BusinessResultProjectionPlan.denied();
        }
    }

    static void requireExact(String document, BusinessResultField field, Object value) {
        if (document == null) throw BusinessResultProjectionPlan.denied();
        try {
            JsonNode node = JSON.readTree(document);
            for (String key : field.sourcePath()) {
                if (node == null || !node.isObject() || !node.has(key)) throw BusinessResultProjectionPlan.denied();
                node = node.get(key);
            }
            if (!sameValue(node, JSON.valueToTree(value))) throw BusinessResultProjectionPlan.denied();
        } catch (JsonProcessingException malformed) {
            // CATCH: the host captured this document; unexpected corruption is a closed boundary.
            throw BusinessResultProjectionPlan.denied();
        }
    }

    private static boolean sameValue(JsonNode expected, JsonNode actual) {
        if(expected==null || actual==null) return expected==actual;
        if(expected.isNumber() && actual.isNumber()) return expected.decimalValue().compareTo(actual.decimalValue())==0;
        if(expected.isArray() && actual.isArray()) {
            if(expected.size()!=actual.size()) return false;
            for(int i=0;i<expected.size();i++) if(!sameValue(expected.get(i),actual.get(i))) return false;
            return true;
        }
        if(expected.isObject() && actual.isObject()) {
            if(expected.size()!=actual.size()) return false;
            var keys=expected.fieldNames();
            while(keys.hasNext()) {String key=keys.next();if(!actual.has(key) || !sameValue(expected.get(key),actual.get(key))) return false;}
            return true;
        }
        return expected.equals(actual);
    }

    static final class Budget {
        private int nodes;
        private int characters;

        Object copy(Object value, int depth) {
            if (++nodes > 4_096 || depth > 12) throw BusinessResultProjectionPlan.denied();
            if (value == null || value instanceof Boolean) return value;
            if (value instanceof String text) { text(text, 32_768); return text; }
            if (value instanceof Number number) {
                if (!(number instanceof Byte || number instanceof Short || number instanceof Integer
                        || number instanceof Long || number instanceof java.math.BigInteger
                        || number instanceof java.math.BigDecimal || number instanceof Float || number instanceof Double))
                    throw BusinessResultProjectionPlan.denied();
                if (number instanceof Double d && !Double.isFinite(d)
                        || number instanceof Float f && !Float.isFinite(f)) throw BusinessResultProjectionPlan.denied();
                text(number.toString(), 128); return number;
            }
            if (value instanceof List<?> list) {
                if (list.size() > 512) throw BusinessResultProjectionPlan.denied();
                var copy = new ArrayList<Object>(list.size());
                for (Object item : list) copy.add(copy(item, depth + 1));
                return Collections.unmodifiableList(copy);
            }
            if (value instanceof Map<?, ?> map) {
                if (map.size() > 128) throw BusinessResultProjectionPlan.denied();
                var copy = new LinkedHashMap<String, Object>();
                for (var entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) throw BusinessResultProjectionPlan.denied();
                    text(key, 256);
                    copy.put(key, copy(entry.getValue(), depth + 1));
                }
                return Collections.unmodifiableMap(copy);
            }
            throw BusinessResultProjectionPlan.denied();
        }

        private void text(String text, int limit) {
            if (text.length() > limit || (characters += text.length()) > 524_288)
                throw BusinessResultProjectionPlan.denied();
        }
    }
}
